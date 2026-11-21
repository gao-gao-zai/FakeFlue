package com.flue.launcher.surface.tools.alert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import com.flue.launcher.i18n.FlueLocaleManager
import com.flue.launcher.surface.tools.AlarmToolActivity
import com.flue.launcher.surface.tools.ToolActivity
import com.flue.launcher.surface.tools.ToolAlertsEngine
import com.flue.launcher.surface.tools.ToolNotifier
import com.flue.launcher.surface.tools.TimerToolActivity
import com.flue.launcher.surface.tools.alarm.Alarm
import com.flue.launcher.surface.tools.alarm.AlarmRing
import com.flue.launcher.surface.tools.timer.TimerState
import java.util.Calendar

/**
 * 到点提醒的通知（R31）。响铃用闹钟音频类别、持续响到关闭或 [ToolAlertsEngine.RING_TIMEOUT_MS]；
 * 错过的闹钟只发一条普通通知告知，不再响铃。
 *
 * 发不出通知（Android 13+ 未允许通知、用户关闭了本应用通知或提醒频道）时 [canNotify] 为假，
 * 界面据此说明到点无法提醒，不假装能提醒。
 */
class AndroidToolNotifier(context: Context) : ToolNotifier {
    private val app = context.applicationContext
    private val manager = NotificationManagerCompat.from(app)

    private fun localized(): Context = FlueLocaleManager.applyLocale(app)

    override fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_RING)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureChannels(ctx: Context) {
        if (manager.getNotificationChannel(CHANNEL_RING) == null) {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RING, ctx.getString(R.string.surface_tool_channel_ring), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.surface_tool_channel_ring_desc)
                    setSound(
                        sound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_MISSED) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MISSED, ctx.getString(R.string.surface_tool_channel_missed), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun post(id: Int, notification: Notification) {
        if (!canNotify()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun activityIntent(cls: Class<out ToolActivity>, requestCode: Int, fromAlert: Boolean): PendingIntent =
        PendingIntent.getActivity(
            app,
            requestCode,
            Intent(app, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ToolActivity.EXTRA_FROM_ALERT, fromAlert),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun receiverIntent(intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun ringing(ctx: Context, builder: NotificationCompat.Builder, fullScreen: PendingIntent): Notification =
        builder
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(ToolAlertsEngine.RING_TIMEOUT_MS)
            .setFullScreenIntent(fullScreen, true)
            .setVibrate(VIBRATION)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }

    override fun notifyTimer(state: TimerState.Fired) {
        val ctx = localized()
        ensureChannels(ctx)
        val content = activityIntent(TimerToolActivity::class.java, REQUEST_TIMER, fromAlert = false)
        val dismiss = receiverIntent(ToolAlertReceiver.timerDismissIntent(app))
        val text = ctx.getString(R.string.surface_tool_timer_notify_text, formatDuration(state.durationMs)) +
            if (state.late) ctx.getString(R.string.surface_tool_late_suffix) else ""
        val builder = NotificationCompat.Builder(ctx, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_surface_tool_timer_small)
            .setContentTitle(ctx.getString(R.string.surface_tool_timer_notify_title))
            .setContentText(text)
            .setContentIntent(content)
            .setDeleteIntent(dismiss)
            .addAction(0, ctx.getString(R.string.surface_tool_dismiss), dismiss)
        post(NOTIFICATION_TIMER, ringing(ctx, builder, activityIntent(TimerToolActivity::class.java, REQUEST_TIMER_FULL, true)))
    }

    override fun cancelTimer() {
        runCatching { manager.cancel(NOTIFICATION_TIMER) }
    }

    override fun notifyAlarm(alarm: Alarm, ring: AlarmRing) {
        val ctx = localized()
        ensureChannels(ctx)
        val time = formatClock(ctx, alarm.hour, alarm.minute)
        val content = activityIntent(AlarmToolActivity::class.java, REQUEST_ALARM, fromAlert = false)
        val dismiss = receiverIntent(ToolAlertReceiver.alarmDismissIntent(app, alarm.id))
        if (ring.missed) {
            post(
                NOTIFICATION_ALARM_BASE + alarm.id,
                NotificationCompat.Builder(ctx, CHANNEL_MISSED)
                    .setSmallIcon(R.drawable.ic_surface_tool_alarm_small)
                    .setContentTitle(ctx.getString(R.string.surface_tool_alarm_missed_title))
                    .setContentText(ctx.getString(R.string.surface_tool_alarm_missed_text, time))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build(),
            )
            return
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_surface_tool_alarm_small)
            .setContentTitle(ctx.getString(R.string.surface_tool_alarm_notify_title))
            .setContentText(time + if (ring.late) ctx.getString(R.string.surface_tool_late_suffix) else "")
            .setContentIntent(content)
            .setDeleteIntent(dismiss)
            .addAction(0, ctx.getString(R.string.surface_tool_dismiss), dismiss)
        post(
            NOTIFICATION_ALARM_BASE + alarm.id,
            ringing(ctx, builder, activityIntent(AlarmToolActivity::class.java, REQUEST_ALARM_FULL, true)),
        )
    }

    override fun cancelAlarm(alarmId: Int) {
        runCatching { manager.cancel(NOTIFICATION_ALARM_BASE + alarmId) }
    }

    companion object {
        const val CHANNEL_RING = "fflue_tool_ring"
        const val CHANNEL_MISSED = "fflue_tool_missed"
        const val NOTIFICATION_TIMER = 31_000
        const val NOTIFICATION_ALARM_BASE = 31_100
        private const val REQUEST_TIMER = 31_000
        private const val REQUEST_TIMER_FULL = 31_001
        private const val REQUEST_ALARM = 31_002
        private const val REQUEST_ALARM_FULL = 31_003
        private val VIBRATION = longArrayOf(0, 600, 400, 600, 400, 600)

        fun formatDuration(ms: Long): String {
            val total = (ms + 999) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.ROOT, "%02d:%02d", m, s)
        }

        /** 按系统 12／24 小时制显示钟面时间。 */
        fun formatClock(context: Context, hour: Int, minute: Int): String {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            return DateFormat.getTimeFormat(context).format(calendar.time)
        }
    }
}
