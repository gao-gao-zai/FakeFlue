# FFlue 开发交接文档

日期：2026-11-22。作者：上一位开发 agent（Claude）。

本文件提交在 `p7-cache` 分支（PR #14）上，合并 P7 后会进入 main。请和用户说中文。

## 1. 项目与资料

- **项目：** FFlue（公开仓库名 FakeFlue），是 Flue 安卓手表桌面的衍生版本，增加了“伪装桌面”模式。
- **需求文档：** `/srv/projects/fflue/docs/FFlue_重开发需求文档.md`，含 R01–R37、验收 A01–A15、§12 工程边界。
- **分阶段计划：** `/srv/projects/fflue/docs/FFlue_分阶段实施计划.md`，分 P0–P7。
- **原版改动总结：** `/srv/projects/fflue/docs/FFlue_原版改动总结.md`
- **P0 真机检查清单：** `/srv/projects/fflue/docs/FFlue_P0_真机检查清单.md`
- **代码：** `/srv/projects/fflue/src`
- **公开开发仓库：** `gao-gao-zai/FakeFlue`，这是 origin。
- **私有仓库：** `gao-gao-zai/fflue` 只作存档。不要往公开仓库推它的历史，也不要推任何 `archive/*` 分支。

## 2. 进度

| 阶段 | 状态 |
| --- | --- |
| P0–P6 | 已合并进 main。P6 是 #8–#11，main 在 `5ba6959` |
| P7 | 三个 PR 已开，**还没合并**，在等用户说“合并” |

P7 的三个 PR 是依次叠起来的，**必须按这个顺序合并**：

