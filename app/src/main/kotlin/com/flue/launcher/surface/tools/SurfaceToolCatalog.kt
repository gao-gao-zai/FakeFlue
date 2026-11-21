package com.flue.launcher.surface.tools

import com.flue.launcher.BuildConfig
import com.flue.launcher.R

/**
 * FFlue 自带的五个基础工具（R31）。每个工具是本应用里一个带 LAUNCHER 入口的独立页面：
 * 应用列表照常从系统查询到它们（与普通应用同一条加载、图标、排序、文件夹路径），
 * 两种模式都能打开；伪装模式把它们当作内置应用，总是公开（R10“展示内置应用及选定的第三方应用”）。
 */
enum class SurfaceTool(val className: String, val labelRes: Int) {
    BATTERY("com.flue.launcher.surface.tools.BatteryToolActivity", R.string.surface_tool_battery_title),
    ALARM("com.flue.launcher.surface.tools.AlarmToolActivity", R.string.surface_tool_alarm_title),
    TIMER("com.flue.launcher.surface.tools.TimerToolActivity", R.string.surface_tool_timer_title),
    STOPWATCH("com.flue.launcher.surface.tools.StopwatchToolActivity", R.string.surface_tool_stopwatch_title),
    CALCULATOR("com.flue.launcher.surface.tools.CalculatorToolActivity", R.string.surface_tool_calculator_title),
}

object SurfaceToolCatalog {
    private val classNames: Set<String> = SurfaceTool.entries.mapTo(HashSet()) { it.className }

    /** 本应用自己的工具页面（只认本应用包名，其他应用同名类不算）。 */
    fun isToolActivity(packageName: String, activityName: String): Boolean =
        packageName == BuildConfig.APPLICATION_ID && activityName in classNames

    /** 应用列表条目键 `包名/Activity` 是否指向本应用的工具页面。 */
    fun isToolComponentKey(componentKey: String): Boolean {
        val slash = componentKey.indexOf('/')
        if (slash <= 0) return false
        return isToolActivity(componentKey.substring(0, slash), componentKey.substring(slash + 1))
    }
}
