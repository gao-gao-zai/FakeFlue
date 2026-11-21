package com.flue.launcher.surface

import com.flue.launcher.surface.tools.BootSession
import com.flue.launcher.surface.tools.stopwatch.StopwatchMachine
import com.flue.launcher.surface.tools.stopwatch.StopwatchState
import com.flue.launcher.surface.tools.timer.TimerMachine
import com.flue.launcher.surface.tools.timer.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R31 计时器与秒表的纯状态机。 */
class TimerStopwatchMachineTest {
    private val boot = BootSession(bootCount = 7, bootWallMs = 1_000_000L)
    private val nextBoot = BootSession(bootCount = 8, bootWallMs = 9_000_000L)

    @Test
    fun timerStartPauseResumeCancel() {
        val idle = TimerState.Idle(60_000L)
        val running = TimerMachine.start(idle, 60_000L, runId = 1, nowElapsed = 1_000, nowWall = 50_000, boot = boot) as TimerState.Running
        assertEquals(61_000L, running.endElapsedMs)
        assertEquals(110_000L, running.endWallMs)
        assertEquals(40_000L, TimerMachine.remaining(running, 21_000))
        // 运行中不能再次开始。
        assertSame(running, TimerMachine.start(running, 5_000L, 2, 0, 0, boot))

        val paused = TimerMachine.pause(running, 21_000) as TimerState.Paused
        assertEquals(40_000L, paused.remainingMs)
        // 暂停期间时间流逝不影响剩余。
        assertEquals(40_000L, TimerMachine.remaining(paused, 999_999))

        val resumed = TimerMachine.resume(paused, runId = 2, nowElapsed = 100_000, nowWall = 500_000, boot = boot) as TimerState.Running
        assertEquals(2L, resumed.runId)
        assertEquals(140_000L, resumed.endElapsedMs)

        assertEquals(TimerState.Idle(60_000L), TimerMachine.cancel(resumed))
        // 零时长不开始。
        assertSame(idle, TimerMachine.start(idle, 0L, 3, 0, 0, boot))
    }

    @Test
    fun timerFiresOnceForCurrentRunOnly() {
        val running = TimerMachine.start(TimerState.Idle(), 10_000L, 5, 0, 0, boot) as TimerState.Running
        // 早到太多：不响。
        assertFalse(TimerMachine.due(running, 5, nowElapsed = 5_000, nowWall = 5_000).ring)
        // 旧登记（runId 不同）：不响。
        assertFalse(TimerMachine.due(running, 4, nowElapsed = 10_000, nowWall = 10_000).ring)
        val fired = TimerMachine.due(running, 5, nowElapsed = 10_000, nowWall = 10_000)
        assertTrue(fired.ring)
        assertFalse((fired.state as TimerState.Fired).late)
        // 重复送达：不再响。
        val again = TimerMachine.due(fired.state, 5, nowElapsed = 10_500, nowWall = 10_500)
        assertFalse(again.ring)
        assertSame(fired.state, again.state)
        // 到点后暂停不生效（交给到点处理）。
        assertSame(running, TimerMachine.pause(running, 10_000))
        assertEquals(TimerState.Idle(10_000L), TimerMachine.dismiss(fired.state))
        // 很晚才送达：标记迟到。
        assertTrue((TimerMachine.due(running, 5, nowElapsed = 200_000, nowWall = 200_000).state as TimerState.Fired).late)
    }

    @Test
    fun timerRecoverySameBootAndAfterReboot() {
        val running = TimerMachine.start(TimerState.Idle(), 60_000L, 9, nowElapsed = 10_000, nowWall = 1_000_000, boot = boot) as TimerState.Running
        // 同一次开机、未到点：不变。
        assertSame(running, TimerMachine.recover(running, 30_000, 1_020_000, boot).state)
        // 同一次开机、进程不在时到点：补响一次。
        val late = TimerMachine.recover(running, 200_000, 1_190_000, boot)
        assertTrue(late.ring)
        assertTrue(late.state is TimerState.Fired)
        assertFalse(TimerMachine.recover(late.state, 300_000, 1_300_000, boot).ring)

        // 重启后还剩 20 s（按墙上时间）：重新换算到新开机的单调时钟并标记估算。
        val rebooted = TimerMachine.recover(running, nowElapsed = 5_000, nowWall = 1_040_000, boot = nextBoot)
        assertFalse(rebooted.ring)
        val state = rebooted.state as TimerState.Running
        assertEquals(25_000L, state.endElapsedMs)
        assertTrue(state.estimatedAfterReboot)
        assertEquals(nextBoot, state.boot)
        // 再复算一次不再变化（新开机身份已记录）。
        assertSame(state, TimerMachine.recover(state, 6_000, 1_041_000, nextBoot).state)

        // 关机期间已到点：补响一次并标记迟到。
        val expired = TimerMachine.recover(running, nowElapsed = 5_000, nowWall = 2_000_000, boot = nextBoot)
        assertTrue(expired.ring)
        assertTrue((expired.state as TimerState.Fired).late)
    }

