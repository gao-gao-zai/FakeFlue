package com.flue.launcher.surface.comm

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.secret.SecretInterruptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 通信相关的系统查询与动作（R32）。只读系统状态的函数不请求任何授权；
 * 角色申请和恢复只在完整设置／授权页由用户点击后发起（R28）。
 */
data class TakeoverRequest(val role: CommRole, val intent: Intent, val recordedNow: Boolean)

object CommSystem {
    private const val TAG = "FflueComm"

    fun hasTelephony(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun simReady(context: Context): Boolean = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.simState == TelephonyManager.SIM_STATE_READY
    }.getOrDefault(false)

    fun roleAvailable(context: Context, role: CommRole): Boolean {
        if (!hasTelephony(context)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { context.getSystemService(RoleManager::class.java)?.isRoleAvailable(role.roleName) == true }
                .getOrDefault(false)
        }
        return true
    }

    /** 系统读回的当前持有者；没有默认应用时为 null。 */
    fun holder(context: Context, role: CommRole): String? = runCatching {
        when (role) {
            CommRole.DIALER -> context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            CommRole.SMS -> Telephony.Sms.getDefaultSmsPackage(context)
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun holds(context: Context, role: CommRole): Boolean = holder(context, role) == context.packageName

    /** 本机能担任该角色的应用（按组件能力近似，系统最终决定）。 */
    @Suppress("DEPRECATION")
    fun qualified(context: Context, role: CommRole): List<String> = runCatching {
        val pm = context.packageManager
        when (role) {
            CommRole.DIALER -> pm.queryIntentActivities(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")), 0)
                .mapNotNull { it.activityInfo?.packageName }
            CommRole.SMS -> pm.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), 0)
                .mapNotNull { it.activityInfo?.packageName }
        }.distinct()
    }.getOrDefault(emptyList())

    fun granted(context: Context): Set<CommPermission> = CommPermission.entries.filter { p ->
        ContextCompat.checkSelfPermission(context, manifestName(p)) == PackageManager.PERMISSION_GRANTED
    }.toSet()

    fun manifestName(p: CommPermission): String = when (p) {
        CommPermission.CALL_PHONE -> Manifest.permission.CALL_PHONE
        CommPermission.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        CommPermission.READ_CALL_LOG -> Manifest.permission.READ_CALL_LOG
        CommPermission.SEND_SMS -> Manifest.permission.SEND_SMS
        CommPermission.READ_SMS -> Manifest.permission.READ_SMS
        CommPermission.RECEIVE_SMS -> Manifest.permission.RECEIVE_SMS
    }

    fun environment(context: Context): CommEnvironment = CommEnvironment(
        hasTelephony = hasTelephony(context),
        simReady = simReady(context),
        dialerRoleAvailable = roleAvailable(context, CommRole.DIALER),
        smsRoleAvailable = roleAvailable(context, CommRole.SMS),
        isDefaultDialer = holds(context, CommRole.DIALER),
        isDefaultSms = holds(context, CommRole.SMS),
        granted = granted(context),
    )

    fun restoreFacts(context: Context): RestoreFacts = RestoreFacts(
        roles = CommRole.entries.associateWith { RoleFacts(holder(context, it), qualified(context, it)) },
        inCall = inCall(context),
    )

    /**
     * 是否有通话或来电：本应用的通话服务、Telecom（有电话状态权限时）或音频模式任一认为有。
     * 无法确认时按有通话处理由调用方决定；这里只合并已知事实。
     */
    fun inCall(context: Context): Boolean {
        if (CallRegistry.hasLiveCall.value) return true
        if (SecretInterruptions.inCall(context)) return true
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        val canAsk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        return canAsk && runCatching { telecom.isInCall }.getOrDefault(false)
    }

    fun ledger(context: Context): RoleLedger = RoleLedger(CommStore(context), context.packageName)

    fun restoreFlow(context: Context): RoleRestoreFlow =
        RoleRestoreFlow(CommStore(context), ledger(context), context.packageName)

    /** 退出外部前是否要先恢复通信角色。 */
    fun restoreNeeded(context: Context): Boolean = restoreFlow(context).needed(restoreFacts(context))

    fun restorePending(context: Context): Boolean = CommStore(context).readProgress().active

    // ---- 接管（R32：首次接管前记录原应用，结果读回确认）----

    /**
     * 申请成为默认电话／短信应用的系统界面。申请之前记录原持有者（只记第一次，不记本应用）。
     * 角色不可用时返回 null。
     */
    fun takeoverRequest(context: Context, role: CommRole): TakeoverRequest? {
        if (!roleAvailable(context, role)) return null
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            runCatching { rm.createRequestRoleIntent(role.roleName) }.getOrNull() ?: return null
        } else {
            legacyChangeIntent(role, context.packageName)
        }
        val recordedNow = ledger(context).recordBeforeTakeover(role, holder(context, role))
        return TakeoverRequest(role, intent, recordedNow)
    }

