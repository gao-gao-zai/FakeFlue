# FFlue 开发交接文档

更新日期：2026-11-29。请和用户说中文。

应用验收基线为 `f20d3430c1169644906cd8fcc2987b167026383b`。P0–P7 以及 #15–#18 的后续补漏均已合并。完整状态见[开发与验收状态](docs/PROJECT_STATUS.md)，交付身份与发布步骤见[发布与签名指南](docs/RELEASING.md)。

## 1. 项目与资料

- FFlue（公开仓库名 FakeFlue）是 Flue 2.3.3 的 Android 手表桌面派生版，增加伪装桌面，以 GPL-3.0 发布。
- 当前代码目录为 `/workspace/FakeFlue`，公开 origin 为 `gao-gao-zai/FakeFlue`。
- 私有仓库 `gao-gao-zai/fflue` 仅作存档，不把其历史或 `archive/*` 分支推往公开仓库。
- 原 R01–R37 需求、P0–P7 计划、原版改动总结与 P0 真机清单位于旧环境 `/srv/projects/fflue/docs/FFlue_*.md`。这些路径不是当前工作区文件；旧归档“表面”需求不能替代现行 FFlue 需求。当前状态表按已合并实现与实际验证证据整理。

## 2. 已完成与验证证据

| 内容 | 已合并记录 |
| --- | --- |
| P0–P6：身份、模式、显示、公开界面、隐藏管理、教程、系统能力、工具与通信 | 首次公开基线及 #1–#11 |
| P7：R35 启动、R37 备份与可靠性、R36 准备缓存 | [#12](https://github.com/gao-gao-zai/FakeFlue/pull/12)、[#13](https://github.com/gao-gao-zai/FakeFlue/pull/13)、[#14](https://github.com/gao-gao-zai/FakeFlue/pull/14) |
| 本地正式签名与工作流 | [#15](https://github.com/gao-gao-zai/FakeFlue/pull/15)，合并提交 `cb5e926` |
| 媒体替换保护、模式坏值恢复与 R8 模拟器验收 | [#16](https://github.com/gao-gao-zai/FakeFlue/pull/16)，合并提交 `7ffb91e` |
| 旧设置页安全区与右滑返回 | [#17](https://github.com/gao-gao-zai/FakeFlue/pull/17)，合并提交 `b4049aa` |
| 默认 Apple 滑条与开关的读屏状态和标准操作 | [#18](https://github.com/gao-gao-zai/FakeFlue/pull/18)，合并提交 `f20d343` |

- 最新单元测试总计 2229 项，0 失败，5 项因缺少本地表盘样本跳过。P7 初次交付的 2215 项是历史统计。
- [最终合并前 CI](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44995141399)：Android 11 Debug 18 项，0 失败、0 跳过；R8 Release 首轮 3 项、外部强制停止后恢复 1 项，全部通过。
- [合并后 main CI](https://github.com/gao-gao-zai/FakeFlue/actions/runs/44996335333) 已通过，完整文件树与验收提交一致。main push 按条件不重复执行模拟器。
- 本地正式签名包的生产代码已核对与应用基线一致，证书与用户已备份密钥一致；包名 `com.gaozai.surface`，版本 `3.0.1 / 30001`（此前测试交付为 `2.3.3 / 28`），R8 映射非空。正式 Release 不包含 Debug 测试 Activity。
- 正式签名保留本地。CI 模拟器使用临时测试签名；云端正式签名任务已实现，Secrets 管理接口此前返回 403，用户已决定暂不配置，不要反复要求配置。
- 普通媒体替换按“写新文件 → 提交设置 → 清理旧引用”执行，与备份媒体共用互斥。桌面模式损坏或无法读取时回到完整模式，这是用户确认的策略。
- 旧设置页与独立图片／视频设置页已采用已保存安全区和共用右滑；保留滚动、搜索、圆屏缩放和旋钮接入。默认 Apple 控件及三种风格 DPI 操作有实际 UI 回归。

## 3. 保留的待验与外部输入

1. 用户明确不做实机测试。Root 导航、真实电话短信、锁屏闹钟、实体键、读屏语音、真实圆屏与 ROM 边距均未完成设备验收；启动和缓存提速没有同机对比结论。[启动计时表](docs/validation/2026-11-21-startup-timing.md)保留待填。
2. 用户决定旧 FFlue 数据不迁移：R37 历史迁移、R30 旧手势恢复和覆盖升级不再做，也不作为发布条件。5 项缺少表盘样本的测试仍不能标成通过。
3. 正式版本号已定为 `3.0.1 / versionCode 30001`（之后按 major*10000 + minor*100 + patch 递增）。3.0.1 正式签名包已于 2026-11-29 从 `87b0989` 构建并校验，哈希、证书和映射记录在[发布指南](docs/RELEASING.md)；`v3.0.1` 标签仍须用户授权后再打。
4. 当前保留主工作树和用户创建的三个 P7 工作树。旧交接中的 `.claude/worktrees/agent-*` 不存在，勿重建或把它们当作待处理改动。清理现有分支／工作树须另按用户授权处理。

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
- 签名身份已确定并完成本地 Release 签名校验，沿用已备份密钥；正式版本号为 `3.0.1 / 30001`，旧 FFlue 数据不迁移。
- 用户允许使用 GitHub Actions 模拟器。需要验证时跟踪当前运行至结束，长期后台定时监控另行约定；纯文档改动运行匹配的文档检查即可。

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
