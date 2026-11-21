package com.flue.launcher.surface.tools

import com.flue.launcher.surface.tools.alarm.Alarm
import com.flue.launcher.surface.tools.alarm.AlarmRing
import com.flue.launcher.surface.tools.alarm.AlarmSchedule
import com.flue.launcher.surface.tools.stopwatch.StopwatchMachine
import com.flue.launcher.surface.tools.stopwatch.StopwatchState
import com.flue.launcher.surface.tools.timer.TimerMachine
import com.flue.launcher.surface.tools.timer.TimerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek

/** 系统定时登记。实现见 [com.flue.launcher.surface.tools.alert.AndroidToolScheduler]。 */
interface ToolAlarmScheduler {
    /** 系统是否允许精确定时；不允许时登记会被系统推迟（界面如实说明）。 */
    fun canScheduleExact(): Boolean
    fun scheduleTimer(runId: Long, endElapsedMs: Long)
    fun cancelTimer()
    fun scheduleAlarm(alarmId: Int, triggerWallMs: Long)
    fun cancelAlarm(alarmId: Int)
}

/** 到点提醒。实现见 [com.flue.launcher.surface.tools.alert.AndroidToolNotifier]。 */
interface ToolNotifier {
    /** 系统当前是否允许本应用发出通知；不允许时到点无法提醒。 */
    fun canNotify(): Boolean
    fun notifyTimer(state: TimerState.Fired)
    fun cancelTimer()
    fun notifyAlarm(alarm: Alarm, ring: AlarmRing)
    fun cancelAlarm(alarmId: Int)
}

/**
 * 闹钟、计时器、秒表的状态与提醒协调（R31）。
 *
 * 所有改动在同一把锁里：读状态 → 纯状态机迁移 → 先落盘 → 再改系统登记和通知。
 * 到点广播、前台倒数到零、开机复算可能同时到来，靠 runId／响铃时刻去重，只响一次。
 * 取消、暂停、删除都同时撤销系统登记；进程重建后 [reconcile] 从持久状态恢复。
 */
