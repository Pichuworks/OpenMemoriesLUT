# CUSTOM LUT — 索尼 A6000 自定义 LUT 胶片模拟

> 把标准 `.cube` 3D LUT 文件丢进 SD 卡，A6000 取景器实时预览影调，JPEG 直出带 LUT——等于给这台 2014 年的老相机装上可无限扩展的「胶片模拟」。

*English summary: A PMCA camera app for the Sony ILCE-6000 (A6000) that decomposes standard .cube 3D LUTs into a 1024-point gamma table + 3×3 RGB matrix and writes them into the camera's ISP pipeline via the private `com.sony.scalar.hardware.CameraEx` API. Live view and JPEG output carry the LUT look in real time. This is an independent interoperability-research project, not affiliated with Sony. See disclaimer below.*

已在 A6000（固件 3.21）真机全流程验证。其他 PMCA 机型（A7/RX 系）接口存在但未验证，定点格式/表深可能有差异。

## 功能

- **SD 卡投放 LUT**：`.cube` 文件放 `/LUTS/` 目录即可（注意相机 SD 卡挂载为 8.3 短文件名，文件名需 8.3 合规，如 `ASTIA.CUB`）
- **实时预览**：浏览列表时 LUT 实时写入 ISP 管线，取景器所见即所得
- **强度无级调节**：0–100%，参数插值，无需重新分解
- **原生管线拍照**：走机身原生 ISP/存储管线，P/A/S/M 曝光模式自动维持，成片带 LUT
- **启动预计算**：新增 LUT 启动时自动机内分解（单个约 6.5–8 秒），缓存于 `LUTCACHE/`，二次使用秒开
- **成片元数据**：JPEG 自动插入 COM 注释段（`CUSTOM LUT: <名称> <强度>%`），ARW 写同名 XMP sidecar（Lightroom 可读）
- **参数 App 级作用域**：退出 App 自动还原，不污染机内设置

## 按键映射

| 按键 | 功能 |
|---|---|
| 拨轮 1 / 方向键上下 | 浏览 LUT 列表（防抖实时预览） |
| 拨轮 2 | LUT 强度 0–100% |
| 中央键 | 选定 / 收起列表 |
| 删除键 | 关闭 LUT |
| 快门半按 / 全按 | 对焦 / 拍照 |
| MENU | 退出 App |

## 工作原理

索尼官方 PMCA 应用 Liveview Grading（2014）的逆向结论：实时调色不需要碰取景帧缓冲——直接往相机 ISP 硬件管线写参数即可，取景/拍照/录像全链路自动生效。

本 App 把任意 3D LUT 用**带约束的交替最小二乘**分解为管线支持的两类参数：

```
前向模型：out = clip( M · g(x) )
  g：1024 点 10bit 伽马表（三通道共用，setExtendedGammaTable）
  M：3×3 RGB 矩阵（×1024 定点，setRGBMatrix）
```

算法自洽性验证：曲线还原 ≤2 LSB、矩阵 0 误差。真实胶片 LUT 保真度：均值约 3–9/255 灰阶，误差尾部集中在高饱和分色相扭曲区域（1D 曲线+线性矩阵的结构性表达上限）。

## 构建与安装

见 [docs/BUILD.md](docs/BUILD.md)。要点：Android SDK（platform android-10 + build-tools 25.0.2）、从自己的相机提取编译 stub JAR（本仓库不提供索尼文件）、`pmca-console install` 装机。

## LUT 素材

本仓库不附带 LUT 文件。胶片模拟 LUT 可从开源项目获取：[YahiaAngelo/Film-Luts](https://github.com/YahiaAngelo/Film-Luts)（Kodachrome、Ektachrome、Provia、Velvia、Tri-X 等）。请使用普通色彩 LUT，不要用 LOG 套色 LUT。

## 致谢

- [ma1co/Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) — PMCA 安装通道逆向
- [ma1co/OpenMemories-Tweak](https://github.com/ma1co/OpenMemories-Tweak) — 相机端 ADB/telnet
- [ma1co/OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) — 第三方相机 App 开发参考
- [YahiaAngelo/Film-Luts](https://github.com/YahiaAngelo/Film-Luts) — 胶片模拟 LUT 素材

## 免责声明

- 本项目是**个人互操作性研究项目**，与索尼公司（Sony Corporation）无任何关联，未获其认可或授权。Sony、α（Alpha）、ILCE 等商标归原权利人所有，此处仅为描述兼容性而使用。
- 本仓库**不包含任何索尼版权文件**（固件、官方应用、反编译产物或其衍生 JAR）。编译所需的接口 stub 需由使用者从**自己持有的相机**中提取，仅供学习研究。
- 向相机安装第三方应用可能导致保修失效，操作风险由使用者自行承担。作者不对任何设备损坏、数据丢失负责。
- 本项目以 MIT 许可证发布，按「原样」提供，无任何明示或暗示担保。

## License

[MIT](LICENSE)
