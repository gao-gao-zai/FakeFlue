package com.flue.launcher.surface.system

import com.flue.launcher.surface.mode.SurfaceMode

/**
 * 导航限制：屏蔽系统下拉（通知栏、快捷设置）和系统多任务，Home、Back 始终保留（R29）。
 * 不再关闭返回手势区域或禁用三键。
 */
data class NavRestriction(val blockShade: Boolean, val blockRecents: Boolean) {
    val active: Boolean get() = blockShade || blockRecents

    companion object {
        val NONE = NavRestriction(blockShade = false, blockRecents = false)
        val DISGUISE = NavRestriction(blockShade = true, blockRecents = true)
    }
}

/** 页面和系统上报的事实；决策只看这些，页面不各自维护全局限制状态（§12-2）。 */
data class NavFacts(
    val mode: SurfaceMode,
    /** 通话或响铃；无法确认时按通话处理，不冒险。 */
    val inCall: Boolean,
    /** 四类设备系统页的临时放行（R27）。 */
    val handoff: Boolean,
)

/**
 * R29 决策表，唯一的决策入口。
 *
 * | 场景 | 下拉、多任务 |
 * | --- | --- |
 * | 伪装桌面、自有设置和工具 | 限制 |
 * | 伪装中的普通第三方应用、输入法、普通权限窗口 | 限制 |
 * | 临时完整设置 | 限制 |
 * | 通话、必要系统设置交接 | 临时放行，结束后恢复 |
 * | 完整桌面、外部桌面 | 不限制 |
 *
 * 前三行都在伪装模式里，前台是谁不影响结果，所以不需要跟踪第三方应用是否在前台。
 */
object NavPolicy {
    fun decide(facts: NavFacts): NavRestriction = when {
        facts.mode != SurfaceMode.DISGUISE -> NavRestriction.NONE
        facts.inCall || facts.handoff -> NavRestriction.NONE
        else -> NavRestriction.DISGUISE
    }

    /** 伪装自有页面隐藏顶部状态栏；不改第三方应用和系统页（R29）。 */
    fun hideOwnStatusBar(mode: SurfaceMode): Boolean = mode == SurfaceMode.DISGUISE
}

/** `IStatusBarService.disable/disable2` 的隐藏常量（android.app.StatusBarManager）。 */
object StatusBarFlags {
    const val DISABLE_EXPAND = 0x00010000
    const val DISABLE_RECENTS = 0x01000000
    const val DISABLE2_QUICK_SETTINGS = 1
    const val DISABLE2_NOTIFICATION_SHADE = 1 shl 2

    fun of(restriction: NavRestriction): Pair<Int, Int> {
        var d1 = 0
        var d2 = 0
        if (restriction.blockShade) {
            d1 = d1 or DISABLE_EXPAND
            d2 = d2 or DISABLE2_QUICK_SETTINGS or DISABLE2_NOTIFICATION_SHADE
        }
        if (restriction.blockRecents) d1 = d1 or DISABLE_RECENTS
        return d1 to d2
    }

    /**
     * 读 `dumpsys statusbar` 里系统实际生效的禁用位：`mDisabled1=0x1010000  mDisabled2=0x5`。
     * 读不到返回 null——不能把命令返回当成生效（R29）。
     */
    fun parseDumpsys(text: String): Pair<Int, Int>? {
        val d1 = Regex("""mDisabled1=0x([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1)?.toLongOrNull(16)
        val d2 = Regex("""mDisabled2=0x([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1)?.toLongOrNull(16)
        if (d1 == null || d2 == null) return null
        return d1.toInt() to d2.toInt()
    }

    /** 系统合并了所有调用方的禁用位；我们要的位都在就算生效。 */
    fun covers(actual: Pair<Int, Int>, wanted: Pair<Int, Int>): Boolean =
        actual.first and wanted.first == wanted.first && actual.second and wanted.second == wanted.second
}

/**
 * 四类设备系统页的临时放行（R27）。
 *
 * 开始于打开系统页之前；本应用任一页面回到前台（Back 回原页面或 Home 回桌面）、熄屏、
 * 模式改变时结束。不依赖系统页或浮层的回调，进程重建后自然没有放行。
 */
class HandoffTracker {
    var active: Boolean = false
        private set

    fun begin() {
        active = true
    }

    /** @return 这次调用是否结束了一次放行。 */
    fun end(): Boolean {
        val was = active
        active = false
        return was
    }
}