| PR | 分支 | 内容 | 基准 | 头提交 |
| --- | --- | --- | --- | --- |
| [#12](https://github.com/gao-gao-zai/FakeFlue/pull/12) | `p7-startup` | R35 启动：当前表盘不等全量扫描就显示，启动分段计时 | main | `a81fd5f` |
| [#13](https://github.com/gao-gao-zai/FakeFlue/pull/13) | `p7-data` | R37 备份导入与数据可靠性修复 | p7-startup | `d48a458` |
| [#14](https://github.com/gao-gao-zai/FakeFlue/pull/14) | `p7-cache` | R36 Watchmaker 表盘持久缓存，以及本交接文档 | p7-data | `900d766` + 交接文档提交 |

- 三个 PR 合在一起跑过全量构建：2196 个测试，0 失败，5 个跳过。
- #12 的 CI 已通过，包括单元测试和 API 30 模拟器测试。
- #13 和 #14 是叠在别的分支上的，CI 只在 PR 以 main 为基准时才跑，所以它们目前没有 CI 结果。
- 本地 `/srv/projects/fflue/src` 当前在 `p7-cache` 分支，工作区干净。
- `p7-cache` 在 `900d766` 之上多了一个只加本文件的提交，不影响代码和构建结果。

## 3. 下一步要做的事

1. **合并 P7。只在用户明确说“合并”后才做。** 流程见 §5。
2. **签名 Release 任务（需要用户同意）。** 上次修改 `.github/workflows/android-ci.yml` 时被自动权限检查拦下，文件没改。用户还没答复要不要加。
   - 设想的方案：
     - 新增一个 `release` 任务，只在 `workflow_dispatch` 或推送 `v*` 标签时运行，需要先跑完 `unit`。
     - 从 Secrets 解码密钥库到 `$RUNNER_TEMP`，缺任何一项 Secret 就直接失败，不产出未签名包。
     - 执行 `assembleRelease`，用 `apksigner verify --print-certs` 校验签名。
     - 构建结束后无论成败都删除密钥文件，然后上传 APK 和 `mapping.txt`。
   - 用到的四个 Secrets：`FLUE_RELEASE_KEYSTORE_B64`、`FLUE_SIGNING_STORE_PASSWORD`、`FLUE_SIGNING_KEY_ALIAS`、`FLUE_SIGNING_KEY_PASSWORD`。
   - `app/build.gradle.kts` 已经支持从环境变量读取 `FLUE_SIGNING_STORE_FILE` 等签名配置，不用改。
   - Secrets 由用户自己在 GitHub 填，**密钥不要进聊天**。
   - 本地未签名的 `assembleRelease`（开 R8 混淆）在 P6 合并后构建通过过。
3. **真机验收（用户说过“不要实际测”，只能整理出来交给用户）：**
   - 启动计时：按 `docs/validation/2026-11-21-startup-timing.md` 填表，需要和原版 Flue 在同一台手表上实测。
   - P5：Root 导航守护。
   - P6：来电全屏、短信收发（A12，需要 SIM 卡）、闹钟锁屏全屏、实体键快速进入。
   - R36：缓存实际提速多少。
   - R37：Android 9–11 上的表现。
4. **一直缺的输入：**
   - 旧 FFlue 的数据格式：R37 的数据迁移和 R30 的旧手势恢复都要用。
   - 旧版签名密钥和 versionCode：versionCode 现在是占位值 28。
5. **待用户决定：**
   - 存储的模式读不出时，现在回到完整模式，属于 fail-open。
   - 表盘媒体替换时，旧文件在新路径写进设置之前就删了，这处保留了原调用约定。
6. **清理：** 两个子代理的临时工作区还被 Claude 进程锁着，P7 合并后再删：
   - `.claude/worktrees/agent-a2c96c506956c6824`
   - `.claude/worktrees/agent-aaf7d10596a313351`

   删除命令：`git worktree remove -f -f <路径>`，然后 `git branch -D worktree-agent-...`。
   这两个目录已经在 `.git/info/exclude` 里。

## 4. 构建与测试

```bash
systemd-run --scope --quiet -p MemoryMax=9G -p MemorySwapMax=0 /opt/fflue-toolchain/run-gradle.sh /srv/projects/fflue/src testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --console=plain > /tmp/build.log 2>&1; echo EXIT=$? >> /tmp/build.log
```

- 一次构建要 5–20 分钟，放后台跑，用 `until grep -q "^EXIT=" /tmp/build.log; do sleep 5; done` 等结果。
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
- **叠起来的 PR 怎么合：**
  1. 先 squash 合并 #12。
  2. fetch，然后把下一个分支从它原来的父提交上摘下来接到 main：`git rebase --onto origin/main <原父提交> <分支>`。
  3. 核对两点：新 HEAD 的 tree 和变基前完全一致；HEAD 的父提交等于 origin/main。
  4. 用 `--force-with-lease` 推送，把 PR 基准改成 main，再 squash 合并。
  5. 对下一个 PR 重复第 2–4 步。
- **一定要确认 fetch 成功：** 有一次 fetch 失败、本地 origin/main 是旧的，结果变基出现了冲突。
- 下面的脚本会在 fetch 失败时直接退出：

```bash
#!/bin/bash
# 用法: stackstep.sh <分支> <原父提交> <PR号> <标题>
set -e
b=$1; parent=$2; pr=$3; title=$4
cd /srv/projects/fflue/src
for i in 1 2 3 4 5; do timeout 90 git fetch -q origin && break; sleep 10; [ $i = 5 ] && { echo FETCH FAILED; exit 1; }; done
old=$(git rev-parse origin/$b)
git checkout -q -B $b origin/$b
git rebase -q --onto origin/main $parent $b
[ "$(git rev-parse HEAD^{tree})" = "$(git rev-parse $old^{tree})" ] || { echo "TREE MISMATCH"; exit 1; }
[ "$(git rev-parse HEAD^)" = "$(git rev-parse origin/main)" ] || { echo "PARENT MISMATCH"; exit 1; }
git show --name-only HEAD | grep -q '\.claude' && { echo "CLAUDE PATH"; exit 1; }
new=$(git rev-parse HEAD)
for i in 1 2 3 4 5; do git push -q --force-with-lease=$b:$old origin $b 2>&1 | tail -1; [ "$(gh api repos/gao-gao-zai/FakeFlue/branches/$b --jq .commit.sha)" = "$new" ] && break; sleep 10; done
[ "$(gh api repos/gao-gao-zai/FakeFlue/branches/$b --jq .commit.sha)" = "$new" ] || { echo "PUSH FAILED"; exit 1; }
gh api -X PATCH repos/gao-gao-zai/FakeFlue/pulls/$pr -f base=main --jq '.base.ref'
sleep 5
gh api -X PUT repos/gao-gao-zai/FakeFlue/pulls/$pr/merge -f merge_method=squash -f commit_title="$title (#$pr)" -f sha=$new --jq '.merged, .sha'
```

P7 的合并顺序：

1. 合并 #12：`gh api -X PUT repos/gao-gao-zai/FakeFlue/pulls/12/merge -f merge_method=squash -f commit_title="P7：启动不等全量表盘扫描、启动分段计时（R35） (#12)" -f sha=a81fd5f…`。先用 `gh api repos/gao-gao-zai/FakeFlue/pulls/12 --jq .head.sha` 取完整 sha。
2. `stackstep.sh p7-data a81fd5f 13 "P7：备份导入与数据可靠性修复（R37）"`
3. `stackstep.sh p7-cache d48a458 14 "P7：Watchmaker 表盘持久准备缓存（R36）"`
4. 本地切到 main，`git merge --ff-only origin/main`。核对 main 的 tree 和 `p7-cache` 合并前头提交的 tree 一致，一致就说明合并后的内容正是构建通过的那一份（外加本文件）。

## 6. 用户的固定要求

- 说中文。
- **只在用户说“合并”时才合并。** 提交和推送只在用户要求的流程里做。
- 签名密钥不能出现在聊天里，用户会给服务器上的文件路径。不要往仓库、附件或日志里放任何密钥、口令或一次性邀请链接。
- 代理只按单条命令设置，不改全局的 git、npm 或系统代理配置。
- 不要杀其他用户（ctyun）的进程。
- 不做真机测试。CI 模拟器只有 Android 11。
- 签名配置和 versionCode 保持占位值。
- 不要自己定时轮询 CI。

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
