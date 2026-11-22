# FFlue 开发交接文档

日期：2026-11-22。原作者：上一位开发 agent（Claude）。P7 合并状态与 Release 后续任务已由 Codex 更新。

本文件已随 `p7-cache`（PR #14）合并进入 main。请和用户说中文。

## 1. 项目与资料

- **项目：** FFlue（公开仓库名 FakeFlue），是 Flue 安卓手表桌面的衍生版本，增加了“伪装桌面”模式。
- **需求文档：** `/srv/projects/fflue/docs/FFlue_重开发需求文档.md`，含 R01–R37、验收 A01–A15、§12 工程边界。
- **分阶段计划：** `/srv/projects/fflue/docs/FFlue_分阶段实施计划.md`，分 P0–P7。
- **原版改动总结：** `/srv/projects/fflue/docs/FFlue_原版改动总结.md`
- **P0 真机检查清单：** `/srv/projects/fflue/docs/FFlue_P0_真机检查清单.md`
- **代码：** 当前云环境为 `/workspace/FakeFlue`。上述需求资料与 `/srv/projects/fflue/src` 路径来自旧开发环境。
- **公开开发仓库：** `gao-gao-zai/FakeFlue`，这是 origin。
- **私有仓库：** `gao-gao-zai/fflue` 只作存档。不要往公开仓库推它的历史，也不要推任何 `archive/*` 分支。

## 2. 进度

| 阶段 | 状态 |
| --- | --- |
| P0–P6 | 已合并进 main，P6 是 #8–#11 |
| P7 | #12、#13、#14 已按顺序 squash 合并，合并完成时 main 为 `6635c421f6136bd59c4a8726b50b4653caaf5a82` |

P7 的三个叠加 PR 已完成合并，下面保留原分工供追溯。

