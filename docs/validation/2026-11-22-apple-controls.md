# Apple 风格控件的读屏状态与调节动作

## 改动

默认 Apple 风格的自绘滑条原先不提供标准进度节点，开关也没有独立的角色、开关状态和点击动作。本轮为实际交互控件补充 Compose 标准语义：

- 滑条暴露当前值、最小值、最大值和中间刻度数，可通过 Android `ACTION_SET_PROGRESS` 调节。
- 调节请求先夹取到范围，再复用触摸拖动的刻度吸附规则。未变化的请求和非有限数值不触发回调。
- 有效调节调用一次变更及一次提交回调；禁用时拒绝调节及触摸拖动。
- 开关暴露 Switch 角色、当前状态及启用时的点击动作，禁用时没有点击动作。
- 触摸手势和外观保留原实现，纯展示开关不新增独立操作节点。

## 实际控件测试

`AppleControlsAccessibilityTest` 使用 Compose 官方测试规则渲染真实 Apple 控件，验证四项场景：

1. 当前进度与范围、越界请求夹取、重复请求，以及 NaN 和无穷值拒收。
2. 标准调节与真实触摸拖动都落到同一组刻度，且一次操作只提交一次。
3. 禁用滑条拒绝标准调节和触摸拖动，重新启用后正常调节。
4. 开关角色与状态、标准点击与真实点按各切换一次，不重复触发父行，禁用后不可切换。

测试依赖沿用项目 Compose BOM，只加入 AndroidTest 的 UI 测试库及 Debug 测试 Activity 清单，Release 不包含测试 Activity。

## 应用旅程与 Release

- `SliderAccessibilityJourneyTest` 在三种 UI 风格的实际 DPI 页面上执行 Android 标准调节动作和真实拖动，检查范围和当前值，确认修改只作用于草稿，右滑返回不保存。节点定位使用非空的可见边界，兼容 MIUIX 的 24dp 轨道，不套用 Apple/M3 的控件高度。
- PR #17 的旧设置滑条回归恢复默认 Apple 风格，继续验证实际数值变化与不误返回。
- `ReleaseUiSmokeTest` 在 R8 APK 的 DPI 页面直接查找标准进度节点并执行调节，随后取消并验证原值，继续执行保存和外部强制停止后的恢复检查。

本地全量单元测试 2229 项、0 失败、0 错误，5 项因缺少本地样本跳过。Debug APK、仪器测试 APK 和独立 Release 验收 APK 均构建通过。

本轮以 PR #17 为基准，模拟器套件包含两轮修改的组合。结果以本分支的 [Actions 记录](https://github.com/gao-gao-zai/FakeFlue/actions/workflows/android-ci.yml?query=branch%3Ayzp%2Fapple-controls-accessibility) 和 PR 验证说明为准。Debug 预计执行 18 项，Release 首轮 3 项、冷启动 1 项。

按用户约定不做真机验收。读屏服务的实际语音播报、实体旋钮和各手表 ROM 的行为仍需设备验证。
