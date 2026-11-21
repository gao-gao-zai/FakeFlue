package com.flue.launcher.surface

import com.flue.launcher.surface.tools.alarm.Alarm
import com.flue.launcher.surface.tools.alarm.AlarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/** R31 闹钟：下一次响铃时间（跨天、重复星期、夏令时）与到点去重状态机。 */
class AlarmScheduleTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = shanghai): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 2026-11-20 是星期五。
    private val friday0800 = at(2026, 11, 20, 8, 0)

    @Test
    fun nextTriggerSameDayOrNextDay() {
        assertEquals(DayOfWeek.FRIDAY, LocalDateTime.of(2026, 11, 20, 0, 0).dayOfWeek)
        assertEquals(at(2026, 11, 20, 9, 30), AlarmSchedule.nextTrigger(9, 30, emptySet(), friday0800, shanghai))
        // 已过今天的时刻：跨天到明天。
        assertEquals(at(2026, 11, 21, 7, 0), AlarmSchedule.nextTrigger(7, 0, emptySet(), friday0800, shanghai))
        // 恰好等于当前时刻不算“之后”。
        assertEquals(at(2026, 11, 21, 8, 0), AlarmSchedule.nextTrigger(8, 0, emptySet(), friday0800, shanghai))
        // 跨月、跨年。
        assertEquals(at(2027, 1, 1, 0, 5), AlarmSchedule.nextTrigger(0, 5, emptySet(), at(2026, 12, 31, 23, 59), shanghai))
    }

    @Test
    fun nextTriggerHonoursRepeatDays() {
        val workdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        // 周五 8:00 之后的工作日 7:00 → 下周一。
        assertEquals(at(2026, 11, 23, 7, 0), AlarmSchedule.nextTrigger(7, 0, workdays, friday0800, shanghai))
        // 周五 9:00 当天还没到。
        assertEquals(at(2026, 11, 20, 9, 0), AlarmSchedule.nextTrigger(9, 0, workdays, friday0800, shanghai))
        // 只在周五：今天已过 → 下周五（满一周）。
        assertEquals(at(2026, 11, 27, 7, 0), AlarmSchedule.nextTrigger(7, 0, setOf(DayOfWeek.FRIDAY), friday0800, shanghai))
        assertEquals(at(2026, 11, 22, 10, 0), AlarmSchedule.nextTrigger(10, 0, setOf(DayOfWeek.SUNDAY), friday0800, shanghai))
    }

    @Test
    fun nextTriggerAcrossDaylightSavingGap() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:30 在纽约不存在：顺延到 03:30。
        val before = at(2026, 3, 8, 1, 0, ny)
        val trigger = AlarmSchedule.nextTrigger(2, 30, emptySet(), before, ny)
        assertEquals(at(2026, 3, 8, 3, 30, ny), trigger)
        // 换时区后按当地钟面时间。
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(at(2026, 11, 20, 9, 30, tokyo), AlarmSchedule.nextTrigger(9, 30, emptySet(), at(2026, 11, 20, 8, 0, tokyo), tokyo))
    }

    @Test
    fun oneShotRingsOnceThenTurnsOff() {
        val created = AlarmSchedule.create(emptyList(), 9, 0, emptySet(), friday0800, shanghai).alarms
        val alarm = created.single()
        assertEquals(1, alarm.id)
        val trigger = alarm.nextTriggerMs!!
        assertEquals(at(2026, 11, 20, 9, 0), trigger)

        // 早到一小时：不响。
        assertTrue(AlarmSchedule.onFire(created, 1, trigger, trigger - 3_600_000, shanghai).rings.isEmpty())
        // 时刻对不上（改过时间后的旧登记）：不响。
        assertTrue(AlarmSchedule.onFire(created, 1, trigger - 60_000, trigger, shanghai).rings.isEmpty())
        // 不存在的闹钟：不响。
        assertTrue(AlarmSchedule.onFire(created, 99, trigger, trigger, shanghai).rings.isEmpty())

        val fired = AlarmSchedule.onFire(created, 1, trigger, trigger + 2_000, shanghai)
        assertEquals(1, fired.rings.size)
        assertFalse(fired.rings.single().late)
        val after = fired.alarms.single()
        assertFalse(after.enabled)
        assertNull(after.nextTriggerMs)
        assertEquals(trigger, after.lastHandledTriggerMs)
        // 重复送达、随后复算都不再响。
        assertTrue(AlarmSchedule.onFire(fired.alarms, 1, trigger, trigger + 3_000, shanghai).rings.isEmpty())
        assertTrue(AlarmSchedule.reconcile(fired.alarms, trigger + 4_000, shanghai).rings.isEmpty())
    }

    @Test
    fun repeatingAlarmMovesToNextOccurrence() {
        val daily = (1..7).map(DayOfWeek::of).toSet()
        val alarms = AlarmSchedule.create(emptyList(), 9, 0, daily, friday0800, shanghai).alarms
        val trigger = alarms.single().nextTriggerMs!!
        val fired = AlarmSchedule.onFire(alarms, 1, trigger, trigger + 500, shanghai)
        assertEquals(1, fired.rings.size)
        val next = fired.alarms.single()
        assertTrue(next.enabled)
        assertEquals(at(2026, 11, 21, 9, 0), next.nextTriggerMs)
        // 时钟被往回拨到响过之前：不会把同一次再排一遍。
        val rewound = AlarmSchedule.reconcile(fired.alarms, trigger - 600_000, shanghai)
        assertTrue(rewound.rings.isEmpty())
        assertEquals(at(2026, 11, 21, 9, 0), rewound.alarms.single().nextTriggerMs)
    }

    @Test
    fun reconcileRingsLateOrReportsMissedExactlyOnce() {
        val a = Alarm(1, 9, 0, nextTriggerMs = at(2026, 11, 20, 9, 0))
        val b = Alarm(2, 6, 0, days = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), nextTriggerMs = at(2026, 11, 20, 6, 0))
        val c = Alarm(3, 23, 0, nextTriggerMs = at(2026, 11, 20, 23, 0))
        val now = at(2026, 11, 20, 9, 5)
        val result = AlarmSchedule.reconcile(listOf(a, b, c), now, shanghai)
        val rings = result.rings.associateBy { it.alarmId }
        // 晚 5 分钟（在补响宽限内）：补响。
        assertTrue(rings.getValue(1).late)
        assertFalse(rings.getValue(1).missed)
        // 晚 3 小时：只告知错过；重复闹钟排到下一次。
        assertTrue(rings.getValue(2).missed)
        assertEquals(at(2026, 11, 21, 6, 0), result.alarms.first { it.id == 2 }.nextTriggerMs)
        // 未到期的不动。
        assertFalse(3 in rings)
        assertEquals(at(2026, 11, 20, 23, 0), result.alarms.first { it.id == 3 }.nextTriggerMs)
        assertFalse(result.alarms.first { it.id == 1 }.enabled)
        // 幂等：再复算不再提醒。
        assertTrue(AlarmSchedule.reconcile(result.alarms, now + 1_000, shanghai).rings.isEmpty())
    }

    @Test
    fun reconcileFollowsTimeZoneChange() {
        val alarms = AlarmSchedule.create(emptyList(), 9, 0, emptySet(), friday0800, shanghai).alarms
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 北京 8:00 = 东京 9:00：东京当天 9:00 已到（不严格晚于），排到明天东京 9:00。
        val moved = AlarmSchedule.reconcile(alarms, friday0800, tokyo).alarms.single()
        assertEquals(at(2026, 11, 21, 9, 0, tokyo), moved.nextTriggerMs)
    }

    @Test
    fun editToggleDeleteAndLimit() {
        var alarms = AlarmSchedule.create(emptyList(), 9, 0, emptySet(), friday0800, shanghai).alarms
        alarms = AlarmSchedule.setEnabled(alarms, 1, false, friday0800, shanghai).alarms
        assertNull(alarms.single().nextTriggerMs)
        alarms = AlarmSchedule.update(alarms, 1, 10, 15, emptySet(), friday0800, shanghai).alarms
        assertTrue(alarms.single().enabled)
        assertEquals(at(2026, 11, 20, 10, 15), alarms.single().nextTriggerMs)
        alarms = AlarmSchedule.create(alarms, 7, 0, emptySet(), friday0800, shanghai).alarms
        assertEquals(listOf(1, 2), alarms.map { it.id })
        assertEquals(listOf(2), AlarmSchedule.delete(alarms, 1).alarms.map { it.id })

        var full = emptyList<Alarm>()
        repeat(AlarmSchedule.MAX_ALARMS + 3) { full = AlarmSchedule.create(full, 7, it % 60, emptySet(), friday0800, shanghai).alarms }
        assertEquals(AlarmSchedule.MAX_ALARMS, full.size)
    }
}
