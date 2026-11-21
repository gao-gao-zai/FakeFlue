package com.flue.launcher.surface.comm

import android.content.Context
import android.content.SharedPreferences

/**
 * 通信角色记录、恢复进度和短信发送回执的设备级存储（R06 原系统状态属于设备级）。
 * 不随模式切换；写入用 `commit()`，打开系统选择之前先确认落盘。
 */
class CommStore(private val prefs: SharedPreferences) : RoleLedgerStorage, RestoreProgressStorage {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    override fun read(role: CommRole): RoleRecord {
        val recorded = prefs.getBoolean(key(role, "recorded"), false)
        if (!recorded) return RoleRecord.NONE
        return RoleRecord(recorded = true, original = prefs.getString(key(role, "original"), null)?.takeIf { it.isNotBlank() })
    }

    override fun write(role: CommRole, record: RoleRecord): Boolean = prefs.edit()
        .putBoolean(key(role, "recorded"), record.recorded)
        .apply { if (record.original != null) putString(key(role, "original"), record.original) else remove(key(role, "original")) }
        .commit()

    override fun readProgress(): RestoreProgress {
        if (!prefs.getBoolean(P_ACTIVE, false)) return RestoreProgress.IDLE
        fun roles(k: String) = prefs.getStringSet(k, null).orEmpty().mapNotNull { n -> CommRole.entries.firstOrNull { it.name == n } }.toSet()
        fun roleMap(prefix: String) = CommRole.entries.mapNotNull { r -> prefs.getString("$prefix${r.name}", null)?.let { r to it } }.toMap()
        return RestoreProgress(
            active = true,
            roles = roles(P_ROLES),
            done = roles(P_DONE),
            skipped = roles(P_SKIPPED),
            chosen = roleMap(P_CHOSEN),
            failed = roleMap(P_FAILED).mapNotNull { (r, v) -> RestoreOutcome.entries.firstOrNull { it.name == v }?.let { r to it } }.toMap(),
            attempted = roleMap(P_ATTEMPTED),
            awaiting = prefs.getString(P_AWAITING, null)?.let { n -> CommRole.entries.firstOrNull { it.name == n } },
        )
    }

    override fun writeProgress(progress: RestoreProgress): Boolean {
        val e = prefs.edit()
        e.putBoolean(P_ACTIVE, progress.active)
        e.putStringSet(P_ROLES, progress.roles.map { it.name }.toSet())
        e.putStringSet(P_DONE, progress.done.map { it.name }.toSet())
        e.putStringSet(P_SKIPPED, progress.skipped.map { it.name }.toSet())
        CommRole.entries.forEach { r ->
            progress.chosen[r]?.let { e.putString("$P_CHOSEN${r.name}", it) } ?: e.remove("$P_CHOSEN${r.name}")
            progress.failed[r]?.let { e.putString("$P_FAILED${r.name}", it.name) } ?: e.remove("$P_FAILED${r.name}")
            progress.attempted[r]?.let { e.putString("$P_ATTEMPTED${r.name}", it) } ?: e.remove("$P_ATTEMPTED${r.name}")
        }
        progress.awaiting?.let { e.putString(P_AWAITING, it.name) } ?: e.remove(P_AWAITING)
        return e.commit()
    }

    /** 发出中的短信：消息 URI → 各段回执。 */
    fun readSend(messageKey: String): SmsSendTracker? = SmsSendTracker.parse(prefs.getString(S_PREFIX + messageKey, null))

    fun writeSend(messageKey: String, tracker: SmsSendTracker) {
        prefs.edit().putString(S_PREFIX + messageKey, tracker.serialize()).commit()
    }

    fun clearSend(messageKey: String) {
        prefs.edit().remove(S_PREFIX + messageKey).commit()
    }

    private fun key(role: CommRole, field: String) = "role_${role.name}_$field"

    companion object {
        const val FILE_NAME = "fflue_comm_state"
        private const val P_ACTIVE = "restore_active"
        private const val P_ROLES = "restore_roles"
        private const val P_DONE = "restore_done"
        private const val P_SKIPPED = "restore_skipped"
        private const val P_CHOSEN = "restore_chosen_"
        private const val P_FAILED = "restore_failed_"
        private const val P_ATTEMPTED = "restore_attempted_"
        private const val P_AWAITING = "restore_awaiting"
        private const val S_PREFIX = "sms_send_"
    }
}
