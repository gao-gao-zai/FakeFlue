# 启动计时（R35）：真机记录表

状态：**未验收**。代码和单元测试已就绪，下面的表格要在 Android 11 Root 手表上实测填写。

## 计时口径

每次冷启动（进程里第一次创建桌面），日志里输出一行 `FflueStartup`。所有时间都从进程启动起算，单位毫秒；`-` 表示这次没有经过该点。

| 字段 | 含义 |
| --- | --- |
| `app_create` | `Application.onCreate` 开始 |
| `launcher_create` | 桌面 `onCreate` |
| `first_frame` | 桌面第一帧（此时可能还在加载页） |
| `face_selected` | 已确定显示哪块表盘 |
| `face_interactive` | 当前表盘首帧已画出、手势解锁，即“完整表盘可操作”；同时调用 `reportFullyDrawn()` |
| `lock_shown` | 伪装模式启用桌面密码时，密码页实际显示（它就是首个可操作页面） |
| `guard_active` | 伪装模式导航限制读回确认生效 |
| `guard=none` | 本次不需要导航限制（完整模式、通话中等） |
| `guard=pending` | 表盘可操作 30 秒后，导航限制仍没有结论（Root 不可用、连接超时等） |

不使用启动动画结束、空窗口、历史截图或 `am start` 的 TotalTime 代替 `face_interactive`。

## 采集方法

```bash
adb logcat -c && adb shell am force-stop com.gaozai.surface && adb shell input keyevent HOME && adb logcat -d -s FflueStartup
```

每个场景至少测 5 次，记中位数。需要看主线程耗时段时，用 Perfetto 抓 `fflue:` 开头的 Trace 段和 `fflue_startup_*` 计数器（Android 10 起才有计数器）。

## 本次的改动（与原版相比）

- 当前表盘只读它自己的目录就能显示，不再等所有已安装表盘扫描完。全量扫描照常在后台完成；读不到时仍然等全量扫描。
- 冷启动时 `onResume` 的强制刷新不再取消首次扫描、重扫一遍。
- 天气仓库的首次建立（读偏好、解析两遍缓存 JSON）从 `Application.onCreate` 的主线程挪到后台。
- 守护状态目录的创建挪出主线程。
- 侧屏、小组件页、控制中心的预热先等当前表盘首帧画好（最多等 3 秒），不再和表盘加载抢主线程。

## 记录表

基线：P0 没有留下启动计时，原版 Flue 需要和本版在同一台设备上一起测。

| 场景 | 原版 Flue `face_interactive` | 本版 `face_selected` | 本版 `face_interactive` | 本版 `lock_shown` | 本版 `guard_active` | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 完整模式，内置表盘，冷启动 | | | | — | none | |
| 完整模式，复杂 Watchmaker 表盘，冷启动 | | | | — | none | |
| 完整模式，装了 20 块以上表盘，冷启动 | | | | — | none | |
| 伪装模式，已确认 Root，冷启动 | — | | | | | |
| 伪装模式，启用桌面密码，冷启动 | — | | | | | |
| 进程被杀后按 HOME 回桌面 | | | | | | |
| 从其他应用按 HOME 回桌面（热） | | | | | | 不输出日志，凭观察记录有无等待 |

未达到“无明显等待”的场景，在备注里写明，保留为未完成。
