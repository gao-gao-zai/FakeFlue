# FFlue

FFlue（FakeFlue）是基于 [Flue](https://github.com/xumidcm/Flue-next) 2.3.3 的 Android 手表桌面派生版本。在原版完整桌面之上，增加伪装桌面、隐藏管理入口和独立配置，并支持退出到其他桌面。

- 包名：`com.gaozai.surface`，可与原版 Flue（`com.flue.launcher`）同时安装。
- 许可：[GPL-3.0](LICENSE)，上游与第三方说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。感谢 Flue 原作者 xumidcm 及所有贡献者。
- 当前测试交付版本：`2.3.3 / versionCode 28`。版本号仍待正式发布前确定。

P0–P7 主体开发及签名、数据保护、显示适配和控件补漏已合并。自动验证已完成，设备专项与旧版兼容仍有待验收项，详见[开发与验收状态](docs/PROJECT_STATUS.md)。

## 主要功能

| 功能 | 当前实现 |
| --- | --- |
| 三种运行模式 | 完整桌面、伪装桌面、外部桌面；完整与伪装配置分开保存 |
| 伪装公开界面 | 公开应用白名单、名称与图标覆盖、公开设置、通知和小组件访问限制 |
| 隐藏管理 | 五区域密码、隐藏抽屉、管理菜单、密码重置与强制操作教程 |
| 显示适配 | 安全区、独立应用 DPI、设置页右滑返回；Apple、Material 3、MIUIX 三种风格 |
| 工具与通信 | 电池、闹钟、计时器、秒表、计算器，以及独立电话和短信实现 |
| 桌面保护 | 桌面进入密码、磁贴／Intent／实体键快速进入、Root 导航守护与有界恢复 |
| 表盘与数据 | 复用 Flue 表盘能力，增加启动分段计时、Watchmaker 持久准备缓存、备份校验和媒体替换保护 |
| 控件无障碍 | 默认 Apple 滑条提供进度与调节动作，开关提供角色、状态及标准点击 |

电话短信需要设备支持相应硬件和默认应用角色；导航守护依赖可用的 Root 能力。相关手表 ROM、SIM 与实体键行为尚未完成实机验收。

## 开始使用

1. 安装使用正式发布密钥签名的测试 APK。
2. 首次启动按向导选择完整桌面，或完成伪装专属配置：安全区、DPI、功能授权、表盘、公开应用、隐藏密码和退出目标。
3. 进入伪装前完成隐藏操作教程。后续通过应用内已配置的管理入口维护设置。
4. 完整桌面使用完整配置；外部模式转交给选定的其他桌面。伪装中的临时管理访问保持原模式。

当前测试包与旧版应用的覆盖升级尚未验证。安装身份、版本号和签名说明见[发布与签名指南](docs/RELEASING.md)。

## 验证情况

应用验收基线为 [`f20d343`](https://github.com/gao-gao-zai/FakeFlue/commit/f20d3430c1169644906cd8fcc2987b167026383b)：

- 单元测试总计 2229 项，0 失败，5 项因缺少本地表盘样本跳过。
- Android 11（API 30）Debug 模拟器 18 项，0 失败、0 跳过。
- R8 Release 模拟器首轮 3 项、强制停止后恢复 1 项，全部通过。
- 本地正式签名 APK 已校验证书、包名、版本和 R8 映射，生产代码与该基线一致。

[合并前模拟器与单元检查](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44995141399) · [合并后主分支检查](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44996335333)

CI 模拟器使用临时测试签名，正式交付沿用已备份的本地发布密钥。当前仅在 Android 11 模拟器验证，实机、其他 Android 版本与性能结论见[待验证项](docs/PROJECT_STATUS.md)。

## 构建与测试

构建环境为 JDK 21、Gradle 8.13、Android SDK 36 与 Build Tools 35.0.0，AGP 8.13.2、Kotlin 2.3.21。Java／Kotlin 字节码目标为 17。

应用声明最低 Android 8.1（API 27），`targetSdk` 与 `compileSdk` 均为 36；最低版本声明不等于已完成各版本设备验收。

配置好 JDK 和 Android SDK 后，在仓库根目录执行：

```bash
bash gradlew --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。输出位置：

- Debug APK：`app/build/outputs/apk/debug/`。
- 单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

正式签名构建的配置、验证命令和产物归档见[发布与签名指南](docs/RELEASING.md)。

## GitHub Actions

工作流为 [android-ci.yml](.github/workflows/android-ci.yml)。

- `main`、`yzp/**` 的 push，以及 Pull Request：单元测试、Debug APK 与独立 Release 验收 APK 构建。
- Pull Request 和手动运行：额外执行 API 30 Debug 仪器测试与 R8 Release 模拟器验收。
- 手动勾选 `build_release` 或推送 `v*` 标签：额外执行正式签名 Release 构建，需要事先配置签名 Secrets。

当前交付选择本地正式签名；云端正式签名任务已实现，尚未配置并完成远端端到端验收。临时测试签名不能替代发布身份。

## 文档与源码入口

- [开发与验收状态](docs/PROJECT_STATUS.md)：阶段、证据与未闭环项。
- [发布与签名指南](docs/RELEASING.md)：当前 APK 校验信息、签名与正式发布检查。
- [开发交接](HANDOFF.md)与 [Git 工作流](docs/GIT_WORKFLOW.md)。
- [旧设置页适配验收](docs/validation/2026-11-22-legacy-settings.md)、[默认控件验收](docs/validation/2026-11-22-apple-controls.md)、[启动计时记录表](docs/validation/2026-11-21-startup-timing.md)。
- [CHANGELOG.md](CHANGELOG.md)保留上游历史记录，其中旧版本号和旧 PR 不代表本项目当前版本。

新增功能集中在 `app/src/main/kotlin/com/flue/launcher/surface/`，模式与配置从 [SurfaceRuntime.kt](app/src/main/kotlin/com/flue/launcher/surface/SurfaceRuntime.kt) 接入。复用的桌面与设置入口仍保留在 [LauncherActivity.kt](app/src/main/kotlin/com/example/wlauncher/LauncherActivity.kt) 和 [SettingsActivity.kt](app/src/main/kotlin/com/example/wlauncher/SettingsActivity.kt)。