| PR | 分支 | 内容 | 原基准 | 状态 |
| --- | --- | --- | --- | --- |
| [#12](https://github.com/gao-gao-zai/FakeFlue/pull/12) | `p7-startup` | R35 启动：当前表盘不等全量扫描就显示，启动分段计时 | main | 已合并 |
| [#13](https://github.com/gao-gao-zai/FakeFlue/pull/13) | `p7-data` | R37 备份导入与数据可靠性修复 | p7-startup | 已合并 |
| [#14](https://github.com/gao-gao-zai/FakeFlue/pull/14) | `p7-cache` | R36 Watchmaker 表盘持久缓存，以及本交接文档 | p7-data | 已合并 |

- P7 全量测试报告共 2215 项，0 失败，5 项因缺少本地样本跳过，包含本轮新增的 19 项回归测试。
- 三个 PR 合并前的 unit 和 API 30 模拟器 CI 均通过。
- main 合并后的 unit CI 通过。main 的 push 事件按工作流条件跳过模拟器任务。
- 签名 Release 工作流已实现；基于合并后应用代码的 R8 构建与新密钥签名校验已通过，验证范围见 §3 第 1 项。

## 3. 下一步要做的事

1. **签名 Release 流程已实现，用户本轮选择保留本地签名构建。**
   - 用户已选择新建发布密钥，并要求保存加密密钥库与签名 APK。新签名与旧版签名的覆盖升级兼容性需分别处理。
   - `.github/workflows/android-ci.yml` 已增加 Release 任务。手动触发时勾选 `build_release`，或推送 `v*` 标签，先等待 unit 通过。手动选项默认关闭，保留原有手动测试入口。
   - 从 Secrets 解码密钥库到 `$RUNNER_TEMP`，执行 `assembleRelease` 并保留 R8 混淆，再用 `apksigner verify --print-certs` 校验。
   - 缺少签名配置时失败。结束时清理临时密钥文件，产物包含签名 APK 与 `mapping.txt`。
   - GitHub 配置名为 `FLUE_RELEASE_KEYSTORE_B64`、`FLUE_SIGNING_STORE_PASSWORD`、`FLUE_SIGNING_KEY_ALIAS`、`FLUE_SIGNING_KEY_PASSWORD`。
   - `app/build.gradle.kts` 已支持 `FLUE_SIGNING_STORE_FILE` 等环境变量。密钥与口令通过用户授权的安全方式配置或交付。
   - **本地验证通过。** `assembleRelease` 已完成 R8 混淆。随后执行工作流中的实际签名步骤，APK 的包名为 `com.gaozai.surface`、版本为 `2.3.3 / 28`；`apksigner` 校验通过，证书 SHA-256 与新密钥库导出的证书一致，`mapping.txt` 非空，临时密钥库已删除。Actionlint 与 9 项脚本失败处理、清理检查均通过。GitHub Secrets 列表和公钥接口返回 403，用户已选择暂时保留本地签名，远端 Secrets 配置与正式签名任务端到端运行留待后续启用。PR #15 的单元与 API 30 模拟器检查已通过，并已 squash 合并为 `cb5e9265edc1fadbd272395c6fc447123875bb9c`。
2. **普通媒体保护与 Release 模拟器验收已实现。** 图片、视频、字体和背景采用新文件写入、真实设置提交、旧引用清理的顺序，普通替换与备份媒体阶段共用互斥；背景按字段和模式防抖，保留最新路径。新增 API 30 R8 Release UI 验收使用临时测试签名，实际发布身份保持本地密钥。独立 `release-smoke` 模块打包测试框架和 Kotlin 运行库，并共享两份不引用应用实现的冒烟测试源码，避免 Debug 测试包省略共享依赖后无法运行于 R8 应用。本地全量单元测试已完成，共 2229 项，0 失败，5 项因缺少本地样本跳过；Debug APK 与仪器测试 APK 均构建通过。Actions 的 Debug 与 R8 Release 模拟器结果以 [PR #16 的检查记录](https://github.com/gao-gao-zai/FakeFlue/pull/16/checks) 为准。Release 首轮验收 3 项，外部 force-stop 后冷启动恢复验收 1 项；调试套件继续独立执行。
3. **真机验收（用户说过“不要实际测”，只能整理出来交给用户）：**
   - 启动计时：按 `docs/validation/2026-11-21-startup-timing.md` 填表，需要和原版 Flue 在同一台手表上实测。
   - P5：Root 导航守护。
   - P6：来电全屏、短信收发（A12，需要 SIM 卡）、闹钟锁屏全屏、实体键快速进入。
   - R36：缓存实际提速多少。
   - R37：Android 9–11 上的表现。
4. **一直缺的输入：**
   - 用户确认暂无旧版备份或 APK 样本。R37 旧数据迁移和 R30 旧手势恢复的历史格式兼容验证等待样本。
   - 旧版 versionCode 仍待确认，当前保持占位值 28；用户已选择新的发布签名身份。
5. **模式恢复策略已由用户确认。** 无法读取桌面模式时回到完整模式。本轮已修复错类型模式值的恢复，并通过 5 项真实 SharedPreferences 回归。
6. **旧环境工作树已核实。** 旧交接中的两个 `.claude/worktrees/agent-*` 路径在当前云环境均不存在，也无对应 Git worktree 注册。当前保留主工作树与用户创建的三个 P7 工作树。

7. **旧设置页统一适配（R23、R25）。** 公共旧设置列表和图片、视频表盘的独立设置页已接入 `SettingsPageFrame`，采用已保存的安全区视口与共用右滑返回。保留旧列表的圆屏缩放、搜索定位、滚动记忆和旋钮处理；编辑页退出沿用原提交回调。新增 5 项 Debug 实际界面检查，R8 备份流程也改为真实右滑。范围、测试场景与结果入口见 [本轮验收记录](docs/validation/2026-11-22-legacy-settings.md)。

8. **默认 Apple 控件读屏状态与标准操作。** 为交互式滑条和开关补充标准语义，滑条复用原拖动刻度规则并处理禁用、重复与无效请求，开关提供角色、状态与启用时的点击。旧设置页的滑条测试恢复默认风格；新增真实控件语义与指针测试，以及三种风格的 DPI 实际旅程；R8 验收也覆盖标准调节动作。测试与边界见 [本轮记录](docs/validation/2026-11-22-apple-controls.md)。本轮叠在 PR #17 上，未改变既有合并规则。

## 4. 构建与测试

```bash
cd /workspace/FakeFlue
source /workspace/.fakeflue/android-env.sh
/workspace/.fakeflue/gradle-8.13/bin/gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --console=plain
```

- 一次构建要 5–20 分钟。后台执行时保留原命令退出码，完成后核对本次生成的测试报告。
- 不要同时在同一个目录跑两个 Gradle。
- 测试结果在 `app/build/test-results/testDebugUnitTest/*.xml`。
- 技术栈：
  - Kotlin + Compose，AGP 8.13.2，JDK 21，Robolectric 4.16.1。
  - compileSdk 36，minSdk 27。不要引入 API 28 以上的调用而不加判断，例如 `InputStream.readAllBytes`、`transferTo`、`List.of`、`Path.of`。
- 应用 ID 是 `com.gaozai.surface`，命名空间是 `com.flue.launcher`。
- Robolectric 测试写法：`@RunWith(RobolectricTestRunner::class) @Config(sdk=[30])`，context 用 `RuntimeEnvironment.getApplication()`。
- **`FlueCardShapeTokenTest` 规则：** 卡片尺寸不能写死 `RoundedCornerShape(n.dp)`，要用 `LauncherTheme.style.compactShape` / `cardShape` 或 `CircleShape`。
- **CRLF 文件：** 很多原版文件是 CRLF 换行，例如 `LauncherViewModel.kt`、`LauncherActivity.kt`、`SettingsActivity.kt`、`FlueApplication.kt`、AndroidManifest、strings XML 和部分 `java/` 下的文件。改之前先用 `file <路径>` 看一下。CRLF 文件用下面的脚本改：

```python
# 保存为 /tmp/fflue-edit/edit.py（/tmp 可能已被清空）
import sys
def edit(p, pairs):
    s=open(p,newline='').read(); crlf='\r\n' in s; s=s.replace('\r\n','\n')
    for a,b in pairs:
        n=s.count(a)
        if n!=1: sys.exit(f"{p}: anchor count {n}: {a[:80]!r}")
        s=s.replace(a,b)
    if crlf: s=s.replace('\n','\r\n')
    open(p,'w',newline='').write(s)
```

- 新代码放在 `com.flue.launcher.surface.*` 下，原版大文件只加最小接入点。

## 5. Git、PR、合并流程

- **提交信息：** 用中文，标题形如 `P7：……（R36）`，最后一行是 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。不是 Claude 的话，按你自己的署名规则写。
- **PR 描述：** 用中文，写明对应哪条需求、改了哪些原版文件、未验证的项目。
- **提交前：**
  - 跑 `/tmp/gitleaks-dl/gitleaks git --staged .` 查泄露。工具不在的话重新下载 gitleaks。
  - 确认提交里没有 `.claude/` 路径。
- **网络：** 经常出现 TLS 或 DNS 错误。推送最多重试 5 次，然后用 `gh api repos/gao-gao-zai/FakeFlue/branches/<分支> --jq .commit.sha` 核对远端确实到了。本机的 127.0.0.1:1080 代理没开。
- **改 PR 基准：** `gh pr edit --base` 会失败（Projects classic 已弃用），改用 `gh api -X PATCH repos/gao-gao-zai/FakeFlue/pulls/N -f base=main`。
- **合并：** 用 squash，`gh api -X PUT repos/gao-gao-zai/FakeFlue/pulls/N/merge -f merge_method=squash -f commit_title="标题 (#N)" -f sha=<头提交>`。
- **P7 合并已完成。** #12 → #13 → #14 的叠加分支合并步骤属于历史记录，当前 main 已包含全部 P7 改动。
- **以后处理叠加 PR 时：**
  1. 确认用户授权合并，按依赖顺序先合并最底层 PR。
  2. 确认 fetch 成功后，用 `git rebase --onto origin/main <原父提交> <分支>` 更新下一层分支。
  3. 核对变基前后 tree 一致，并用 `git merge-base --is-ancestor origin/main <分支>` 确认最新 main 已是分支祖先。
  4. 用 `--force-with-lease` 推送，调整 PR 基准并完成所需 CI 验证，再执行授权的 squash 合并。

## 6. 用户的固定要求

- 说中文。
- **只在用户说“合并”时才合并。** 提交和推送只在用户要求的流程里做。
- 签名密钥库按用户确认的方式加密备份，恢复口令单独保存。代码仓库、构建日志和交接记录使用公开证书信息与验证结论。
- 代理只按单条命令设置，不改全局的 git、npm 或系统代理配置。
- 不要杀其他用户（ctyun）的进程。
- 不做真机测试。CI 模拟器只有 Android 11。
- 签名身份确认后再落实 Release 签名配置，versionCode 仍为占位值 28。
- 用户本轮明确要求完成 Actions 模拟器验收，可跟踪本轮验证至结束；持续后台定时监控另行约定。

## 7. P7 各块的要点（细节见各 PR 描述）

- **R35（#12）：**
  - 新增 `surface/startup/StartupTimeline.kt`。每次冷启动输出一行 `FflueStartup` 日志，从进程启动起算，三项分开计时：表盘可操作、密码页显示、导航限制生效。
  - `WatchmakerWatchFaceStorage.describeInstalled` 和 `JbWatchFaceStorage.describeInstalled` 只读一个表盘目录。
  - 新增 `LauncherViewModel.quickResolveSelectedWatchFace`。
  - 冷启动时不再把表盘扫描跑两遍；天气仓库在后台建立；侧页预热要等表盘首帧画出来。
- **R37（#13）：**
  - 新增 `backup/BackupArchive.kt`、`BackupPreferenceSchema.kt`、`FlueBackupImportResult.kt`。
  - 新增 `data/StagedFiles.kt`、`DirectorySwap.kt`、`LauncherSettingsDataStore.kt`，提供损坏保护。
  - 新字符串在 `data_reliability_strings.xml`。
  - **新增设置键时**，`BackupPreferenceSchema` 要同步。有反射测试守着，漏了测试会失败。
- **R36（#14）：**
  - 新增 `watchface/standard/prepared/`，包括编解码、磁盘缓存和 `WatchmakerPreparedCache.invalidate`。
  - 解析器或表达式编译器的行为一改，就要把 `WatchPreparedCodec.ENGINE_VERSION` 加 1。
  - Lua 缓存产物只经 `LoadState.undump` 的内部路径还原，不开放从表盘包导入 Lua 二进制。
