package com.flue.launcher.surface.tools.stopwatch

import com.flue.launcher.surface.tools.BootSession

/**
 * 秒表（R31），基于本次开机的单调时钟。
 *
 * [laps] 记录每次计次时的累计时长。运行中重启过：单调时钟作废，无法知道关机前跑了多久，
 * 清零并置 [resetByReboot]，界面如实说明；暂停中的读数与时钟无关，保留。
 */
data class StopwatchState(
    val accumulatedMs: Long = 0L,
    val runningSinceElapsedMs: Long? = null,
    val boot: BootSession? = null,
    val laps: List<Long> = emptyList(),
    val resetByReboot: Boolean = false,
) {
    val running: Boolean get() = runningSinceElapsedMs != null
    val isZero: Boolean get() = !running && accumulatedMs == 0L && laps.isEmpty()
}

data class LapRow(val index: Int, val splitMs: Long, val totalMs: Long)

object StopwatchMachine {
    const val MAX_LAPS = 99

    fun elapsed(state: StopwatchState, nowElapsed: Long): Long {
        val since = state.runningSinceElapsedMs ?: return state.accumulatedMs
        return state.accumulatedMs + (nowElapsed - since).coerceAtLeast(0L)
    }

    fun start(state: StopwatchState, nowElapsed: Long, boot: BootSession): StopwatchState {
        if (state.running) return state
        return state.copy(runningSinceElapsedMs = nowElapsed, boot = boot, resetByReboot = false)
    }

    fun pause(state: StopwatchState, nowElapsed: Long): StopwatchState {
        if (!state.running) return state
        return state.copy(accumulatedMs = elapsed(state, nowElapsed), runningSinceElapsedMs = null)
    }

    fun lap(state: StopwatchState, nowElapsed: Long): StopwatchState {
        if (!state.running || state.laps.size >= MAX_LAPS) return state
        return state.copy(laps = state.laps + elapsed(state, nowElapsed))
    }

    fun reset(state: StopwatchState): StopwatchState = if (state.running) state else StopwatchState()

    fun recover(state: StopwatchState, nowElapsed: Long, boot: BootSession): StopwatchState {
        val since = state.runningSinceElapsedMs ?: return state
        val saved = state.boot
        val sameBoot = saved != null && saved.sameAs(boot) && since <= nowElapsed
        return if (sameBoot) state else StopwatchState(resetByReboot = true)
    }

    /** 计次列表，最新的在前。 */
    fun lapRows(state: StopwatchState): List<LapRow> =
        state.laps.mapIndexed { index, total ->
            LapRow(index + 1, total - (if (index == 0) 0L else state.laps[index - 1]), total)
        }.asReversed()

    /** `mm:ss.cc`，满一小时显示 `h:mm:ss.cc`。 */
    fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val centis = (total / 10) % 100
        val seconds = (total / 1000) % 60
        val minutes = (total / 60_000) % 60
        val hours = total / 3_600_000
        return if (hours > 0) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, seconds, centis)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, centis)
        }
    }
}
