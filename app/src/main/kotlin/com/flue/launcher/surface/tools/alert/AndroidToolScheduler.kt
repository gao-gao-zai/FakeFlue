package com.flue.launcher.surface.tools.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.flue.launcher.surface.tools.AlarmToolActivity
import com.flue.launcher.surface.tools.ToolAlarmScheduler

/**
 * AlarmManager 登记（R31）。精确定时可用时：闹钟用 `setAlarmClock`（系统显示为下一个闹钟，
 * 打盹模式也准点），计时器用 `setExactAndAllowWhileIdle`；Android 12+ 未获精确定时许可时降级为
 * `setAndAllowWhileIdle`，系统可能推迟，界面据 [canScheduleExact] 如实说明。
 *
 * 每个登记由 Intent 的 data 区分（计时器一个、每个闹钟一个），重复登记替换旧登记。
 */
class AndroidToolScheduler(context: Context) : ToolAlarmScheduler {
    private val app = context.applicationContext
    private val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)

    override fun scheduleTimer(runId: Long, endElapsedMs: Long) {
        val pending = broadcast(ToolAlertReceiver.timerFireIntent(app, runId))
        val exact = canScheduleExact() && runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsedMs, pending)
        }.isSuccess
        if (!exact) runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsedMs, pending) }
    }

    override fun cancelTimer() {
        cancel(ToolAlertReceiver.timerFireIntent(app, 0L))
    }

    override fun scheduleAlarm(alarmId: Int, triggerWallMs: Long) {
        val pending = broadcast(ToolAlertReceiver.alarmFireIntent(app, alarmId, triggerWallMs))
        val exact = canScheduleExact() && runCatching {
            val show = PendingIntent.getActivity(
                app,
                0,
                Intent(app, AlarmToolActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerWallMs, show), pending)
        }.isSuccess
        if (!exact) runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerWallMs, pending) }
    }

    override fun cancelAlarm(alarmId: Int) {
        cancel(ToolAlertReceiver.alarmFireIntent(app, alarmId, 0L))
    }

    private fun broadcast(intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(intent: Intent) {
        val pending = PendingIntent.getBroadcast(
            app,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        runCatching { alarmManager.cancel(pending) }
        pending.cancel()
    }

    companion object {
        internal fun timerUri(): Uri = Uri.parse("fflue-tool://timer")
        internal fun alarmUri(id: Int): Uri = Uri.parse("fflue-tool://alarm/$id")
    }
}