    /** 系统界面返回后读回：本应用确实是持有者才算接管。 */
    fun finishTakeover(context: Context, request: TakeoverRequest): Boolean =
        ledger(context).afterTakeover(request.role, request.recordedNow, holder(context, request.role))

    private fun legacyChangeIntent(role: CommRole, packageName: String): Intent = when (role) {
        CommRole.DIALER -> Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        CommRole.SMS -> Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
    }

    // ---- 恢复 ----

    /**
     * 让用户在系统里把角色交给 [target]。API 28 的系统对话框可直接指定目标；
     * API 29+ 只能由持有者自己申请角色，本应用打开系统“默认应用”设置让用户选择。
     */
    fun restoreIntent(role: CommRole, target: String): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else {
            legacyChangeIntent(role, target)
        }

    /** 打开系统选择；失败时退回系统设置首页。返回是否打开了。 */
    fun openRestore(context: Context, role: CommRole, target: String): Boolean {
        val candidates = listOf(restoreIntent(role, target), Intent(Settings.ACTION_SETTINGS))
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    fun rootConfirmed(context: Context): Boolean = SurfaceRuntime.get(context).stateStore.rootConfirmed

    /**
     * 用户已确认 Root 时用命令交还角色，然后等待系统读回（最多约 3 秒）。
     * @return 读回的持有者；命令失败也返回当时读回的值，由调用方判定。
     */
    suspend fun restoreWithRoot(context: Context, role: CommRole, target: String): String? {
        val commands = RootRoleCommands.commands(role, target, Build.VERSION.SDK_INT)
        if (commands.isEmpty()) return holder(context, role)
        withContext(Dispatchers.IO) {
            commands.forEach { command ->
                withTimeoutOrNull(15_000) {
                    runCatching {
                        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                        process.inputStream.bufferedReader().readText()
                        process.waitFor(10, TimeUnit.SECONDS)
                    }.onFailure { Log.w(TAG, "root role command failed", it) }
                }
            }
        }
        repeat(6) {
            val now = holder(context, role)
            if (now == target) return now
            delay(500)
        }
        return holder(context, role)
    }

    // ---- 其他 ----

    @Suppress("DEPRECATION")
    fun isEmergency(context: Context, number: String): Boolean {
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number)
            } else {
                PhoneNumberUtils.isEmergencyNumber(number)
            }
        }.getOrNull()
        // 查不到时按常见紧急号码保守处理：交给系统界面。
        return result ?: (number in FALLBACK_EMERGENCY)
    }

    private val FALLBACK_EMERGENCY = setOf("112", "911", "999", "000", "110", "119", "120", "122", "118", "08")

    fun appLabel(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }

    /** 系统自带的电话界面包名（不是本应用），用于紧急号码和能力不足时交给系统。 */
    @Suppress("DEPRECATION")
    fun systemDialerPackage(context: Context): String? {
        val own = context.packageName
        val telecom = context.getSystemService(TelecomManager::class.java)
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { telecom?.systemDialerPackage }.getOrNull() else null
        if (system != null && system != own) return system
        val current = holder(context, CommRole.DIALER)
        if (current != null && current != own) return current
        return qualified(context, CommRole.DIALER).firstOrNull { it != own }
    }
}
