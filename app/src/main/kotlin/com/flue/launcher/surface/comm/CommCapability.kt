package com.flue.launcher.surface.comm

/** 自研通信能力依赖的运行时权限（按用途分组）。 */
enum class CommPermission { CALL_PHONE, READ_PHONE_STATE, READ_CALL_LOG, SEND_SMS, READ_SMS, RECEIVE_SMS }

/** 系统事实。全部由调用方读取后传入，便于测试。 */
data class CommEnvironment(
    /** `PackageManager.FEATURE_TELEPHONY`。 */
    val hasTelephony: Boolean,
    /** SIM 是否就绪（`TelephonyManager.SIM_STATE_READY`）。 */
    val simReady: Boolean,
    val dialerRoleAvailable: Boolean,
    val smsRoleAvailable: Boolean,
    val isDefaultDialer: Boolean,
    val isDefaultSms: Boolean,
    val granted: Set<CommPermission>,
)

/** 某项自研能力关闭的原因；顺序即优先级。 */
enum class CommBlock { NO_HARDWARE, NO_SIM, ROLE_UNAVAILABLE, NOT_DEFAULT, PERMISSION_MISSING }

/**
 * R32 能力检测：无 SIM、硬件不支持、角色或权限不足时关闭对应自研能力（返回原因），
 * 系统原通信服务不受影响。返回 null 表示可用。
 */
object CommCapability {

    /** 用本应用的拨号盘发起普通呼叫（经 Telecom，由系统通信服务实际拨出）。 */
    fun dial(env: CommEnvironment): CommBlock? = when {
        !env.hasTelephony -> CommBlock.NO_HARDWARE
        !env.simReady -> CommBlock.NO_SIM
        CommPermission.CALL_PHONE !in env.granted -> CommBlock.PERMISSION_MISSING
        else -> null
    }

    /** 本应用的来电、通话中界面：只有默认电话应用才会被系统绑定。 */
    fun inCallUi(env: CommEnvironment): CommBlock? = when {
        !env.hasTelephony -> CommBlock.NO_HARDWARE
        !env.dialerRoleAvailable -> CommBlock.ROLE_UNAVAILABLE
        !env.isDefaultDialer -> CommBlock.NOT_DEFAULT
        else -> null
    }

    fun callLog(env: CommEnvironment): CommBlock? = when {
        !env.hasTelephony -> CommBlock.NO_HARDWARE
        CommPermission.READ_CALL_LOG !in env.granted -> CommBlock.PERMISSION_MISSING
        else -> null
    }

    fun smsRead(env: CommEnvironment): CommBlock? = when {
        !env.hasTelephony -> CommBlock.NO_HARDWARE
        CommPermission.READ_SMS !in env.granted -> CommBlock.PERMISSION_MISSING
        else -> null
    }

    fun smsSend(env: CommEnvironment): CommBlock? = when {
        !env.hasTelephony -> CommBlock.NO_HARDWARE
        !env.simReady -> CommBlock.NO_SIM
        CommPermission.SEND_SMS !in env.granted -> CommBlock.PERMISSION_MISSING
        else -> null
    }

    /** 接收短信并写入系统短信库：只有默认短信应用会收到 SMS_DELIVER。 */
    fun smsReceive(env: CommEnvironment): CommBlock? = when {
        !env.hasTelephony -> CommBlock.NO_HARDWARE
        !env.smsRoleAvailable -> CommBlock.ROLE_UNAVAILABLE
        !env.isDefaultSms -> CommBlock.NOT_DEFAULT
        else -> null
    }

    /** 是否由本应用写系统短信库：只有默认短信应用可以写；不是默认时系统自己写已发送。 */
    fun writesSmsProvider(env: CommEnvironment): Boolean = env.isDefaultSms
}

/** 拨号键的处理方式。 */
enum class DialRoute {
    /** 经 TelecomManager.placeCall 拨出。 */
    PLACE_CALL,

    /** 交给系统电话界面（预填号码），由系统决定能否拨出——紧急号码和无权限时用。 */
    SYSTEM_DIALER,

    /** 号码为空或不是可拨号码。 */
    INVALID,
}

object DialRules {
    private val allowed = Regex("""^\+?[0-9*#,;]+$""")

    /** 去掉空格、横线和括号；保留 + * # , ;（暂停／等待）。 */
    fun normalize(raw: String): String = raw.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }

    fun isDialable(number: String): Boolean = number.isNotEmpty() && allowed.matches(number)

    /**
     * 紧急号码遵循系统能力和必要系统界面（R32）：本应用是默认电话时由 Telecom 直接处理
     * （系统对默认电话应用放行紧急呼叫），否则交给系统电话界面，不因无 SIM 或权限不足拦下。
     */
    fun route(number: String, isEmergency: Boolean, env: CommEnvironment): DialRoute {
        if (!isDialable(number)) return DialRoute.INVALID
        if (isEmergency) {
            return if (env.isDefaultDialer && CommPermission.CALL_PHONE in env.granted) DialRoute.PLACE_CALL else DialRoute.SYSTEM_DIALER
        }
        return if (CommCapability.dial(env) == null) DialRoute.PLACE_CALL else DialRoute.SYSTEM_DIALER
    }
}
