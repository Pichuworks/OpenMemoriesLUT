package com.sonylut.app;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.sony.scalar.hardware.CameraEx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CUSTOM LUT — A6000 胶片模拟。
 *
 * SD 卡 /LUTS/*.cube 投放 LUT。启动时先检查 LUTCACHE 分解缓存：
 * 全部就绪直接进拍照界面，否则先逐个计算新增 LUT 再进。
 * 分解为 伽马表+矩阵 写入 ISP 管线，取景/成片实时生效。
 * 拍照后自动标记：JPEG 插入 COM 段，ARW 写 XMP sidecar。
 *
 * 按键（对齐机内习惯）：
 *   拨轮1 / 方向键上下 : 浏览 LUT 列表（实时预览）
 *   拨轮2              : 强度 0-100%
 *   中央键             : 选定 / 收起列表
 *   删除键             : 关闭 LUT
 *   快门半按/全按      : 对焦 / 拍照（原生管线存储）；
 *                      对焦锁定后再次半按先解锁再重新对焦，取景中央显示对焦框
 *   MENU               : 退出（参数随 App 退出自动还原）
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback,
        CameraEx.ShutterListener {
    private static final String TAG = "SonyLut";
    private static final File LUT_DIR = new File(
            Environment.getExternalStorageDirectory(), "LUTS");
    private static final File CACHE_DIR = new File(LUT_DIR, "LUTCACHE");
    private static final File DCIM_DIR = new File(
            Environment.getExternalStorageDirectory(), "DCIM");
    private static final String OFF_NAME = "OFF（关闭）";

    // 扫描码
    private static final int SCAN_MENU = 514;
    private static final int SCAN_DELETE = 595;
    private static final int SCAN_S1 = 516;
    private static final int SCAN_S1_UP = 517;  // 半按释放
    private static final int SCAN_S2 = 518;
    private static final int SCAN_DIAL1_CW = 525;
    private static final int SCAN_DIAL1_CCW = 526;
    private static final int SCAN_DIAL2_CW = 528;
    private static final int SCAN_DIAL2_CCW = 529;
    private static final int SCAN_UP = 103;
    private static final int SCAN_DOWN = 108;

    private SurfaceHolder surfaceHolder;
    private TextView topBar, lutListView, bottomHint;
    private HudView hud;
    private CameraEx camera;
    private boolean previewStarted = false;
    private boolean takingPicture = false;
    private boolean resumed = false;
    // 最近一次 AF 状态（锁定态判断用，v0.2 可重复对焦）
    private volatile int lastAfStatus = 0;

    // LUT 状态
    private final List<File> cubeFiles = new ArrayList<File>();
    private int selection = 0;       // 列表高亮（0=OFF）
    private int appliedIndex = 0;    // 当前生效
    private int intensity = 100;
    private boolean browsing = false;
    private LutParams baseParams;    // 当前 LUT 的 100% 参数（OFF 时为 null）
    private int applySeq = 0;        // 应用请求序号（防抖）

    // 启动预计算状态
    private boolean startupComputing = false;
    private int startupTotal = 0;
    private int startupDone = 0;

    // 已标记过的照片（防重复）
    private final Set<String> taggedFiles = new HashSet<String>();

    private Handler worker;
    private final Handler mainHandler = new Handler();

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        topBar = (TextView) findViewById(R.id.topBar);
        lutListView = (TextView) findViewById(R.id.lutList);
        bottomHint = (TextView) findViewById(R.id.bottomHint);
        hud = (HudView) findViewById(R.id.hud);
        bottomHint.setText("拨轮1:选择  拨轮2:强度  确认:选定  删除:关闭  MENU:退出");

        HandlerThread t = new HandlerThread("lut-worker");
        t.start();
        worker = new Handler(t.getLooper());

        scanLuts();
        checkStartupCache();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        resumed = true;
        notifyAppInfo();
        if (startupComputing) {
            // 预计算未完成前不进拍照界面
            return;
        }
        initCamera();
    }

    /** 打开相机并起预览（可重入）。 */
    private void initCamera() {
        if (camera != null) {
            return;
        }
        try {
            camera = CameraEx.open(0, null);
            camera.setShutterListener(this);
            // AF 状态监听：驱动取景中央对焦框。
            // 回调线程不确定，只打 Log 并切主线程改 UI，不碰 camera native 调用
            camera.setAutoFocusStartListener(new CameraEx.AutoFocusStartListener() {
                public void onStart(CameraEx c) {
                    Log.i(TAG, "af start");
                    mainHandler.post(new Runnable() {
                        public void run() {
                            hud.setAfState(HudView.AF_WORKING);
                        }
                    });
                }
            });
            camera.setAutoFocusDoneListener(new CameraEx.AutoFocusDoneListener() {
                public void onDone(int status, int[] areas, CameraEx c) {
                    lastAfStatus = status;
                    Log.i(TAG, "af done: status=" + status);
                    final int hudState;
                    if (status == STATUS_LOCK || status == STATUS_LOCK_WARM) {
                        hudState = HudView.AF_LOCK;
                    } else if (status == STATUS_WORKING || status == STATUS_CONTINUOUS
                            || status == STATUS_LOCK_WARN) {
                        hudState = HudView.AF_WORKING;
                    } else {
                        hudState = HudView.AF_CLEAR;
                    }
                    mainHandler.post(new Runnable() {
                        public void run() {
                            hud.setAfState(hudState);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "CameraEx.open failed", t);
            topBar.setText("相机打开失败: " + t);
            return;
        }
        surfaceHolder.addCallback(this);
        if (surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
            startPreview();
        }
        refreshTopBar();
        // 恢复之前应用的 LUT（从播放界面返回等场景）
        if (appliedIndex > 0 && baseParams != null) {
            writePipeline(baseParams.withIntensity(intensity));
        }
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause");
        resumed = false;
        if (camera != null) {
            try {
                camera.getNormalCamera().stopPreview();
            } catch (Throwable t) {
                Log.e(TAG, "stopPreview failed", t);
            }
            camera.release();
            camera = null;
        }
        previewStarted = false;
        surfaceHolder.removeCallback(this);
        lastAfStatus = 0;
        hud.setAfState(HudView.AF_CLEAR);
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!startupComputing) {
            startPreview();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        previewStarted = false;
    }

    private void startPreview() {
        if (camera == null || previewStarted) {
            return;
        }
        try {
            camera.getNormalCamera().setPreviewDisplay(surfaceHolder);
            camera.getNormalCamera().startPreview();
            previewStarted = true;
            Log.i(TAG, "preview started");
        } catch (IOException e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    /** 拍摄类应用注册（LVG 同款）：声明 CATEGORY_REC，快门才归本 App。 */
    private void notifyAppInfo() {
        android.content.Intent intent = new android.content.Intent(
                "com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        intent.putExtra("large_category", "CATEGORY_REC");
        intent.putExtra("small_category", "APP_SHOOTING");
        sendBroadcast(intent);
    }

    // ---------------- 启动预计算 ----------------

    /** 检查所有 cube 是否都有新鲜缓存；缺的进预计算流程，算完再进拍照界面。 */
    private void checkStartupCache() {
        final List<File> missing = new ArrayList<File>();
        for (File f : cubeFiles) {
            File cache = new File(CACHE_DIR, shortName83(f.getName()) + ".LTC");
            if (!(cache.isFile() && cache.lastModified() >= f.lastModified())) {
                missing.add(f);
            }
        }
        if (missing.isEmpty()) {
            Log.i(TAG, "all LUT caches fresh, skip precompute");
            return;
        }
        startupComputing = true;
        startupTotal = missing.size();
        startupDone = 0;
        lutListView.setVisibility(View.VISIBLE);
        bottomHint.setVisibility(View.GONE);
        showStartupProgress(null);
        worker.post(new Runnable() {
            public void run() {
                for (final File f : missing) {
                    mainHandler.post(new Runnable() {
                        public void run() {
                            showStartupProgress(displayName(cubeFiles.indexOf(f) + 1));
                        }
                    });
                    try {
                        loadOrDecompose(f);
                    } catch (Throwable t) {
                        Log.e(TAG, "precompute failed: " + f.getName(), t);
                    }
                    startupDone++;
                }
                writeLutList();
                mainHandler.post(new Runnable() {
                    public void run() {
                        startupComputing = false;
                        lutListView.setVisibility(View.GONE);
                        bottomHint.setVisibility(View.VISIBLE);
                        refreshTopBar();
                        if (resumed) {
                            initCamera();
                        }
                    }
                });
            }
        });
    }

    private void showStartupProgress(String name) {
        String s = "正在计算新增LUT";
        if (name != null) {
            s += "（" + name + "）";
        }
        s += "\n" + startupDone + "/" + startupTotal;
        lutListView.setText(s);
        topBar.setText("CUSTOM LUT");
    }

    /** 把已算好的 LUT 列表写进缓存目录（8.3 文件名 LUTLIST.TXT）。 */
    private void writeLutList() {
        try {
            CACHE_DIR.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cubeFiles.size(); i++) {
                sb.append(cubeFiles.get(i).getName());
                sb.append('\t');
                sb.append(displayName(i + 1));
                sb.append('\n');
            }
            File list = new File(CACHE_DIR, "LUTLIST.TXT");
            FileOutputStream fos = new FileOutputStream(list);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Log.i(TAG, "LUTLIST.TXT written, " + cubeFiles.size() + " entries");
        } catch (Throwable t) {
            Log.e(TAG, "writeLutList failed", t);
        }
    }

    // ---------------- LUT 列表 ----------------

    private void scanLuts() {
        cubeFiles.clear();
        File[] files = LUT_DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".cube") || n.endsWith(".cub"))) {
                    cubeFiles.add(f);
                }
            }
        }
        Collections.sort(cubeFiles, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        Log.i(TAG, "found " + cubeFiles.size() + " cubes in " + LUT_DIR);
        refreshTopBar();
    }

    private final java.util.Map<String, String> titleCache =
            new java.util.HashMap<String, String>();

    private String displayName(int index) {
        if (index == 0) {
            return OFF_NAME;
        }
        File f = cubeFiles.get(index - 1);
        String path = f.getPath();
        String title = titleCache.get(path);
        if (title == null) {
            title = readTitle(f);
            titleCache.put(path, title);
        }
        return title;
    }

    /** 从 .cube 头部读 TITLE；没有则退回文件名（去扩展名）。 */
    private static String readTitle(File f) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 10) {
                line = line.trim();
                if (line.toUpperCase().startsWith("TITLE")) {
                    int q1 = line.indexOf('"');
                    int q2 = line.lastIndexOf('"');
                    if (q1 >= 0 && q2 > q1) {
                        return line.substring(q1 + 1, q2);
                    }
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignore) {}
            }
        }
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void refreshTopBar() {
        if (startupComputing) {
            return;
        }
        String s;
        if (cubeFiles.isEmpty()) {
            s = "SD卡 /LUTS 下未发现 .cube 文件";
        } else if (appliedIndex == 0) {
            s = "未应用 LUT";
        } else {
            s = displayName(appliedIndex) + "   强度 " + intensity + "%";
        }
        topBar.setText(s);
    }

    private void refreshListView() {
        if (!browsing) {
            lutListView.setVisibility(View.GONE);
            return;
        }
        int total = cubeFiles.size() + 1;
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, Math.min(selection - 2, total - 5));
        int end = Math.min(total, start + 5);
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(i == selection ? "> " : "   ");
            sb.append(displayName(i));
            if (i == appliedIndex) {
                sb.append("  ●");
            }
        }
        lutListView.setText(sb.toString());
        lutListView.setVisibility(View.VISIBLE);
    }

    // ---------------- 参数应用 ----------------

    private void requestApply(final int index) {
        final int seq = ++applySeq;
        if (index == 0) {
            baseParams = null;
            appliedIndex = 0;
            writePipeline(null);
            refreshTopBar();
            refreshListView();
            return;
        }
        topBar.setText(displayName(index) + "   计算中...");
        worker.post(new Runnable() {
            public void run() {
                final LutParams params;
                try {
                    params = loadOrDecompose(cubeFiles.get(index - 1));
                } catch (Throwable t) {
                    Log.e(TAG, "decompose failed", t);
                    mainHandler.post(new Runnable() {
                        public void run() {
                            topBar.setText("分解失败");
                        }
                    });
                    return;
                }
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (seq != applySeq) {
                            return; // 已被更新的选择覆盖
                        }
                        baseParams = params;
                        appliedIndex = index;
                        writePipeline(params.withIntensity(intensity));
                        refreshTopBar();
                        refreshListView();
                    }
                });
            }
        });
    }

    /** 缓存命中则秒读，否则机内分解并写缓存。
     *  注意：A6000 的 SD 卡挂载是 8.3 短文件名，缓存目录/文件名必须 8.3 合规。 */
    private LutParams loadOrDecompose(File cubeFile) throws IOException {
        CACHE_DIR.mkdirs();
        File cache = new File(CACHE_DIR, shortName83(cubeFile.getName()) + ".LTC");
        if (cache.isFile() && cache.lastModified() >= cubeFile.lastModified()) {
            Log.i(TAG, "cache hit: " + cache.getName());
            return LutParams.load(cache);
        }
        long t0 = System.currentTimeMillis();
        Cube cube = Cube.load(cubeFile);
        LutParams params = Decomposer.decompose(cube);
        Log.i(TAG, "decomposed " + cubeFile.getName() + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        try {
            params.save(cache);
        } catch (IOException e) {
            Log.e(TAG, "cache write failed", e);
        }
        return params;
    }

    /** 文件名转 8.3 短名（不含扩展名部分）：去非字母数字，截 8 字符，大写。 */
    private static String shortName83(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 8; i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.length() > 0 ? sb.toString() : "LUT";
    }

    /** 写管线（主线程）。params=null 表示关闭。 */
    private void writePipeline(LutParams params) {
        if (camera == null) {
            return;
        }
        try {
            if (params == null) {
                camera.setExtendedGammaTable(null);
                writeMatrix(new int[]{1024, 0, 0, 0, 1024, 0, 0, 0, 1024});
                Log.i(TAG, "pipeline cleared");
                return;
            }
            CameraEx.GammaTable table = camera.createGammaTable();
            table.setPictureEffectGammaForceOff(true);
            byte[] buf = new byte[2048];
            for (int i = 0; i < 1024; i++) {
                int v = params.gamma[i];
                buf[2 * i] = (byte) (v & 0xff);
                buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
            }
            table.write(new ByteArrayInputStream(buf));
            camera.setExtendedGammaTable(table);
            writeMatrix(params.matrix);
            Log.i(TAG, "pipeline written");
        } catch (Throwable t) {
            Log.e(TAG, "writePipeline failed", t);
            topBar.setText("写入失败: " + t);
        }
    }

    private void writeMatrix(int[] m) {
        Camera cam = camera.getNormalCamera();
        Camera.Parameters p = cam.getParameters();
        CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
        mod.setRGBMatrix(m);
        cam.setParameters(p);
    }

    private void applyIntensity() {
        if (appliedIndex > 0 && baseParams != null) {
            writePipeline(baseParams.withIntensity(intensity));
        }
        refreshTopBar();
    }

    // ---------------- 拍照 ----------------

    private void shoot() {
        if (camera == null || takingPicture) {
            return;
        }
        try {
            takingPicture = true;
            camera.burstableTakePicture();
        } catch (Throwable t) {
            takingPicture = false;
            Log.e(TAG, "burstableTakePicture failed", t);
        }
    }

    @Override
    public void onShutter(int i, CameraEx cameraEx) {
        try {
            cameraEx.cancelTakePicture();
        } catch (Throwable t) {
            Log.e(TAG, "cancelTakePicture failed", t);
        }
        takingPicture = false;
        scheduleTagging();
    }

    // ---------------- 成片 LUT 标记 ----------------

    /** 拍照后延迟扫 DCIM 最新文件：JPEG 插 COM 段，ARW 写 XMP sidecar。 */
    private void scheduleTagging() {
        if (appliedIndex <= 0) {
            return;
        }
        final String label = displayName(appliedIndex) + " " + intensity + "%";
        worker.postDelayed(new Runnable() {
            public void run() {
                tagNewestPhoto(label);
            }
        }, 1500);
    }

    private void tagNewestPhoto(String label) {
        try {
            File newest = findNewestPhoto(DCIM_DIR);
            if (newest == null) {
                return;
            }
            // 只处理 30 秒内的新文件，避免标记旧照片
            if (System.currentTimeMillis() - newest.lastModified() > 30000) {
                Log.i(TAG, "newest photo too old, skip tagging");
                return;
            }
            String path = newest.getPath();
            if (taggedFiles.contains(path)) {
                return;
            }
            String n = newest.getName().toUpperCase();
            if (n.endsWith(".JPG")) {
                insertJpegComment(newest, "CUSTOM LUT: " + label);
            } else {
                writeXmpSidecar(newest, "CUSTOM LUT: " + label);
            }
            taggedFiles.add(path);
            Log.i(TAG, "tagged " + newest.getName() + " : " + label);
        } catch (Throwable t) {
            Log.e(TAG, "tagNewestPhoto failed", t);
        }
    }

    /** 在 DCIM 各子目录里找最新的 JPG/ARW。 */
    private static File findNewestPhoto(File dcim) {
        File newest = null;
        File[] subs = dcim.listFiles();
        if (subs == null) {
            return null;
        }
        for (File sub : subs) {
            if (!sub.isDirectory()) {
                continue;
            }
            File[] files = sub.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                String n = f.getName().toUpperCase();
                if (!(n.endsWith(".JPG") || n.endsWith(".ARW"))) {
                    continue;
                }
                if (newest == null || f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }
        }
        return newest;
    }

    /** JPEG 字节手术：SOI 后插 COM(FFFE) 段。经 8.3 临时文件替换回原文件。 */
    private static void insertJpegComment(File jpg, String comment) throws IOException {
        byte[] data = readAll(jpg);
        if (data.length < 4
                || (data[0] & 0xff) != 0xFF || (data[1] & 0xff) != 0xD8) {
            Log.w(TAG, "not a jpeg: " + jpg.getName());
            return;
        }
        // 已有 COM 标记过就跳过（防重）
        if ((data[2] & 0xff) == 0xFF && (data[3] & 0xff) == 0xFE) {
            int len = ((data[4] & 0xff) << 8) | (data[5] & 0xff);
            if (2 + len <= data.length) {
                String existing = new String(data, 6, len - 2, "UTF-8");
                if (existing.startsWith("CUSTOM LUT:")) {
                    Log.i(TAG, "already tagged, skip");
                    return;
                }
            }
        }
        byte[] payload = comment.getBytes("UTF-8");
        if (payload.length > 65530) {
            return;
        }
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(data.length + payload.length + 4);
        out.write(data, 0, 2);
        out.write(0xFF);
        out.write(0xFE);
        int segLen = payload.length + 2; // 长度字段包含自身
        out.write((segLen >> 8) & 0xff);
        out.write(segLen & 0xff);
        out.write(payload);
        out.write(data, 2, data.length - 2);

        File tmp = new File(jpg.getParentFile(), "LUTTMP.TMP");
        writeAll(tmp, out.toByteArray());
        if (!jpg.delete() || !tmp.renameTo(jpg)) {
            tmp.delete();
            throw new IOException("replace failed: " + jpg.getName());
        }
    }

    /** ARW 不动本体，写同名 XMP sidecar（8.3 文件名，Lightroom 可读）。 */
    private static void writeXmpSidecar(File arw, String label) throws IOException {
        String name = arw.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File xmp = new File(arw.getParentFile(), base + ".XMP");
        String esc = xmlEscape(label);
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description"
                + " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmp:Label=\"" + esc + "\">\n"
                + "   <dc:description><rdf:Alt>"
                + "<rdf:li xml:lang=\"x-default\">" + esc + "</rdf:li>"
                + "</rdf:Alt></dc:description>\n"
                + "  </rdf:Description>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n";
        FileOutputStream fos = new FileOutputStream(xmp);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int code = event.getKeyCode();
        int scan = event.getScanCode();
        Log.i(TAG, "keyDown: code=" + code + " scan=" + scan);

        if (scan == SCAN_MENU || code == KeyEvent.KEYCODE_MENU) {
            exitProperly();
            return true;
        }
        if (startupComputing) {
            return true; // 预计算期间吞掉其余按键
        }
        if (scan == SCAN_DELETE || code == KeyEvent.KEYCODE_DEL) {
            selection = 0;
            browsing = false;
            requestApply(0);
            refreshListView();
            return true;
        }
        if (scan == SCAN_S2 && camera != null) {
            shoot();
            return true;
        }
        if (scan == SCAN_S1 && camera != null) {
            // 实测：HAL 合焦锁定后必须 cancelAutoFocus 才能再次 autoFocus
            if (lastAfStatus == CameraEx.AutoFocusDoneListener.STATUS_LOCK
                    || lastAfStatus
                            == CameraEx.AutoFocusDoneListener.STATUS_LOCK_WARM) {
                // 锁定态：先 cancel 解锁，200ms 后再重新对焦（立即对焦 HAL 不理）
                try {
                    camera.getNormalCamera().cancelAutoFocus();
                } catch (Throwable t) {
                    Log.i(TAG, "cancelAutoFocus (pre-S1) failed: " + t);
                }
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        if (camera == null) {
                            return;
                        }
                        try {
                            camera.getNormalCamera().autoFocus(null);
                        } catch (Throwable t) {
                            Log.e(TAG, "autoFocus failed", t);
                        }
                    }
                }, 200);
            } else {
                try {
                    camera.getNormalCamera().autoFocus(null);
                } catch (Throwable t) {
                    Log.e(TAG, "autoFocus failed", t);
                }
            }
            return true;
        }
        if (scan == SCAN_S1_UP && camera != null) {
            // 松开半按：仅锁定态才需要 cancelAutoFocus 解锁
            if (lastAfStatus == CameraEx.AutoFocusDoneListener.STATUS_LOCK
                    || lastAfStatus
                            == CameraEx.AutoFocusDoneListener.STATUS_LOCK_WARM) {
                try {
                    camera.getNormalCamera().cancelAutoFocus();
                } catch (Throwable t) {
                    Log.i(TAG, "cancelAutoFocus (S1-up) failed: " + t);
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (browsing) {
                browsing = false; // 选定，收起列表
                refreshListView();
            } else {
                browsing = true;
                selection = appliedIndex;
                refreshListView();
            }
            return true;
        }
        boolean prev = (scan == SCAN_DIAL1_CCW || scan == SCAN_UP);
        boolean next = (scan == SCAN_DIAL1_CW || scan == SCAN_DOWN);
        if (prev || next) {
            if (!browsing) {
                browsing = true;
                selection = appliedIndex;
            }
            int total = cubeFiles.size() + 1;
            if (total > 0) {
                selection = (selection + (next ? 1 : total - 1)) % total;
            }
            refreshListView();
            debouncePreview();
            return true;
        }
        boolean intDown = (scan == SCAN_DIAL2_CCW);
        boolean intUp = (scan == SCAN_DIAL2_CW);
        if (intDown || intUp) {
            intensity = Math.max(0, Math.min(100, intensity + (intUp ? 5 : -5)));
            applyIntensity();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        int code = event.getKeyCode();
        if (scan == SCAN_MENU || scan == SCAN_DELETE || scan == SCAN_S1 || scan == SCAN_S2
                || scan == SCAN_S1_UP
                || scan == SCAN_DIAL1_CW || scan == SCAN_DIAL1_CCW
                || scan == SCAN_DIAL2_CW || scan == SCAN_DIAL2_CCW
                || scan == SCAN_UP || scan == SCAN_DOWN
                || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DEL
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == 0) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** 浏览中移动选择后 400ms 防抖预览。 */
    private void debouncePreview() {
        final int seq = ++applySeq;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (seq == applySeq && browsing) {
                    requestApply(selection);
                }
            }
        }, 400);
    }

    /** LVG 的正统退出：先 DAConnectionManager.finish() 让系统接管显示。 */
    private void exitProperly() {
        try {
            Class<?> c = Class.forName("android.app.DAConnectionManager");
            Object mgr = c.getDeclaredConstructor(android.content.Context.class).newInstance(this);
            c.getMethod("finish").invoke(mgr);
        } catch (Throwable t) {
            Log.e(TAG, "DAConnectionManager.finish() failed", t);
        }
        finish();
    }
}
