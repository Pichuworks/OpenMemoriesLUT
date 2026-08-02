# 构建与安装

## 工具链要求

- JDK（含 `javac` / `keytool` / `jarsigner`，JDK 8–17 均可）
- Android SDK，需要两个组件：
  - `platforms/android-10`（Android 2.3.3，PMCA 相机系统级别）
  - `build-tools/25.0.2`（使用其中的 `aapt` 和 `dx`）
- 一台已破解的索尼 PMCA 相机（见下）

## 相机侧准备（一次性）

1. 用 [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) 给相机安装 [OpenMemories-Tweak](https://github.com/ma1co/OpenMemories-Tweak)（USB 连接，`pmca-console.py install -f OpenMemories-Tweak.apk`）。
2. 在相机的 Tweak 应用中开启 **ADB** 和 **WiFi**，记下相机 IP。

## 提取编译 stub（必需，本仓库不提供）

代码调用的 `com.sony.scalar.hardware.CameraEx` 是相机私有 API，编译时需要接口 stub。
stub 是索尼固件的衍生物，**请从你自己持有的相机提取，仅供学习研究**：

```bash
# 1. 连接相机（WiFi 或 USB 网络）
adb connect <相机IP>:5555

# 2. 拉出 framework（找到含 scalar 类的 JAR/DEX，通常是 framework.jar 或专门的 scalar 包）
adb pull /system/framework ./framework-dump

# 3. deodex（如是 .odex 需先 deodex）后用 dex2jar 转换：
d2j-dex2jar framework-dump/<含scalar的dex或jar> -o stubs/sony_cameraex_stubs.jar
```

把得到的 `sony_cameraex_stubs.jar` 放到本仓库 `stubs/` 目录（已 gitignore），
或通过环境变量 `SONY_STUBS` 指向它。

## 构建

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
# 可选：export SONY_STUBS=/path/to/sony_cameraex_stubs.jar
bash build.sh
# 产出：CustomLut.apk（约 30KB）
```

首次构建会自动生成本地 `debug.keystore`（已 gitignore）。
Android 2.3 只认 v1（jarsigner）签名；脚本会临时放开现代 JDK 对 SHA1 的禁用，仅对本次签名调用生效。

## 安装到相机

```bash
# USB 连接（MTP 模式），macOS 用户注意先退出 Lightroom/Photos（会抢占 PTP 通道）
python3 pmca-console.py install -f CustomLut.apk
```

## 投放 LUT

SD 卡挂载为 8.3 短文件名 FAT，目录/文件名必须 8.3 合规：

```
/LUTS/ASTIA.CUB
/LUTS/VELVIA.CUB
...
```

首次放入新 LUT 后，App 启动时会显示「正在计算新增LUT」逐个分解（约 6.5–8 秒/个），
缓存写入 `/LUTS/LUTCACHE/`，之后启动直接进拍照界面。
