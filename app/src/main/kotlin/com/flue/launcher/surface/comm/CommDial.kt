package com.flue.launcher.surface.comm

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.SurfaceMode

data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val durationSec: Long,
)

/** 通话记录（系统 CallLog，需要通话记录读取权限）。业务数据两种模式共用（R06）。 */
object CallLogReader {
    fun recent(context: Context, limit: Int = 60): List<CallLogEntry> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val out = ArrayList<CallLogEntry>()
                while (c.moveToNext() && out.size < limit) {
                    out += CallLogEntry(
                        id = c.getLong(0),
                        number = c.getString(1).orEmpty(),
                        name = c.getString(2)?.takeIf { it.isNotBlank() },
                        type = c.getInt(3),
                        date = c.getLong(4),
                        durationSec = c.getLong(5),
                    )
                }
                out
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun typeLabel(type: Int): Int = when (type) {
        CallLog.Calls.INCOMING_TYPE -> R.string.surface_comm_log_incoming
        CallLog.Calls.OUTGOING_TYPE -> R.string.surface_comm_log_outgoing
        CallLog.Calls.MISSED_TYPE -> R.string.surface_comm_log_missed
        CallLog.Calls.REJECTED_TYPE -> R.string.surface_comm_log_rejected
        CallLog.Calls.BLOCKED_TYPE -> R.string.surface_comm_log_blocked
        else -> R.string.surface_comm_log_other
    }
}

enum class DialOutcome { PLACED, HANDED_TO_SYSTEM, INVALID, FAILED }

/** 拨号：可用时经 Telecom 拨出；紧急号码、能力不足时交给系统电话界面（R32）。 */
object CommDial {
    fun dial(context: Context, raw: String): DialOutcome {
        val number = DialRules.normalize(raw)
        val env = CommSystem.environment(context)
        return when (DialRules.route(number, CommSystem.isEmergency(context, number), env)) {
            DialRoute.INVALID -> DialOutcome.INVALID
            DialRoute.PLACE_CALL -> {
                val telecom = context.getSystemService(TelecomManager::class.java)
                val placed = telecom != null && runCatching {
                    telecom.placeCall(Uri.fromParts("tel", number, null), null)
                }.isSuccess
                if (placed) DialOutcome.PLACED else handToSystem(context, number)
            }
            DialRoute.SYSTEM_DIALER -> handToSystem(context, number)
        }
    }

    fun handToSystem(context: Context, number: String): DialOutcome {
        val pkg = CommSystem.systemDialerPackage(context) ?: return DialOutcome.FAILED
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            DialOutcome.HANDED_TO_SYSTEM
        } catch (_: ActivityNotFoundException) {
            DialOutcome.FAILED
        } catch (_: SecurityException) {
            DialOutcome.FAILED
        }
    }
}

/**
 * 能力不足时的说明文字。伪装模式下不出现权限、授权字样（R28），只说明结果；
 * 完整模式说明原因和设置位置。
 */
object CommBlockText {
    fun forDial(block: CommBlock, disguise: Boolean): Int = when (block) {
        CommBlock.NO_HARDWARE -> R.string.surface_comm_block_no_hardware
        CommBlock.NO_SIM -> R.string.surface_comm_block_no_sim
        else -> if (disguise) R.string.surface_comm_block_dial_system else R.string.surface_comm_block_dial_permission
    }

    fun forInCall(block: CommBlock, disguise: Boolean): Int = when (block) {
        CommBlock.NO_HARDWARE -> R.string.surface_comm_block_no_hardware
        CommBlock.ROLE_UNAVAILABLE -> if (disguise) R.string.surface_comm_block_incall_system else R.string.surface_comm_block_role_unavailable
        else -> if (disguise) R.string.surface_comm_block_incall_system else R.string.surface_comm_block_not_default_dialer
    }

    fun forCallLog(block: CommBlock, disguise: Boolean): Int = when (block) {
        CommBlock.NO_HARDWARE -> R.string.surface_comm_block_no_hardware
        else -> if (disguise) R.string.surface_comm_block_log_unavailable else R.string.surface_comm_block_log_permission
    }

    fun forSmsRead(block: CommBlock, disguise: Boolean): Int = when (block) {
        CommBlock.NO_HARDWARE -> R.string.surface_comm_block_no_hardware
        else -> if (disguise) R.string.surface_comm_block_sms_unavailable else R.string.surface_comm_block_sms_read_permission
    }

    fun forSmsSend(block: CommBlock, disguise: Boolean): Int = when (block) {
        CommBlock.NO_HARDWARE -> R.string.surface_comm_block_no_hardware
        CommBlock.NO_SIM -> R.string.surface_comm_block_no_sim
        else -> if (disguise) R.string.surface_comm_block_sms_unavailable else R.string.surface_comm_block_sms_send_permission
    }

    fun forSmsReceive(block: CommBlock, disguise: Boolean): Int = when (block) {
        CommBlock.NO_HARDWARE -> R.string.surface_comm_block_no_hardware
        CommBlock.ROLE_UNAVAILABLE -> if (disguise) R.string.surface_comm_block_sms_receive_system else R.string.surface_comm_block_role_unavailable
        else -> if (disguise) R.string.surface_comm_block_sms_receive_system else R.string.surface_comm_block_not_default_sms
    }

    fun disguise(context: Context): Boolean =
        SurfaceRuntime.get(context).controller.mode.value == SurfaceMode.DISGUISE
}
