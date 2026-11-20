# Third-Party Notices

FFlue 以 [GPL-3.0](LICENSE) 发布。如果你分发 FFlue 或它的修改版，必须同时保留本文件与 `LICENSE`，
并提供完整对应源码。

## 上游项目

### Flue

- 上游：<https://github.com/xumidcm/Flue-next>（作者 xumidcm）
- 许可证：GPL-3.0
- 说明：FFlue 以 Flue 2.3.3 的源码为开发基线（Kotlin namespace `com.flue.launcher` 沿用上游），
  在其上增加伪装桌面、模式切换等功能。上游的版权声明与署名原样保留。

### Fake-desktop

- 版权：Copyright (c) 2026 gaozaiya
- 许可证：MIT（原文如下，已被 MIT 覆盖的原有材料继续适用该许可）

```text
MIT License

Copyright (c) 2026 gaozaiya

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

以下第三方组件清单沿用上游 Flue 的 THIRD_PARTY_NOTICES，并按 FFlue 的依赖核对过。

## 随 APK 分发的二进制

### PRoot

- 文件：`app/src/main/jniLibs/arm64-v8a/libproot_exec.so`、`libproot_loader.so`
- 版权：Copyright (C) 2015 STMicroelectronics
- 许可证：GPL-2.0-or-later
- 上游：<https://github.com/termux/proot>（基于 <https://proot.me>）
- 说明：这是 Termux 打过补丁的 PRoot，由 Flue 通过 `ProcessBuilder` 作为独立进程启动，
  用于在设备上运行 Agent 的 Linux rootfs。它不与 Flue 的 Kotlin 代码静态链接。
- 对应源码：见 [`third_party/proot`](third_party/proot/README.md)。
  该目录记录精确的上游提交、Termux 补丁与 NDK 构建参数，是 GPLv2 §3 意义上的对应源码入口。

### FFmpegKit

- 依赖：`dev.ffmpegkit-maintained:ffmpeg-kit-video`
- 许可证：LGPL-3.0（以所依赖 AAR 内的 LICENSE 声明为准；若替换为含 x264/x265 的
  `video-gpl` / `full-gpl` 变体，则整体按 GPL-3.0 分发）
- 上游：<https://github.com/arthenica/ffmpeg-kit> 的社区维护分支
- 说明：以未修改的动态库（`.so`）形式使用，用户可以自行替换为兼容构建。

## 编译期与运行期依赖

| 组件 | 坐标 | 许可证 |
|---|---|---|
| AndroidX / Jetpack Compose | `androidx.*` | Apache-2.0 |
| MIUIX | `top.yukonga.miuix.kmp` | Apache-2.0 |
| Cloudy | `com.github.skydoves:cloudy` | Apache-2.0 |
| BlurView | `com.github.Dimezis:BlurView` | Apache-2.0 |
| LuaJ | `org.luaj:luaj-jse` | MIT |
| XZ for Java | `org.tukaani:xz` | Public Domain |
| smart-exception-java | `com.arthenica:smart-exception-java` | 见上游仓库 |
| JUnit | `junit:junit` | EPL-1.0 |
| Robolectric | `org.robolectric:robolectric` | MIT |

## 运行期下载、不随项目分发的组件

- **Ubuntu Base 24.04 rootfs**：Agent 工作区在用户主动点击安装时，从
  `cdimage.ubuntu.com` 下载官方 arm64 镜像到应用私有目录。项目本身不包含、
  也不再分发该镜像；镜像内组件的许可证见镜像自带的 `/usr/share/doc`。
- **系统表盘与第三方表盘包**：由用户在设备上导入，本项目不附带任何表盘包。

## 兼容性与商标

Flue / FFlue 是独立的桌面启动器实现，**不是** WatchMaker、JB 表盘或其他表盘平台的官方产品。
项目中的兼容运行时（`watchface/standard`、`watchface/jbwatch`、`watchface/dingdingcat`）
是按其公开的文件格式做互操作实现，相关名称与商标归各自所有者。

## 参考资料

下列项目影响了 Flue 的表盘运行时与外部数据接口设计，用于行为对照而非直接引入代码：

- <https://gitlab.com/ailife8881/wftools>
- <https://gitlab.com/ailife8881/watchmaker-definition>
- <https://gitlab.com/ailife8881/clox>
