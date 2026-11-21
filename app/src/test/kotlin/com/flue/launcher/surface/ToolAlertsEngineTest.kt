package com.flue.launcher.surface

import android.app.AlarmManager
import android.content.Context
import com.flue.launcher.surface.apps.PublicAppRules
import com.flue.launcher.surface.tools.BootSession
import com.flue.launcher.surface.tools.SurfaceTool
import com.flue.launcher.surface.tools.SurfaceToolCatalog
import com.flue.launcher.surface.tools.ToolAlarmScheduler
import com.flue.launcher.surface.tools.ToolAlertsEngine
import com.flue.launcher.surface.tools.ToolClock
import com.flue.launcher.surface.tools.ToolNotifier
import com.flue.launcher.surface.tools.ToolStore
import com.flue.launcher.surface.tools.alarm.Alarm
import com.flue.launcher.surface.tools.alarm.AlarmRing
import com.flue.launcher.surface.tools.alert.AndroidToolScheduler
import com.flue.launcher.surface.tools.battery.BatteryFormat
import com.flue.launcher.surface.tools.timer.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R31 工具提醒协调：真实持久化（SharedPreferences）+ 假时钟、假登记、假通知。
 * “进程重建”用同一份存储新建一个引擎模拟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ToolAlertsEngineTest {

    private class FakeClock(var wall: Long, var elapsed: Long, var boot: BootSession, var zoneId: ZoneId) : ToolClock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
        override fun zone() = zoneId
        override fun boot() = boot
        fun advance(ms: Long) {
            wall += ms
            elapsed += ms
        }
    }

    private class FakeScheduler : ToolAlarmScheduler {
        var exact = true
        var timer: Pair<Long, Long>? = null
        val alarms = mutableMapOf<Int, Long>()
        override fun canScheduleExact() = exact
        override fun scheduleTimer(runId: Long, endElapsedMs: Long) { timer = runId to endElapsedMs }
        override fun cancelTimer() { timer = null }
        override fun scheduleAlarm(alarmId: Int, triggerWallMs: Long) { alarms[alarmId] = triggerWallMs }
        override fun cancelAlarm(alarmId: Int) { alarms.remove(alarmId) }
    }

    private class FakeNotifier : ToolNotifier {
        var allowed = true
        val timerRings = mutableListOf<TimerState.Fired>()
        val alarmRings = mutableListOf<AlarmRing>()
        var timerShown = false
        val alarmShown = mutableSetOf<Int>()
        override fun canNotify() = allowed
        override fun notifyTimer(state: TimerState.Fired) { timerRings += state; timerShown = true }
        override fun cancelTimer() { timerShown = false }
        override fun notifyAlarm(alarm: Alarm, ring: AlarmRing) { alarmRings += ring; alarmShown += alarm.id }
        override fun cancelAlarm(alarmId: Int) { alarmShown -= alarmId }
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val boot = BootSession(1, 0L)
    private lateinit var context: Context
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeScheduler
    private lateinit var notifier: FakeNotifier

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ToolStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        clock = FakeClock(
            wall = LocalDateTime.of(2026, 11, 20, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            elapsed = 1_000_000L,
            boot = boot,
            zoneId = zone,
        )
        scheduler = FakeScheduler()
        notifier = FakeNotifier()
    }

    /** 新进程：新的存储对象、新的登记器和通知器（系统登记在进程死亡后仍在，这里交给复算重新登记）。 */
    private fun newProcess(): ToolAlertsEngine {
        scheduler = FakeScheduler()
        notifier = FakeNotifier()
        return ToolAlertsEngine(ToolStore(context), clock, scheduler, notifier)
    }

    @Test
    fun timerRingsExactlyOnceAcrossDuplicateDeliveries() {
        val engine = newProcess()
        engine.startTimer(60_000L)
        val running = engine.timer.value as TimerState.Running
        assertEquals(running.runId to running.endElapsedMs, scheduler.timer)

        clock.advance(60_000L)
        engine.timerDue(running.runId)
        // 前台倒数到零与系统广播同时到达。
        engine.timerDue(running.runId)
        engine.reconcile()
        assertEquals(1, notifier.timerRings.size)
        assertTrue(engine.timer.value is TimerState.Fired)
        assertNull(scheduler.timer)

        // 进程重建：从持久状态恢复为“时间到”，不再响。
        val rebuilt = newProcess()
        rebuilt.reconcile()
        assertTrue(rebuilt.timer.value is TimerState.Fired)
        assertTrue(notifier.timerRings.isEmpty())

        rebuilt.dismissTimer()
        assertEquals(TimerState.Idle(60_000L), rebuilt.timer.value)
        assertEquals(TimerState.Idle(60_000L), ToolStore(context).loadTimer())
    }

    @Test
    fun pauseAndCancelWithdrawRegistrationAndIgnoreStaleBroadcasts() {
        val engine = newProcess()
        engine.startTimer(30_000L)
        val first = (engine.timer.value as TimerState.Running).runId
        clock.advance(10_000L)
        engine.pauseTimer()
        assertNull(scheduler.timer)
        assertEquals(20_000L, (engine.timer.value as TimerState.Paused).remainingMs)
        // 暂停期间旧登记送达：不响。
        clock.advance(100_000L)
        engine.timerDue(first)
        assertTrue(notifier.timerRings.isEmpty())

        engine.resumeTimer()
        val resumed = engine.timer.value as TimerState.Running
        assertTrue(resumed.runId > first)
        assertEquals(clock.elapsed + 20_000L, resumed.endElapsedMs)
        engine.cancelTimer()
        assertNull(scheduler.timer)
        clock.advance(30_000L)
        engine.timerDue(resumed.runId)
        assertTrue(notifier.timerRings.isEmpty())
        assertTrue(engine.timer.value is TimerState.Idle)
    }

    @Test
    fun runningTimerSurvivesProcessDeathAndFiresOnceIfMissed() {
        newProcess().startTimer(120_000L)
        // 进程被杀，未到点：重建后重新登记，不响。
        clock.advance(30_000L)
        var engine = newProcess()
        engine.reconcile()
        val running = engine.timer.value as TimerState.Running
        assertEquals(running.runId to running.endElapsedMs, scheduler.timer)
        assertTrue(notifier.timerRings.isEmpty())
        // 进程不在时到点（例如强停后系统登记被清除）：下次启动补响一次。
        clock.advance(200_000L)
        engine = newProcess()
        engine.reconcile()
        engine.reconcile()
        assertEquals(1, notifier.timerRings.size)
        assertTrue(notifier.timerRings.single().late)
        newProcess().reconcile()
        assertTrue(notifier.timerRings.isEmpty())
    }

    @Test
    fun timerAfterRebootUsesWallClockEstimate() {
        newProcess().startTimer(600_000L)
        clock.advance(100_000L)
        // 重启：单调时钟归零，开机次数变化。
        clock.elapsed = 5_000L
        clock.boot = BootSession(2, clock.wall - 5_000L)
        val engine = newProcess()
        engine.reconcile()
        val state = engine.timer.value as TimerState.Running
        assertTrue(state.estimatedAfterReboot)
        assertEquals(5_000L + 500_000L, state.endElapsedMs)
        assertEquals(state.runId to state.endElapsedMs, scheduler.timer)
    }

    @Test
    fun alarmFiresOnceAndRecoversAfterRecreation() {
        var engine = newProcess()
        assertTrue(engine.addAlarm(9, 0, emptySet()))
        val alarm = engine.alarms.value.single()
        val trigger = alarm.nextTriggerMs!!
        assertEquals(trigger, scheduler.alarms[alarm.id])

        // 进程重建：重新登记同一时刻。
        engine = newProcess()
        engine.reconcile()
        assertEquals(trigger, scheduler.alarms[alarm.id])
        assertTrue(notifier.alarmRings.isEmpty())

        clock.wall = trigger + 1_000
        engine.alarmFired(alarm.id, trigger)
        engine.alarmFired(alarm.id, trigger)
        engine.reconcile()
        assertEquals(1, notifier.alarmRings.size)
        assertEquals(listOf(alarm.id), engine.ringing.value.map { it.alarmId })
        assertFalse(engine.alarms.value.single().enabled)
        assertNull(scheduler.alarms[alarm.id])

        // 重建后：仍显示正在响，不重复响。
        engine = newProcess()
        engine.reconcile()
        assertTrue(notifier.alarmRings.isEmpty())
        assertEquals(1, engine.ringing.value.size)
        engine.dismissAlarm(alarm.id)
        assertTrue(engine.ringing.value.isEmpty())
        assertTrue(ToolStore(context).loadRinging().isEmpty())
    }

    @Test
    fun repeatingAlarmMissedWhileOffIsReportedOnceAndRescheduled() {
        val engine = newProcess()
        engine.addAlarm(6, 0, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
        // 第一次登记是周五 6:00 之后的下一次：周六 6:00。
        val sat = LocalDateTime.of(2026, 11, 21, 6, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(sat, engine.alarms.value.single().nextTriggerMs)
        // 设备在周六 6:00 前关机，周六 10:00 开机。
        clock.wall = sat + 4 * 3_600_000L
        clock.elapsed = 1_000L
        clock.boot = BootSession(2, clock.wall - 1_000L)
        val after = newProcess()
        after.reconcile()
        assertEquals(1, notifier.alarmRings.size)
        assertTrue(notifier.alarmRings.single().missed)
        assertTrue(after.ringing.value.isEmpty())
        val fri = LocalDateTime.of(2026, 11, 27, 6, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(fri, after.alarms.value.single().nextTriggerMs)
        assertEquals(fri, scheduler.alarms.values.single())
        after.reconcile()
        assertEquals(1, notifier.alarmRings.size)
    }

    @Test
    fun deletingOrDisablingWithdrawsRegistration() {
        val engine = newProcess()
        engine.addAlarm(9, 0, emptySet())
        engine.addAlarm(10, 0, emptySet())
        engine.setAlarmEnabled(1, false)
        assertNull(scheduler.alarms[1])
        assertTrue(2 in scheduler.alarms)
        engine.deleteAlarm(2)
        assertTrue(scheduler.alarms.isEmpty())
        engine.setAlarmEnabled(1, true)
        assertTrue(1 in scheduler.alarms)
        assertEquals(1, newProcess().alarms.value.size)
    }

    @Test
    fun stopwatchPersistsAcrossRecreationAndResetsAfterReboot() {
        var engine = newProcess()
        engine.stopwatchStart()
        clock.advance(1_500L)
        engine.stopwatchLap()
        clock.advance(500L)
        engine = newProcess()
        engine.reconcile()
        val state = engine.stopwatch.value
        assertTrue(state.running)
        assertEquals(listOf(1_500L), state.laps)
        assertEquals(2_000L, com.flue.launcher.surface.tools.stopwatch.StopwatchMachine.elapsed(state, clock.elapsed))

        clock.elapsed = 100L
        clock.boot = BootSession(5, clock.wall - 100L)
        engine = newProcess()
        engine.reconcile()
        assertTrue(engine.stopwatch.value.resetByReboot)
        assertTrue(engine.stopwatch.value.isZero)
    }

    @Test
    fun androidSchedulerRegistersAndCancels() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = shadowOf(manager)
        val real = AndroidToolScheduler(context)
        assertTrue(real.canScheduleExact())
        real.scheduleAlarm(3, 5_000_000L)
        real.scheduleAlarm(3, 6_000_000L)
        real.scheduleTimer(7, 90_000L)
        assertEquals(2, shadow.scheduledAlarms.size)
        real.cancelAlarm(3)
        assertEquals(1, shadow.scheduledAlarms.size)
        real.cancelTimer()
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test
    fun storeRoundTripsAllStates() {
        val store = ToolStore(context)
        val states = listOf(
            TimerState.Idle(42_000L),
            TimerState.Running(60_000L, 3, 1_000, 2_000, BootSession(null, 5), estimatedAfterReboot = true),
            TimerState.Paused(60_000L, 12_345L),
            TimerState.Fired(60_000L, 4, 99, late = true),
        )
        states.forEach {
            store.saveTimer(it)
            assertEquals(it, ToolStore(context).loadTimer())
        }
        val alarms = listOf(Alarm(1, 7, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), true, 10L, 5L), Alarm(2, 23, 59))
        store.saveAlarms(alarms)
        assertEquals(alarms, ToolStore(context).loadAlarms())
        // 损坏的数据不崩溃。
        context.getSharedPreferences(ToolStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString("timer", "{broken").putString("alarms", "nope").commit()
        assertEquals(TimerState.Idle(), ToolStore(context).loadTimer())
        assertTrue(ToolStore(context).loadAlarms().isEmpty())
    }

    @Test
    fun toolsAreAlwaysPublicOnlyForOwnPackage() {
        val own = com.flue.launcher.BuildConfig.APPLICATION_ID
        assertEquals("com.gaozai.surface", own)
        SurfaceTool.entries.forEach { tool ->
            val key = "$own/${tool.className}"
            assertTrue(SurfaceToolCatalog.isToolComponentKey(key))
            assertTrue(PublicAppRules.isPublic(key, own, builtIn = false, whitelist = emptySet()))
            // 其他应用声明同名类不算。
            assertFalse(PublicAppRules.isPublic("evil.app/${tool.className}", "evil.app", false, emptySet()))
            Class.forName(tool.className, false, javaClass.classLoader)
        }
        assertFalse(SurfaceToolCatalog.isToolComponentKey("$own/com.flue.launcher.SettingsActivity"))
        assertFalse(PublicAppRules.isPublic("$own/com.flue.launcher.SettingsActivity", own, false, emptySet()))
    }

    @Test
    fun batteryFormatting() {
        assertEquals("4.12 V", BatteryFormat.voltage(4123))
        assertNull(BatteryFormat.voltage(0))
        assertNull(BatteryFormat.voltage(null))
        assertEquals(87, BatteryFormat.levelPercent(87, 100))
        assertNull(BatteryFormat.levelPercent(-1, 100))
        assertNull(BatteryFormat.pluggedRes(null))
        assertNull(BatteryFormat.healthRes(android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN))
        assertEquals(
            com.flue.launcher.R.string.surface_tool_battery_plug_usb,
            BatteryFormat.pluggedRes(android.os.BatteryManager.BATTERY_PLUGGED_USB),
        )
    }
}
