# FFlue 开发与验收状态

更新日期：2026-11-22。应用验收基线：[`f20d3430c1169644906cd8fcc2987b167026383b`](https://github.com/gao-gao-zai/FakeFlue/commit/f20d3430c1169644906cd8fcc2987b167026383b)。后续纯文档整理沿用该应用基线。

本表按已合并实现、测试报告和交接记录整理。P0–P7 主体开发已合并；代码完成、自动验证通过和设备验收分别记录，不将尚未执行的验收标记为通过。

## 功能与阶段

| 范围 | 开发状态与合并记录 | 验收状态与边界 |
| --- | --- | --- |
| P0–P1：独立身份、模式和配置基础 | 已纳入[首次公开基线](https://github.com/gao-gao-zai/FakeFlue/commit/517b5a7)；完整／伪装／外部模式及配置隔离已实现 | 现有单元与模式旅程覆盖；旧版升级样本待补 |
| P2a：安全区、独立 DPI | [#1](https://github.com/gao-gao-zai/FakeFlue/pull/1) 已合并 | 模拟器覆盖 DPI 草稿、保存、取消和重启恢复；真实面板与系统边距待验 |
| P2b：公开界面、应用、小组件和通知边界 | [#2](https://github.com/gao-gao-zai/FakeFlue/pull/2)、[#3](https://github.com/gao-gao-zai/FakeFlue/pull/3)、[#4](https://github.com/gao-gao-zai/FakeFlue/pull/4)、[#5](https://github.com/gao-gao-zai/FakeFlue/pull/5) 已合并 | 访问策略有自动测试；通知与第三方应用的 ROM 行为待设备验收 |
| P3–P4：隐藏入口、密码、管理与强制教程 | [#6](https://github.com/gao-gao-zai/FakeFlue/pull/6)、[#7](https://github.com/gao-gao-zai/FakeFlue/pull/7) 已合并 | 有状态规则、密码和模式旅程测试；中断与硬件行为仍保留设备验证边界 |
| P5：Root 导航守护、设备页放行和有界恢复 | [#8](https://github.com/gao-gao-zai/FakeFlue/pull/8) 已合并 | 有自动检查和模拟器诊断；目标 Root 手表、系统命令与 ROM 恢复行为未做实机验收 |
| P6：桌面密码、快速进入、工具、电话短信 | [#9](https://github.com/gao-gao-zai/FakeFlue/pull/9)、[#10](https://github.com/gao-gao-zai/FakeFlue/pull/10)、[#11](https://github.com/gao-gao-zai/FakeFlue/pull/11) 已合并 | 工具与规则有单元测试；真实 SIM、来电、短信、锁屏闹钟、实体键待验 |
| P7：启动、备份可靠性与准备缓存（R35–R37） | [#12](https://github.com/gao-gao-zai/FakeFlue/pull/12)、[#13](https://github.com/gao-gao-zai/FakeFlue/pull/13)、[#14](https://github.com/gao-gao-zai/FakeFlue/pull/14) 已合并 | 故障处理、缓存与持久化有回归；同机性能对比及历史数据兼容未闭环 |
| 签名与媒体替换保护 | [#15](https://github.com/gao-gao-zai/FakeFlue/pull/15)、[#16](https://github.com/gao-gao-zai/FakeFlue/pull/16) 已合并 | 本地正式签名已校验；云端正式签名按用户决定暂不启用；R8 使用临时测试签名验收 |
| 旧设置页适配与默认控件无障碍 | [#17](https://github.com/gao-gao-zai/FakeFlue/pull/17)、[#18](https://github.com/gao-gao-zai/FakeFlue/pull/18) 已合并 | 安全区、滚动、右滑、滑条与开关操作已在模拟器验证；语音播报、旋钮与真实圆屏待验 |

## 已完成的自动验证

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 单元测试 | 总计 2229 项，0 失败，5 项缺少本地表盘样本跳过 | [合并前 CI](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44995141399)、[main CI](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44996335333) |
| Android 11 Debug 仪器测试 | 18 项，0 失败、0 跳过 | [合并前 CI](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44995141399) 的 `android-test-reports-api30` |
| R8 Release UI | 首轮 3 项，外部 force-stop 后恢复 1 项，全部通过 | 同一 CI 的 `release-emulator-test-reports-api30` |
| 本地正式签名包 | 签名证书、包名、版本和非空 R8 映射已校验，Release 不包含测试 Activity | 交付目录的 `verification.json`、`validation.json`，校验值见[发布指南](RELEASING.md) |

Debug 的 18 项中包括真实 Apple 控件 4 项、三种 UI 风格 DPI 旅程 1 项、旧设置页 5 项；其余为既有模式、显示与启动界面回归。具体范围见[旧设置页记录](validation/2026-11-22-legacy-settings.md)和[控件记录](validation/2026-11-22-apple-controls.md)。

主分支 push 按工作流条件执行单元测试和构建，模拟器在合并前 PR 执行。已核对最终 main 的完整文件树与验收提交一致。R8 模拟器包使用临时测试签名；正式交付包在本地使用已备份发布密钥签名。

GitHub Actions 产物保留期为 7 天，长期归档应保存交付 APK、配套 `mapping.txt`、校验与验证记录。

## 尚未闭环的项目

| 项目 | 当前结论 | 完成条件 |
| --- | --- | --- |
| 正式版本号 | 已定为 `versionName=3.0.1`、`versionCode=30001` | 对选定的发布提交重新构建正式签名包并记录哈希、证书和映射 |
| 旧版迁移与覆盖升级 | 用户决定：旧 FFlue 数据不迁移，不做 R37 历史迁移、R30 旧手势恢复和覆盖升级 | 不再作为发布条件。设备上装有旧 FFlue（同包名、不同签名）时需先卸载再安装 |
| 5 项样本测试 | 缺少表盘归档样本而跳过 | 在有对应样本的环境执行并保存结果；这些跳过不等同于旧数据兼容已通过 |
| 设备专项 | 按用户要求不做实机测试 | 后续有设备时验证 Root 导航、电话短信、锁屏闹钟、实体键、读屏、圆屏和 ROM 行为 |
| 性能与 Android 版本兼容 | 启动计时和缓存实现已具备；当前 CI 只有 Android 11 | 用同一手表对比启动与缓存，补 Android 9／10 等目标设备记录；[启动计时表](validation/2026-11-21-startup-timing.md)可直接填写 |
| 云端正式签名 | 工作流已实现，用户选择保留本地正式签名 | 未来启用时配置四项 Secrets 并单独验证远端正式签名流程；当前测试交付不依赖此项 |

当前范围为已合并的功能和测试交付，正式发布条件见[发布与签名指南](RELEASING.md)。
