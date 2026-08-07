package com.sonylut.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 取景覆盖层：只画 AF 对焦框（v0.2 新增）。
 * 半按快门时取景中央显示对焦框：白色=对焦中，绿色=合焦（1.5 秒后自动消隐）。
 */
public class HudView extends View {
    // AF 对焦框状态
    public static final int AF_CLEAR = 0;    // 不画
    public static final int AF_WORKING = 1;  // 白：对焦中
    public static final int AF_LOCK = 2;     // 绿：合焦

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF00E676;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private int afState = AF_CLEAR;

    // 合焦后 1.5 秒自动消隐
    private final Runnable afHide = new Runnable() {
        public void run() {
            if (afState == AF_LOCK) {
                afState = AF_CLEAR;
                invalidate();
            }
        }
    };

    public HudView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** AF 对焦框：AF_WORKING 白 / AF_LOCK 绿（1.5 秒自隐）/ AF_CLEAR 清除。 */
    public void setAfState(int state) {
        afState = state;
        removeCallbacks(afHide);
        if (state == AF_LOCK) {
            postDelayed(afHide, 1500);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (afState == AF_CLEAR) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float bw = w * 0.17f;
        float bh = h * 0.23f;
        float x0 = (w - bw) / 2;
        float y0 = (h - bh) / 2;
        int col = afState == AF_LOCK ? GREEN : WHITE;
        rf.set(x0, y0, x0 + bw, y0 + bh);
        p.setStyle(Paint.Style.STROKE);
        // 无 shadow API（API 10），垫一层低透明加宽描边让框在亮景上也可辨
        p.setStrokeWidth(h * 0.008f);
        p.setColor((col & 0x00FFFFFF) | 0x3C000000);
        float rad = Math.min(bw, bh) * 0.10f;
        cv.drawRoundRect(rf, rad, rad, p);
        p.setStrokeWidth(1.5f);
        p.setColor(col);
        cv.drawRoundRect(rf, rad, rad, p);
    }
}
