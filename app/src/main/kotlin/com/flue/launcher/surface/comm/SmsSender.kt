package com.flue.launcher.surface.comm

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.flue.launcher.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 发送请求的结果：只表示是否交给了系统，最终是否发出看回执。 */
sealed interface SmsSubmit {
    data class Submitted(val key: String, val parts: Int) : SmsSubmit
    data class Unavailable(val block: CommBlock) : SmsSubmit
    data object Invalid : SmsSubmit
    data object Rejected : SmsSubmit
}

/**
 * 短信发送（R32）：系统分段（长短信），每段一个回执，全部成功才显示已发送，任一段失败显示失败。
 * 是默认短信应用时先写入发件箱、按回执改为已发送／失败；不是默认时系统自己写已发送，
 * 本应用只在界面上显示回执结果。
 */
object SmsSender {
    private const val TAG = "FflueSms"
    const val ACTION_SENT = "com.flue.launcher.comm.SMS_SENT"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_PART = "part"
    private const val EXTRA_URI = "uri"

    private val _states = MutableStateFlow<Map<String, SmsSendState>>(emptyMap())

    /** 本进程发出的短信状态（键是短信库 URI 或内存键）。 */
    val states: StateFlow<Map<String, SmsSendState>> = _states.asStateFlow()

    @Suppress("DEPRECATION")
    private fun manager(context: Context): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    }.getOrNull()

    fun send(context: Context, address: String, body: String): SmsSubmit {
        val to = DialRules.normalize(address)
        if (!DialRules.isDialable(to) || body.isBlank()) return SmsSubmit.Invalid
        val env = CommSystem.environment(context)
        CommCapability.smsSend(env)?.let { return SmsSubmit.Unavailable(it) }
        val sms = manager(context) ?: return SmsSubmit.Unavailable(CommBlock.NO_HARDWARE)
        val parts = runCatching { sms.divideMessage(body) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: arrayListOf(body)
        val uri = if (CommCapability.writesSmsProvider(env)) SmsStore.insertOutbox(context, to, body) else null
        val key = uri?.toString() ?: "mem:${System.nanoTime()}"
        val store = CommStore(context)
        store.writeSend(key, SmsSendTracker(parts.size))
        _states.update { it + (key to SmsSendState.SENDING) }
        val sent = ArrayList<PendingIntent>(parts.size)
        parts.indices.forEach { part ->
            val intent = Intent(context, SmsSentReceiver::class.java)
                .setAction(ACTION_SENT)
                // data 让每段的 PendingIntent 各不相同（extras 不参与比较）。
                .setData(Uri.parse("fflue-sms://sent/${Uri.encode(key)}/$part"))
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_PART, part)
                .putExtra(EXTRA_URI, uri?.toString())
            sent += PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        }
        return try {
            if (parts.size == 1) {
                sms.sendTextMessage(to, null, parts[0], sent[0], null)
            } else {
                sms.sendMultipartTextMessage(to, null, parts, sent, null)
            }
            SmsSubmit.Submitted(key, parts.size)
        } catch (e: Exception) {
            Log.w(TAG, "send rejected", e)
            finish(context, key, uri, SmsSendState.FAILED)
            SmsSubmit.Rejected
        }
    }

    /** 某段的回执；回执可能在新进程里到达，所以进度存在 [CommStore]。 */
    fun onSent(context: Context, intent: Intent, resultCode: Int) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val part = intent.getIntExtra(EXTRA_PART, -1)
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val store = CommStore(context)
        val tracker = store.readSend(key) ?: return
        val state = tracker.report(part, resultCode)
        if (state == SmsSendState.SENDING) {
            store.writeSend(key, tracker)
            return
        }
        finish(context, key, uri, state)
        if (state == SmsSendState.FAILED) {
            CommNotifications.notice(
                context,
                context.getString(R.string.surface_comm_sms_failed_title),
                context.getString(R.string.surface_comm_sms_failed_body),
            )
        }
    }

    private fun finish(context: Context, key: String, uri: Uri?, state: SmsSendState) {
        CommStore(context).clearSend(key)
        if (uri != null) {
            SmsStore.setType(
                context,
                uri,
                if (state == SmsSendState.SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED,
            )
        }
        _states.update { it + (key to state) }
    }
}

/** 发送回执（不导出）。 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSender.ACTION_SENT) return
        SmsSender.onSent(context, intent, resultCode)
    }
}

/**
 * 默认短信应用的收信入口（SMS_DELIVER，系统用 BROADCAST_SMS 保护）。写入系统短信库并通知。
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        val pending = goAsync()
        Thread {
            try {
                val sentAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                SmsRules.combine(messages.map { it?.displayOriginatingAddress to it?.displayMessageBody }).forEach { (address, body) ->
                    val uri = SmsStore.insertInbox(context, address, body, System.currentTimeMillis(), sentAt)
                    val thread = uri?.let { SmsStore.threadOf(context, it) } ?: -1L
                    CommNotifications.incomingSms(context, address, body, thread)
                    SmsEvents.changed()
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * 彩信入口（WAP_PUSH_DELIVER）。成为默认短信应用必须登记；本应用不下载、不显示彩信内容，
 * 收到时如实提示，不假装已接收。
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        CommNotifications.notice(
            context,
            context.getString(R.string.surface_comm_mms_title),
            context.getString(R.string.surface_comm_mms_body),
        )
    }
}

/**
 * 来电“用短信回复”（RESPOND_VIA_MESSAGE），系统用 SEND_RESPOND_VIA_MESSAGE 保护。无界面。
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val recipients = SmsRules.recipients(intent.data?.schemeSpecificPart)
            if (text.isNotBlank()) recipients.forEach { SmsSender.send(this, it, text) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** 短信库变化的简单通知（本进程内），会话页据此刷新。 */
object SmsEvents {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun changed() {
        _version.update { it + 1 }
    }
}
