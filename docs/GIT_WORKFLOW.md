# FFlue Git 工作流

FFlue 只保留一个长期分支：`main`。可发布、可回滚的版本使用 tag 保存，日常开发使用短期主题分支。

## 分支约定

- `main`：唯一长期分支，始终代表最新的已整合代码。
- `yzp/<topic>`：个人主题分支，例如 `yzp/watch-store-filter`。
- 一个分支只处理一个明确主题；不要用分支长期保存版本快照。
- 开始开发前同步 `main`，开发期间避免长期偏离 `main`。
- 合并后可清理主题分支；涉及删除分支、tag、stash 或 worktree 时，按下方“清理检查”另行确认。

```powershell
git switch main
git pull --ff-only
git switch -c yzp/<topic>
```

## 合并约定

- Pull Request 按用户授权使用 squash 合并；用户未说“合并”时，提交推送并保留待合并状态。
- 本地同步 `main` 使用 fast-forward；禁止对共享的 `main` 强制推送。
- 叠加 PR 先合并父 PR，再变基到新 main，核对变基前后文件树与最新祖先关系；需要重写主题分支时用 `--force-with-lease`，更新基准并完成检查。
- 合并前运行与改动相匹配的检查。保留 CRLF 的原文件使用 `git -c core.whitespace=cr-at-eol diff --check`，避免把行尾回车误报为空白错误。
- 提交信息使用简体中文，说明实际完成的行为。
- 暂存时显式指定已审查路径，不使用会捎带本地文件的宽泛暂存命令。

## 版本约定

- 正式版本使用 `v<major>.<minor>.<patch>`，例如 `v2.1.0`。
- 测试版本使用 `v<major>.<minor>.<patch>-beta`，例如 `v1.5.0-beta`。
- 不再继续开发、但仍需保留的实验分支使用 `archive/<topic>` tag。
- 正式 tag 只能指向已完成 Release 构建和 APK 签名验证的提交。
- 当前版本为 `3.0.1 / versionCode 30001`，对应正式标签 `v3.0.1`；只有完成发布检查后才能打标签，见[发布与签名指南](RELEASING.md)。推送 `v*` 标签会触发云端签名任务，未配置 Secrets 时该任务会失败。
- tag 推送到 GitHub 后，才能删除它替代的版本分支或归档分支。

```powershell
git tag -a v2.1.0 <commit> -m "发布 FFlue 2.1.0"
git push origin v2.1.0
```

## 清理检查

删除分支前必须确认提交已经进入 `main`，或已有指向该提交的归档 tag：

```powershell
git branch --merged main
git branch --no-merged main
git show-ref --tags
```

存在未提交修改或 stash 时，清理分支不得改写、应用或丢弃这些内容。涉及删除分支、tag、stash 或 worktree 的操作必须先记录目标 SHA，并单独确认。
