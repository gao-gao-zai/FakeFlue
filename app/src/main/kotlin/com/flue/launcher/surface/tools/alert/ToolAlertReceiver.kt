package com.flue.launcher.surface.tools.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flue.launcher.surface.tools.ToolAlerts

/**
 * 工具提醒的唯一接收器（R31），不导出：到点与关闭按钮是本应用的显式 PendingIntent；
 * 开机、改时间、改时区、应用更新、精确定时许可变化由系统发送（系统 uid 不受 exported 限制），
 * 收到后从持久状态复算、重新登记。所有处理都是幂等的，同一次提醒只响一次。
 */
class ToolAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = ToolAlerts.engine(context)
        when (intent.action) {
            ACTION_TIMER_FIRE -> engine.timerDue(intent.getLongExtra(EXTRA_RUN_ID, -1L))
            ACTION_ALARM_FIRE -> engine.alarmFired(intent.getIntExtra(EXTRA_ALARM_ID, -1), intent.getLongExtra(EXTRA_TRIGGER, -1L))
            ACTION_TIMER_DISMISS -> engine.dismissTimer()
            ACTION_ALARM_DISMISS -> engine.dismissAlarm(intent.getIntExtra(EXTRA_ALARM_ID, -1))
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_ALARM_STATE_CHANGED,
            -> engine.reconcile()
        }
    }

    companion object {
        const val ACTION_TIMER_FIRE = "com.flue.launcher.surface.tools.TIMER_FIRE"
        const val ACTION_ALARM_FIRE = "com.flue.launcher.surface.tools.ALARM_FIRE"
        const val ACTION_TIMER_DISMISS = "com.flue.launcher.surface.tools.TIMER_DISMISS"
        const val ACTION_ALARM_DISMISS = "com.flue.launcher.surface.tools.ALARM_DISMISS"

        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`（API 31）。 */
        const val ACTION_EXACT_ALARM_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TRIGGER = "trigger"

        fun timerFireIntent(context: Context, runId: Long): Intent =
            Intent(context, ToolAlertReceiver::class.java)
                .setAction(ACTION_TIMER_FIRE)
                .setData(AndroidToolScheduler.timerUri())
                .putExtra(EXTRA_RUN_ID, runId)

        fun alarmFireIntent(context: Context, alarmId: Int, triggerMs: Long): Intent =
            Intent(context, ToolAlertReceiver::class.java)
                .setAction(ACTION_ALARM_FIRE)
                .setData(AndroidToolScheduler.alarmUri(alarmId))
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TRIGGER, triggerMs)

        fun timerDismissIntent(context: Context): Intent =
            Intent(context, ToolAlertReceiver::class.java)
                .setAction(ACTION_TIMER_DISMISS)
                .setData(AndroidToolScheduler.timerUri())

        fun alarmDismissIntent(context: Context, alarmId: Int): Intent =
            Intent(context, ToolAlertReceiver::class.java)
                .setAction(ACTION_ALARM_DISMISS)
                .setData(AndroidToolScheduler.alarmUri(alarmId))
                .putExtra(EXTRA_ALARM_ID, alarmId)
    }
}
