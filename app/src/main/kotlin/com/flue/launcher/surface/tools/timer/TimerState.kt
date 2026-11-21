package com.flue.launcher.surface.tools.timer

import com.flue.launcher.surface.tools.BootSession

/**
 * 计时器状态（R31）。一次只有一个倒计时。
 *
 * 每次开始或继续都换一个新的 [runId]：系统登记的到点广播带着它，状态里的 runId 对不上
 * （已暂停、已取消、已重新开始）就丢弃，同一个 runId 只会响一次。
 */
sealed interface TimerState {
    val durationMs: Long

    data class Idle(override val durationMs: Long = DEFAULT_DURATION_MS) : TimerState

    data class Running(
        override val durationMs: Long,
        val runId: Long,
        /** 本次开机内的到点时刻（`elapsedRealtime`）。 */
        val endElapsedMs: Long,
        /** 同一到点时刻的墙上时间，只在重启后用来估算剩余时间。 */
        val endWallMs: Long,
        val boot: BootSession,
        /** 设备重启过，剩余时间是按系统时间估算的。 */
        val estimatedAfterReboot: Boolean = false,
    ) : TimerState

    data class Paused(
        override val durationMs: Long,
        val remainingMs: Long,
    ) : TimerState

    data class Fired(
        override val durationMs: Long,
        val runId: Long,
        val firedAtWallMs: Long,
        /** 不是准点响起：设备关机、进程被停止或系统推迟了提醒。 */
        val late: Boolean,
    ) : TimerState

    companion object {
        const val DEFAULT_DURATION_MS = 5 * 60_000L
        const val MAX_DURATION_MS = 24 * 3_600_000L - 1_000L
    }
}

/** 状态迁移结果：新状态，以及这次是否要发出到点提醒。 */
data class TimerTransition(val state: TimerState, val ring: Boolean = false)

/** 纯状态机：所有时刻由调用方传入，便于测试和进程重建后复算。 */
object TimerMachine {
    /** 到点广播最多可以早到多少（系统时钟换算误差）；更早就当作没到、重新登记。 */
    const val EARLY_TOLERANCE_MS = 1_000L

    /** 晚于到点多少算“迟到”（界面如实说明）。 */
    const val LATE_THRESHOLD_MS = 60_000L

    fun clampDuration(ms: Long): Long = ms.coerceIn(0L, TimerState.MAX_DURATION_MS)

    fun start(
        state: TimerState,
        durationMs: Long,
        runId: Long,
        nowElapsed: Long,
        nowWall: Long,
        boot: BootSession,
    ): TimerState {
        if (state is TimerState.Running || state is TimerState.Paused) return state
        val duration = clampDuration(durationMs)
        if (duration <= 0L) return state
        return TimerState.Running(duration, runId, nowElapsed + duration, nowWall + duration, boot)
    }

    /** 暂停。已经到点（剩余为 0）时不暂停，交给 [due] 处理。 */
    fun pause(state: TimerState, nowElapsed: Long): TimerState {
        if (state !is TimerState.Running) return state
        val remaining = state.endElapsedMs - nowElapsed
        if (remaining <= 0L) return state
        return TimerState.Paused(state.durationMs, remaining)
    }

    fun resume(state: TimerState, runId: Long, nowElapsed: Long, nowWall: Long, boot: BootSession): TimerState {
        if (state !is TimerState.Paused) return state
        return TimerState.Running(state.durationMs, runId, nowElapsed + state.remainingMs, nowWall + state.remainingMs, boot)
    }

    /** 取消：回到未开始，保留上次设定的时长。 */
    fun cancel(state: TimerState): TimerState = TimerState.Idle(state.durationMs)

    /** 关闭“时间到”。 */
    fun dismiss(state: TimerState): TimerState =
        if (state is TimerState.Fired) TimerState.Idle(state.durationMs) else state

    fun setDuration(state: TimerState, durationMs: Long): TimerState =
        if (state is TimerState.Idle) TimerState.Idle(clampDuration(durationMs)) else state

    /** 剩余毫秒。运行中按本次开机的单调时钟算；开机身份变了先 [recover]。 */
    fun remaining(state: TimerState, nowElapsed: Long): Long = when (state) {
        is TimerState.Idle -> state.durationMs
        is TimerState.Running -> (state.endElapsedMs - nowElapsed).coerceAtLeast(0L)
        is TimerState.Paused -> state.remainingMs
        is TimerState.Fired -> 0L
    }

    /**
     * 到点通知（系统广播或前台页面倒数到零）。
     * runId 不符、已经响过、还没到点都不响。
     */
    fun due(state: TimerState, runId: Long, nowElapsed: Long, nowWall: Long): TimerTransition {
        if (state !is TimerState.Running || state.runId != runId) return TimerTransition(state)
        if (nowElapsed + EARLY_TOLERANCE_MS < state.endElapsedMs) return TimerTransition(state)
        val late = nowElapsed - state.endElapsedMs > LATE_THRESHOLD_MS || state.estimatedAfterReboot
        return TimerTransition(TimerState.Fired(state.durationMs, runId, nowWall, late), ring = true)
    }

    /**
     * 进程重建、开机或时间变化后的复算。
     *
     * - 同一次开机：单调时钟仍然有效；已过到点就补响一次。
     * - 重启过：单调时钟作废，按墙上时间估算剩余时间并标记“估算”；关机期间已到点则补响一次。
     */
    fun recover(state: TimerState, nowElapsed: Long, nowWall: Long, boot: BootSession): TimerTransition {
        if (state !is TimerState.Running) return TimerTransition(state)
        val sameBoot = state.boot.sameAs(boot) && state.endElapsedMs - state.durationMs <= nowElapsed
        if (sameBoot) {
            return if (nowElapsed >= state.endElapsedMs) due(state, state.runId, nowElapsed, nowWall) else TimerTransition(state)
        }
        val remaining = state.endWallMs - nowWall
        return if (remaining > 0L) {
            TimerTransition(
                state.copy(
                    endElapsedMs = nowElapsed + remaining.coerceAtMost(state.durationMs),
                    boot = boot,
                    estimatedAfterReboot = true,
                ),
            )
        } else {
            TimerTransition(TimerState.Fired(state.durationMs, state.runId, nowWall, late = true), ring = true)
        }
    }
}