class ToolAlertsEngine(
    private val store: ToolStore,
    private val clock: ToolClock,
    private val scheduler: ToolAlarmScheduler,
    private val notifier: ToolNotifier,
) {
    private val lock = Any()

    private val _timer = MutableStateFlow(store.loadTimer())
    val timer: StateFlow<TimerState> = _timer.asStateFlow()

    private val _alarms = MutableStateFlow(store.loadAlarms())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _ringing = MutableStateFlow(store.loadRinging())
    val ringing: StateFlow<List<RingingAlarm>> = _ringing.asStateFlow()

    private val _stopwatch = MutableStateFlow(store.loadStopwatch())
    val stopwatch: StateFlow<StopwatchState> = _stopwatch.asStateFlow()

    fun canNotify(): Boolean = notifier.canNotify()

    fun canScheduleExact(): Boolean = scheduler.canScheduleExact()

    // ---- 复算 ----

    /** 开机、时间／时区变化、精确定时授权变化、进程重建、页面回到前台时调用。可重复调用。 */
    fun reconcile() = synchronized(lock) {
        recoverTimer()
        reconcileAlarms()
        expireRinging()
        val sw = _stopwatch.value
        val recovered = StopwatchMachine.recover(sw, clock.elapsedMs(), clock.boot())
        if (recovered != sw) setStopwatch(recovered)
    }

    private fun recoverTimer() {
        val current = _timer.value
        val transition = TimerMachine.recover(current, clock.elapsedMs(), clock.wallMs(), clock.boot())
        if (transition.state != current) setTimer(transition.state)
        applyTimer(transition.state, ring = transition.ring)
    }

    private fun reconcileAlarms() {
        val transition = AlarmSchedule.reconcile(_alarms.value, clock.wallMs(), clock.zone())
        if (transition.alarms != _alarms.value) setAlarms(transition.alarms)
        transition.alarms.forEach(::applyAlarm)
        deliver(transition.rings)
    }

    private fun expireRinging() {
        val now = clock.wallMs()
        val live = _ringing.value.filter { now - it.sinceWallMs < RING_TIMEOUT_MS && now >= it.sinceWallMs }
        if (live != _ringing.value) setRinging(live)
    }

    // ---- 计时器 ----

    fun setTimerDuration(durationMs: Long) = synchronized(lock) {
        val next = TimerMachine.setDuration(_timer.value, durationMs)
        if (next != _timer.value) setTimer(next)
    }

    fun startTimer(durationMs: Long) = synchronized(lock) {
        val current = _timer.value
        if (current is TimerState.Running || current is TimerState.Paused) return@synchronized
        val next = TimerMachine.start(current, durationMs, store.nextRunId(), clock.elapsedMs(), clock.wallMs(), clock.boot())
        if (next == current) return@synchronized
        notifier.cancelTimer()
        setTimer(next)
        applyTimer(next, ring = false)
    }

    fun pauseTimer() = synchronized(lock) {
        val current = _timer.value
        val next = TimerMachine.pause(current, clock.elapsedMs())
        if (next == current) {
            // 已经到点：按到点处理，不暂停成 0。
            if (current is TimerState.Running) timerDueLocked(current.runId)
            return@synchronized
        }
        setTimer(next)
        scheduler.cancelTimer()
    }

    fun resumeTimer() = synchronized(lock) {
        val current = _timer.value
        if (current !is TimerState.Paused) return@synchronized
        val next = TimerMachine.resume(current, store.nextRunId(), clock.elapsedMs(), clock.wallMs(), clock.boot())
        setTimer(next)
        applyTimer(next, ring = false)
    }

    fun cancelTimer() = synchronized(lock) {
        setTimer(TimerMachine.cancel(_timer.value))
        scheduler.cancelTimer()
        notifier.cancelTimer()
    }

    fun dismissTimer() = synchronized(lock) {
        val current = _timer.value
        if (current is TimerState.Fired) setTimer(TimerMachine.dismiss(current))
        notifier.cancelTimer()
    }

    /** 到点：系统广播或前台页面倒数到零。 */
    fun timerDue(runId: Long) = synchronized(lock) { timerDueLocked(runId) }

    private fun timerDueLocked(runId: Long) {
        val current = _timer.value
        val transition = TimerMachine.due(current, runId, clock.elapsedMs(), clock.wallMs())
        if (transition.state == current) {
            // 早到：确保仍有登记。
            if (current is TimerState.Running && current.runId == runId) scheduler.scheduleTimer(current.runId, current.endElapsedMs)
            return
        }
        setTimer(transition.state)
        applyTimer(transition.state, transition.ring)
    }

    private fun applyTimer(state: TimerState, ring: Boolean) {
        when (state) {
            is TimerState.Running -> scheduler.scheduleTimer(state.runId, state.endElapsedMs)
            is TimerState.Fired -> {
                scheduler.cancelTimer()
                if (ring) notifier.notifyTimer(state)
            }
            else -> scheduler.cancelTimer()
        }
    }

    // ---- 闹钟 ----

    fun addAlarm(hour: Int, minute: Int, days: Set<DayOfWeek>): Boolean = synchronized(lock) {
        val transition = AlarmSchedule.create(_alarms.value, hour, minute, days, clock.wallMs(), clock.zone())
        if (transition.alarms == _alarms.value) return@synchronized false
        setAlarms(transition.alarms)
        transition.alarms.lastOrNull()?.let(::applyAlarm)
        true
    }

    fun updateAlarm(id: Int, hour: Int, minute: Int, days: Set<DayOfWeek>) = synchronized(lock) {
        mutateAlarms(AlarmSchedule.update(_alarms.value, id, hour, minute, days, clock.wallMs(), clock.zone()).alarms, id)
    }

    fun setAlarmEnabled(id: Int, enabled: Boolean) = synchronized(lock) {
        mutateAlarms(AlarmSchedule.setEnabled(_alarms.value, id, enabled, clock.wallMs(), clock.zone()).alarms, id)
    }

    fun deleteAlarm(id: Int) = synchronized(lock) {
        setAlarms(AlarmSchedule.delete(_alarms.value, id).alarms)
        scheduler.cancelAlarm(id)
        dismissAlarmLocked(id)
    }

    private fun mutateAlarms(next: List<Alarm>, id: Int) {
        setAlarms(next)
        next.firstOrNull { it.id == id }?.let(::applyAlarm)
        dismissAlarmLocked(id)
    }

    /** 系统送来的闹钟到点广播。 */
    fun alarmFired(id: Int, triggerMs: Long) = synchronized(lock) {
        val transition = AlarmSchedule.onFire(_alarms.value, id, triggerMs, clock.wallMs(), clock.zone())
        if (transition.alarms != _alarms.value) setAlarms(transition.alarms)
        transition.alarms.firstOrNull { it.id == id }?.let(::applyAlarm)
        deliver(transition.rings)
    }

    /** 关闭正在响的闹钟（通知按钮、划掉通知或页面按钮）。 */
    fun dismissAlarm(id: Int) = synchronized(lock) { dismissAlarmLocked(id) }

    private fun dismissAlarmLocked(id: Int) {
        val rest = _ringing.value.filterNot { it.alarmId == id }
        if (rest != _ringing.value) setRinging(rest)
        notifier.cancelAlarm(id)
    }

    private fun applyAlarm(alarm: Alarm) {
        val next = alarm.nextTriggerMs
        if (alarm.enabled && next != null) scheduler.scheduleAlarm(alarm.id, next) else scheduler.cancelAlarm(alarm.id)
    }

    private fun deliver(rings: List<AlarmRing>) {
        if (rings.isEmpty()) return
        val now = clock.wallMs()
        val sounding = rings.filterNot { it.missed }
        if (sounding.isNotEmpty()) {
            setRinging(_ringing.value.filterNot { r -> sounding.any { it.alarmId == r.alarmId } } +
                sounding.map { RingingAlarm(it.alarmId, it.triggerMs, now) })
        }
        val byId = _alarms.value.associateBy { it.id }
        rings.forEach { ring -> byId[ring.alarmId]?.let { notifier.notifyAlarm(it, ring) } }
    }

    // ---- 秒表 ----

    fun stopwatchStart() = synchronized(lock) { setStopwatch(StopwatchMachine.start(_stopwatch.value, clock.elapsedMs(), clock.boot())) }

    fun stopwatchPause() = synchronized(lock) { setStopwatch(StopwatchMachine.pause(_stopwatch.value, clock.elapsedMs())) }

    fun stopwatchLap() = synchronized(lock) { setStopwatch(StopwatchMachine.lap(_stopwatch.value, clock.elapsedMs())) }

    fun stopwatchReset() = synchronized(lock) { setStopwatch(StopwatchMachine.reset(_stopwatch.value)) }

    // ---- 落盘 ----

    private fun setTimer(state: TimerState) {
        store.saveTimer(state)
        _timer.value = state
    }

    private fun setAlarms(alarms: List<Alarm>) {
        store.saveAlarms(alarms)
        _alarms.value = alarms
    }

    private fun setRinging(list: List<RingingAlarm>) {
        store.saveRinging(list)
        _ringing.value = list
    }

    private fun setStopwatch(state: StopwatchState) {
        if (state == _stopwatch.value) return
        store.saveStopwatch(state)
        _stopwatch.value = state
    }

    companion object {
        /** 响铃最长持续时间；通知到时自动收起。 */
        const val RING_TIMEOUT_MS = 10 * 60_000L
    }
}
