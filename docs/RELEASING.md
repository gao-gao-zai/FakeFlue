# FFlue 发布与签名指南

更新日期：2026-11-28。正式版本号已定为 `3.0.1 / versionCode 30001`；旧 FFlue 数据不迁移、不要求覆盖升级。

## 上一次已交付 APK 的身份（2.3.3 测试交付）

| 项目 | 值 |
| --- | --- |
| APK 文件名 | `FFlue-2.3.3-vc28-release.apk` |
| applicationId | `com.gaozai.surface` |
| versionName / versionCode | `2.3.3 / 28` |
| 应用验收基线 | [`f20d3430c1169644906cd8fcc2987b167026383b`](https://github.com/gao-gao-zai/FakeFlue/commit/f20d3430c1169644906cd8fcc2987b167026383b) |
| APK 实际构建源提交 | `720fc088c8a56ca2cf1849fe45e424e72f648d8d`，生产代码已核对与验收基线一致 |
| APK SHA-256 | `e4fa6e99b51e6744ba624440d14917ef1b42c26893c8a763cea53028f62e8faf` |
| 发布证书 SHA-256 | `16bee1eab62001b745a02eba685d663f91f31860f235347531b2010c01482ab3` |

本次沿用用户已备份的发布密钥。本地交付目录包含 APK、配套 `mapping.txt`、`verification.json`、`validation.json`、模拟器记录与交付说明，APK 不随源码提交。

构建源提交记录 APK 的实际来源；后续变基、squash 合并和文档整理通过生产代码一致性核对关联到应用验收基线，不改写 APK 内的 VCS 元数据。表中的 APK 哈希仅对应这次已交付文件，重新构建后应重新计算。

## 本地正式签名构建

先安装 JDK 21、Android SDK 36 与 Build Tools 35.0.0，并设置 SDK 路径。使用已备份密钥，在受保护的本地配置或当前构建会话中提供四项配置：

| 名称 | 用途 |
| --- | --- |
| `FLUE_SIGNING_STORE_FILE` | 本地密钥库文件路径 |
| `FLUE_SIGNING_STORE_PASSWORD` | 密钥库口令 |
| `FLUE_SIGNING_KEY_ALIAS` | 签名条目别名 |
| `FLUE_SIGNING_KEY_PASSWORD` | 条目口令 |

[构建配置](../app/build.gradle.kts)按环境变量、Gradle 属性、根目录 `keystore.properties` 的顺序读取配置。四项完整时启用 Release 签名；普通 `assembleRelease` 在签名配置不全时可能输出未签名产物，因此归档前必须验证实际 APK。

口令通过受保护的配置传入，避免写入命令行参数或构建日志。密钥库及恢复材料保存在源码仓库之外，按已有加密备份流程保管。

```bash
bash gradlew --no-daemon --max-workers=2 :app:assembleRelease --console=plain
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
test -s app/build/outputs/mapping/release/mapping.txt
sha256sum app/build/outputs/apk/release/app-release.apk
```

Windows 使用 `gradlew.bat` 和 SDK 下的 `apksigner.bat`。校验证书必须与保存的发布身份一致；APK 和该次构建的 `mapping.txt` 一起归档。正式构建保留默认 R8 和资源压缩，不传入关闭混淆的测试参数。

## GitHub Actions

当前选择本地正式签名。CI 的 R8 模拟器任务使用临时测试签名，仅用于运行验收。

未来启用云端正式签名时，在仓库 Actions Secrets 配置：

- `FLUE_RELEASE_KEYSTORE_B64`：密钥库文件的 Base64 编码。
- `FLUE_SIGNING_STORE_PASSWORD`：密钥库口令。
- `FLUE_SIGNING_KEY_ALIAS`：签名条目别名。
- `FLUE_SIGNING_KEY_PASSWORD`：条目口令。

Base64 是文件编码，独立于本地加密备份。配置后手动勾选 `build_release`，或按[Git 工作流](GIT_WORKFLOW.md)推送 `v*` 标签。正式签名任务等待 unit 成功，缺少 Secrets 时失败，结束时清理临时密钥库；成功后上传 `Flue-release`，包含 APK 和 R8 映射。

工作流实现见 [android-ci.yml](../.github/workflows/android-ci.yml)。云端正式身份尚未完成端到端验收；本地签名验证和模拟器临时签名验证的结果分别记录。

## 正式发布前

- [x] P0–P7 与后续补漏合并，应用基线为 `f20d343`。
- [x] 单元测试与 Android 11 Debug／R8 Release 验收通过，5 项缺失样本测试单独记录。
- [x] 本地正式签名校验完成，密钥已有备份，APK 与配套映射已交付。
- [x] 确定 FFlue 正式版本：`3.0.1 / versionCode 30001`。
- [x] 旧版兼容：用户决定旧 FFlue 数据不迁移，不做历史备份、旧手势恢复和覆盖升级验证。设备上的旧 FFlue（同包名、不同签名）需先卸载。
- [ ] 完成或明确列出本次发行未验的设备项目。用户当前选择不做实机，Root／SIM／ROM／实体键和实际性能仍保留待验状态。
- [ ] 对最终选定的发布提交构建、校验签名并重新记录 APK 哈希、版本、证书、映射和测试范围，再创建正式标签或 Release。

本轮文档收尾沿用现有应用验收与签名包。版本号、历史兼容和设备验收的具体状态见[开发与验收状态](PROJECT_STATUS.md)。