    @Test
    fun rebootDetectionFallsBackToBootWallTime() {
        val a = BootSession(null, 1_000_000L)
        assertTrue(a.sameAs(BootSession(null, 1_000_000L + 60_000L)))
        assertFalse(a.sameAs(BootSession(null, 1_000_000L + BootSession.BOOT_WALL_TOLERANCE_MS + 1)))
        assertFalse(BootSession(3, 0).sameAs(BootSession(4, 0)))
        // 单调时钟倒退说明重启过，即使开机身份读不出差异。
        val running = TimerMachine.start(TimerState.Idle(), 60_000L, 1, nowElapsed = 500_000, nowWall = 10_000_000, boot = a) as TimerState.Running
        val recovered = TimerMachine.recover(running, nowElapsed = 1_000, nowWall = 10_010_000, boot = a)
        assertTrue((recovered.state as TimerState.Running).estimatedAfterReboot)
    }

    @Test
    fun stopwatchTimingAndLaps() {
        var sw = StopwatchMachine.start(StopwatchState(), nowElapsed = 1_000, boot = boot)
        assertEquals(2_500L, StopwatchMachine.elapsed(sw, 3_500))
        sw = StopwatchMachine.lap(sw, 3_500)
        sw = StopwatchMachine.lap(sw, 4_000)
        sw = StopwatchMachine.pause(sw, 5_000)
        assertEquals(4_000L, StopwatchMachine.elapsed(sw, 99_999))
        // 暂停时不能计次；复位只在暂停时可用。
        assertSame(sw, StopwatchMachine.lap(sw, 6_000))
        sw = StopwatchMachine.start(sw, nowElapsed = 10_000, boot = boot)
        assertEquals(5_000L, StopwatchMachine.elapsed(sw, 11_000))
        assertSame(sw, StopwatchMachine.reset(sw))

        val rows = StopwatchMachine.lapRows(sw)
        assertEquals(listOf(2, 1), rows.map { it.index })
        assertEquals(500L, rows[0].splitMs)
        assertEquals(3_000L, rows[0].totalMs)
        assertEquals(2_500L, rows[1].splitMs)

        sw = StopwatchMachine.pause(sw, 11_000)
        assertTrue(StopwatchMachine.reset(sw).isZero)
        assertEquals("01:02.34", StopwatchMachine.format(62_345))
        assertEquals("1:00:00.00", StopwatchMachine.format(3_600_000))
    }

    @Test
    fun stopwatchRecovery() {
        val running = StopwatchMachine.start(StopwatchState(accumulatedMs = 1_000), nowElapsed = 10_000, boot = boot)
        // 同一次开机（进程重建）：继续正确计时。
        assertSame(running, StopwatchMachine.recover(running, 20_000, boot))
        assertEquals(11_000L, StopwatchMachine.elapsed(running, 20_000))
        // 运行中重启：清零并说明，不显示虚假时间。
        val reset = StopwatchMachine.recover(running, 5_000, nextBoot)
        assertTrue(reset.isZero)
        assertTrue(reset.resetByReboot)
        // 暂停中重启：读数与时钟无关，保留。
        val paused = StopwatchMachine.pause(running, 15_000)
        assertSame(paused, StopwatchMachine.recover(paused, 1_000, nextBoot))
        // 重新开始清除说明。
        assertFalse(StopwatchMachine.start(reset, 6_000, nextBoot).resetByReboot)
    }
}
