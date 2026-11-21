package com.flue.launcher.surface.comm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony

data class SmsRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean,
) {
    val outgoing: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}

data class SmsThread(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Int,
)

/**
 * 系统短信库的读写（R32）。读取需要短信读取权限；只有默认短信应用可以写入
 * （收到的短信、本应用发出的短信及其状态）。业务数据两种模式共用（R06）。
 */
object SmsStore {
    private val PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
    )

    private fun Cursor.row(): SmsRow = SmsRow(
        id = getLong(0),
        threadId = getLong(1),
        address = getString(2).orEmpty(),
        body = getString(3).orEmpty(),
        date = getLong(4),
        type = getInt(5),
        read = getInt(6) != 0,
    )

    private fun query(context: Context, selection: String?, args: Array<String>?, order: String, limit: Int): List<SmsRow> =
        runCatching {
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, selection, args, order)?.use { c ->
                val out = ArrayList<SmsRow>()
                while (c.moveToNext() && out.size < limit) out += c.row()
                out
            }.orEmpty()
        }.getOrDefault(emptyList())

    /** 会话列表：按最新一条排序。 */
    fun threads(context: Context, scanLimit: Int = 1_000): List<SmsThread> =
        groupThreads(query(context, null, null, "${Telephony.Sms.DATE} DESC", scanLimit))

    fun groupThreads(rowsNewestFirst: List<SmsRow>): List<SmsThread> =
        rowsNewestFirst.groupBy { it.threadId }.map { (threadId, rows) ->
            val latest = rows.first()
            SmsThread(
                threadId = threadId,
                address = rows.firstOrNull { it.address.isNotBlank() }?.address.orEmpty(),
                snippet = latest.body,
                date = latest.date,
                unread = rows.count { !it.read && !it.outgoing },
            )
        }.sortedByDescending { it.date }

    /** 一个会话的消息，按时间从旧到新。 */
    fun thread(context: Context, threadId: Long, limit: Int = 500): List<SmsRow> =
        query(context, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC", limit).reversed()

    /** 按号码找已有会话（不新建）。 */
    fun threadIdFor(context: Context, address: String): Long? {
        val wanted = DialRules.normalize(address)
        return query(context, null, null, "${Telephony.Sms.DATE} DESC", 1_000)
            .firstOrNull { DialRules.normalize(it.address) == wanted }?.threadId
    }

    fun insertInbox(context: Context, address: String, body: String, date: Long, dateSent: Long): Uri? = runCatching {
        context.contentResolver.insert(
            Telephony.Sms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, dateSent)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            },
        )
    }.getOrNull()

    fun insertOutbox(context: Context, address: String, body: String): Uri? = runCatching {
        context.contentResolver.insert(
            Telephony.Sms.Outbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            },
        )
    }.getOrNull()

    fun setType(context: Context, uri: Uri, type: Int) {
        runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null)
        }
    }

    fun threadOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }.getOrNull()

    fun markThreadRead(context: Context, threadId: Long) {
        runCatching {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                },
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        }
    }
}
