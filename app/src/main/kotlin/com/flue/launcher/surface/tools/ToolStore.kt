package com.flue.launcher.surface.tools

import android.content.Context
import android.content.SharedPreferences
import com.flue.launcher.surface.tools.alarm.Alarm
import com.flue.launcher.surface.tools.stopwatch.StopwatchState
import com.flue.launcher.surface.tools.timer.TimerState
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** 正在响的闹钟（通知已发出、尚未关闭）。超过 [ToolAlertsEngine.RING_TIMEOUT_MS] 自动视为结束。 */
data class RingingAlarm(val alarmId: Int, val triggerMs: Long, val sinceWallMs: Long)

/**
 * 工具业务数据（R06：闹钟、计时等业务数据完整／伪装共用，不按模式复制）。
 *
 * 同步 SharedPreferences，写入用 commit：到点标记必须在发出提醒前落盘，进程随后被杀也不会重复响。
 * 读不出的条目当作没有，不崩溃；JSON 结构由本类独占。
 */
class ToolStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadTimer(): TimerState = runCatching { prefs.getString(KEY_TIMER, null)?.let { timerFrom(JSONObject(it)) } }
        .getOrNull() ?: TimerState.Idle()

    /**
     * 读不出的闹钟列表（整体损坏或个别条目损坏）仍按「没有」处理以免崩溃，但先把原始值
     * 原样复制到 [unreadableAlarmsKey] 下（R37）：否则下一次保存就会把用户的闹钟永久覆盖掉。
     * 同一份原始值只复制一次。
     */
    fun loadAlarms(): List<Alarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        var lossy = false
        val alarms = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { alarmFrom(array.getJSONObject(index)) }.getOrNull().also { if (it == null) lossy = true }
            }
        }.getOrElse {
            lossy = true
            emptyList()
        }
        if (lossy) preserveUnreadable(KEY_ALARMS, raw)
        return alarms
    }

    /** 已保留下来的读不出的闹钟原始值（供诊断 / 人工恢复）。 */
    fun unreadableAlarmBackups(): List<String> = prefs.all
        .filterKeys { it.startsWith(unreadablePrefix(KEY_ALARMS)) }
        .values
        .filterIsInstance<String>()

    private fun preserveUnreadable(key: String, raw: String) {
        val backupKey = unreadablePrefix(key) + sha256Prefix(raw)
        if (prefs.contains(backupKey)) return
        prefs.edit().putString(backupKey, raw).commit()
    }

    fun loadStopwatch(): StopwatchState = runCatching {
        prefs.getString(KEY_STOPWATCH, null)?.let { stopwatchFrom(JSONObject(it)) }
    }.getOrNull() ?: StopwatchState()

    fun loadRinging(): List<RingingAlarm> = runCatching {
        val array = JSONArray(prefs.getString(KEY_RINGING, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            RingingAlarm(o.getInt("id"), o.getLong("trigger"), o.getLong("since"))
        }
    }.getOrDefault(emptyList())

    /** 单调递增的计时器运行编号；在发出新登记前先落盘。 */
    fun nextRunId(): Long {
        val next = prefs.getLong(KEY_RUN_ID, 0L) + 1
        prefs.edit().putLong(KEY_RUN_ID, next).commit()
        return next
    }

    fun saveTimer(state: TimerState): Boolean = prefs.edit().putString(KEY_TIMER, timerJson(state).toString()).commit()

    fun saveAlarms(alarms: List<Alarm>): Boolean =
        prefs.edit().putString(KEY_ALARMS, JSONArray(alarms.map(::alarmJson)).toString()).commit()

    fun saveStopwatch(state: StopwatchState): Boolean =
        prefs.edit().putString(KEY_STOPWATCH, stopwatchJson(state).toString()).commit()

    fun saveRinging(list: List<RingingAlarm>): Boolean = prefs.edit().putString(
        KEY_RINGING,
        JSONArray(list.map { JSONObject().put("id", it.alarmId).put("trigger", it.triggerMs).put("since", it.sinceWallMs) }).toString(),
    ).commit()

    companion object {
        const val PREFS = "fflue_surface_tools"
        private const val KEY_TIMER = "timer"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_STOPWATCH = "stopwatch"
        private const val KEY_RINGING = "ringing_alarms"
        private const val KEY_RUN_ID = "timer_run_id"

        internal fun unreadablePrefix(key: String) = "$key.unreadable."

        private fun sha256Prefix(raw: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

        private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
        private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null

        private fun bootJson(boot: BootSession) = JSONObject().put("count", boot.bootCount ?: JSONObject.NULL).put("wall", boot.bootWallMs)
        private fun bootFrom(o: JSONObject) = BootSession(o.optIntOrNull("count"), o.getLong("wall"))

        internal fun timerJson(state: TimerState): JSONObject {
            val o = JSONObject().put("duration", state.durationMs)
            when (state) {
                is TimerState.Idle -> o.put("type", "idle")
                is TimerState.Running -> o.put("type", "running").put("run", state.runId).put("endElapsed", state.endElapsedMs)
                    .put("endWall", state.endWallMs).put("boot", bootJson(state.boot)).put("estimated", state.estimatedAfterReboot)
                is TimerState.Paused -> o.put("type", "paused").put("remaining", state.remainingMs)
                is TimerState.Fired -> o.put("type", "fired").put("run", state.runId).put("firedAt", state.firedAtWallMs)
                    .put("late", state.late)
            }
            return o
        }

        internal fun timerFrom(o: JSONObject): TimerState {
            val duration = o.getLong("duration")
            return when (o.getString("type")) {
                "running" -> TimerState.Running(
                    duration, o.getLong("run"), o.getLong("endElapsed"), o.getLong("endWall"),
                    bootFrom(o.getJSONObject("boot")), o.optBoolean("estimated", false),
                )
                "paused" -> TimerState.Paused(duration, o.getLong("remaining"))
                "fired" -> TimerState.Fired(duration, o.getLong("run"), o.getLong("firedAt"), o.optBoolean("late", false))
                else -> TimerState.Idle(duration)
            }
        }

        internal fun alarmJson(alarm: Alarm): JSONObject = JSONObject()
            .put("id", alarm.id)
            .put("hour", alarm.hour)
            .put("minute", alarm.minute)
            .put("days", JSONArray(alarm.days.sorted().map { it.value }))
            .put("enabled", alarm.enabled)
            .put("next", alarm.nextTriggerMs ?: JSONObject.NULL)
            .put("handled", alarm.lastHandledTriggerMs ?: JSONObject.NULL)

        internal fun alarmFrom(o: JSONObject): Alarm {
            val days = o.optJSONArray("days")
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour").coerceIn(0, 23),
                minute = o.getInt("minute").coerceIn(0, 59),
                days = if (days == null) emptySet() else (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                enabled = o.optBoolean("enabled", true),
                nextTriggerMs = o.optLongOrNull("next"),
                lastHandledTriggerMs = o.optLongOrNull("handled"),
            )
        }

        internal fun stopwatchJson(state: StopwatchState): JSONObject = JSONObject()
            .put("accumulated", state.accumulatedMs)
            .put("since", state.runningSinceElapsedMs ?: JSONObject.NULL)
            .put("boot", state.boot?.let(::bootJson) ?: JSONObject.NULL)
            .put("laps", JSONArray(state.laps))
            .put("rebootReset", state.resetByReboot)

        internal fun stopwatchFrom(o: JSONObject): StopwatchState {
            val laps = o.optJSONArray("laps")
            return StopwatchState(
                accumulatedMs = o.getLong("accumulated"),
                runningSinceElapsedMs = o.optLongOrNull("since"),
                boot = o.optJSONObject("boot")?.let(::bootFrom),
                laps = if (laps == null) emptyList() else (0 until laps.length()).map { laps.getLong(it) },
                resetByReboot = o.optBoolean("rebootReset", false),
            )
        }
    }
}
