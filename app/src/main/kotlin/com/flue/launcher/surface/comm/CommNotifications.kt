package com.flue.launcher.surface.comm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flue.launcher.R

/**
 * 通话与短信通知（R32）。来电用全屏通知兜底（锁屏、熄屏时直接显示来电界面）；
 * 通话中保留一条“回到通话”的通知，离开通话界面不挂断。
 */
object CommNotifications {
    const val CHANNEL_INCOMING = "fflue_comm_incoming_call"
    const val CHANNEL_ONGOING = "fflue_comm_ongoing_call"
    const val CHANNEL_SMS = "fflue_comm_sms"
    const val CHANNEL_NOTICE = "fflue_comm_notice"

    private const val ID_CALL = 32_001
    private const val ID_NOTICE = 32_002
    private const val ID_SMS_BASE = 32_100

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.surface_comm_channel_incoming), NotificationManager.IMPORTANCE_HIGH)
                // 系统负责响铃，本通知不再出声。
                .apply { setSound(null, null) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.surface_comm_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SMS, context.getString(R.string.surface_comm_channel_sms), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTICE, context.getString(R.string.surface_comm_channel_notice), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun callAction(context: Context, action: String, id: Int, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_ID, id),
            flags(),
        )

    @android.annotation.SuppressLint("MissingPermission")
    fun updateCall(context: Context, calls: List<CallSnapshot>) {
        val nm = NotificationManagerCompat.from(context)
        val live = calls.filter { it.phase.live }
        if (live.isEmpty()) {
            nm.cancel(ID_CALL)
            return
        }
        if (!canPost(context)) return
        ensureChannels(context)
        val primary = live[CallRules.primaryIndex(live.map { it.phase })]
        val open = PendingIntent.getActivity(
            context,
            0,
            InCallActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )
        val who = primary.name ?: primary.number ?: context.getString(R.string.surface_comm_unknown_number)
        val builder = if (primary.phase == CallPhase.RINGING) {
            NotificationCompat.Builder(context, CHANNEL_INCOMING)
                .setContentTitle(context.getString(R.string.surface_comm_call_incoming))
                .setContentText(who)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(open, true)
                .addAction(0, context.getString(R.string.surface_comm_call_reject), callAction(context, CallActionReceiver.ACTION_REJECT, primary.id, 1))
                .addAction(
                    0,
                    context.getString(R.string.surface_comm_call_answer),
                    // 接听要同时打开界面：用 Activity 的 PendingIntent，不经广播跳板。
                    PendingIntent.getActivity(
                        context,
                        2,
                        InCallActivity.answerIntent(context, primary.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        flags(),
                    ),
                )
        } else {
            NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setContentTitle(context.getString(R.string.surface_comm_call_ongoing))
                .setContentText(context.getString(R.string.surface_comm_call_return_fmt, who))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setUsesChronometer(primary.connectTimeMillis > 0)
                .setWhen(if (primary.connectTimeMillis > 0) primary.connectTimeMillis else System.currentTimeMillis())
                .addAction(0, context.getString(R.string.surface_comm_call_hang_up), callAction(context, CallActionReceiver.ACTION_HANG_UP, primary.id, 3))
        }
        builder.setSmallIcon(R.drawable.ic_comm_call_glyph)
            .setOngoing(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        runCatching { nm.notify(ID_CALL, builder.build()) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun incomingSms(context: Context, address: String, body: String, threadId: Long) {
        if (!canPost(context)) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            threadId.toInt(),
            MessagesActivity.threadIntent(context, threadId, address).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_comm_sms_glyph)
            .setContentTitle(address)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_SMS_BASE + (threadId % 1000).toInt(), n) }
    }

    fun cancelThread(context: Context, threadId: Long) {
        NotificationManagerCompat.from(context).cancel(ID_SMS_BASE + (threadId % 1000).toInt())
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun notice(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_NOTICE)
            .setSmallIcon(R.drawable.ic_comm_sms_glyph)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_NOTICE, n) }
    }
}

/** 通知上的拒接、挂断。只接收本应用的 PendingIntent（不导出）。 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            ACTION_REJECT -> CallRegistry.reject(id)
            ACTION_HANG_UP -> CallRegistry.hangUp(id)
        }
    }

    companion object {
        const val EXTRA_ID = "com.flue.launcher.extra.CALL_ID"
        const val ACTION_REJECT = "com.flue.launcher.comm.REJECT"
        const val ACTION_HANG_UP = "com.flue.launcher.comm.HANG_UP"
    }
}
