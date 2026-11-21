package com.flue.launcher.surface.tools.alarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 一个闹钟（R31）。时刻是本地钟面时间：换时区后仍在当地 [hour]:[minute] 响。
 *
 * [nextTriggerMs] 是已经登记的下一次响铃（墙上时间）；[lastHandledTriggerMs] 是最近一次
 * 已经处理（响过或记为错过）的响铃时刻，同一时刻不会第二次响。
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** 空集合表示只响一次。 */
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val nextTriggerMs: Long? = null,
    val lastHandledTriggerMs: Long? = null,
) {
    val repeating: Boolean get() = days.isNotEmpty()
}

/** 一次需要提醒的响铃。[missed]：设备关机等原因错过太久，只告知错过，不再响铃。 */
data class AlarmRing(val alarmId: Int, val triggerMs: Long, val late: Boolean, val missed: Boolean)

data class AlarmTransition(val alarms: List<Alarm>, val rings: List<AlarmRing> = emptyList())

object AlarmSchedule {
    const val MAX_ALARMS = 20

    /** 到点广播最多可以早到多少；更早只重新登记。 */
    const val EARLY_TOLERANCE_MS = 60_000L

    /** 复算时发现已过响铃时刻：这个时间内补响，更久只提示错过。 */
    const val LATE_GRACE_MS = 10 * 60_000L

    /** 晚于响铃时刻多少算“迟到”。 */
    const val LATE_THRESHOLD_MS = 60_000L

    /**
     * 严格晚于 [afterMs] 的下一次响铃时刻。夏令时跳过的时刻顺延到跳变之后，重叠的时刻取第一次。
     */
    fun nextTrigger(hour: Int, minute: Int, days: Set<DayOfWeek>, afterMs: Long, zone: ZoneId): Long {
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var date = after.toLocalDate().minusDays(1)
        repeat(9) {
            val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (candidate > afterMs && (days.isEmpty() || date.dayOfWeek in days)) return candidate
            date = date.plusDays(1)
        }
        error("unreachable: no trigger within a week")
    }

    private fun Alarm.scheduledAfter(nowMs: Long, zone: ZoneId): Alarm {
        if (!enabled) return copy(nextTriggerMs = null)
        val floor = maxOf(nowMs, lastHandledTriggerMs ?: Long.MIN_VALUE)
        return copy(nextTriggerMs = nextTrigger(hour, minute, days, floor, zone))
    }

    fun create(alarms: List<Alarm>, hour: Int, minute: Int, days: Set<DayOfWeek>, nowMs: Long, zone: ZoneId): AlarmTransition {
        if (alarms.size >= MAX_ALARMS) return AlarmTransition(alarms)
        val id = (alarms.maxOfOrNull { it.id } ?: 0) + 1
        val alarm = Alarm(id, hour.coerceIn(0, 23), minute.coerceIn(0, 59), days).scheduledAfter(nowMs, zone)
        return AlarmTransition(alarms + alarm)
    }

    fun update(alarms: List<Alarm>, id: Int, hour: Int, minute: Int, days: Set<DayOfWeek>, nowMs: Long, zone: ZoneId): AlarmTransition =
        AlarmTransition(alarms.map {
            if (it.id != id) it else it.copy(hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59), days = days, enabled = true)
                .scheduledAfter(nowMs, zone)
        })

    fun setEnabled(alarms: List<Alarm>, id: Int, enabled: Boolean, nowMs: Long, zone: ZoneId): AlarmTransition =
        AlarmTransition(alarms.map { if (it.id != id) it else it.copy(enabled = enabled).scheduledAfter(nowMs, zone) })

    fun delete(alarms: List<Alarm>, id: Int): AlarmTransition = AlarmTransition(alarms.filterNot { it.id == id })

    /** 一次响铃已经处理：重复闹钟排到下一次，单次闹钟关闭。 */
    private fun Alarm.handled(triggerMs: Long, nowMs: Long, zone: ZoneId): Alarm {
        val marked = copy(lastHandledTriggerMs = triggerMs)
        return if (repeating) marked.scheduledAfter(nowMs, zone) else marked.copy(enabled = false, nextTriggerMs = null)
    }

    /**
     * 系统送来的到点广播。闹钟已删除、已关闭、时刻对不上（改过时间后的旧登记）或已经处理过都不响；
     * 早到太多只重新登记。
     */
    fun onFire(alarms: List<Alarm>, id: Int, triggerMs: Long, nowMs: Long, zone: ZoneId): AlarmTransition {
        val alarm = alarms.firstOrNull { it.id == id } ?: return AlarmTransition(alarms)
        if (!alarm.enabled || alarm.nextTriggerMs != triggerMs || alarm.lastHandledTriggerMs == triggerMs) {
            return AlarmTransition(alarms)
        }
        if (nowMs + EARLY_TOLERANCE_MS < triggerMs) return AlarmTransition(alarms)
        val ring = AlarmRing(id, triggerMs, late = nowMs - triggerMs > LATE_THRESHOLD_MS, missed = false)
        return AlarmTransition(alarms.map { if (it.id == id) it.handled(triggerMs, nowMs, zone) else it }, listOf(ring))
    }

    /**
     * 开机、时间或时区变化、进程重建后的复算：已过响铃时刻的按 [LATE_GRACE_MS] 补响或记为错过，
     * 其余按当前时间和时区重算下一次。可以重复调用，不会重复提醒。
     */
    fun reconcile(alarms: List<Alarm>, nowMs: Long, zone: ZoneId): AlarmTransition {
        val rings = mutableListOf<AlarmRing>()
        val next = alarms.map { alarm ->
            if (!alarm.enabled) return@map alarm.copy(nextTriggerMs = null)
            val pending = alarm.nextTriggerMs
            if (pending != null && pending <= nowMs && pending != alarm.lastHandledTriggerMs) {
                val overdue = nowMs - pending
                rings += AlarmRing(alarm.id, pending, late = overdue > LATE_THRESHOLD_MS, missed = overdue > LATE_GRACE_MS)
                alarm.handled(pending, nowMs, zone)
            } else {
                alarm.scheduledAfter(nowMs, zone)
            }
        }
        return AlarmTransition(next, rings)
    }
}
