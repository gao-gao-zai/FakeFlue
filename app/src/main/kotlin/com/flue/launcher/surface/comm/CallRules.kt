package com.flue.launcher.surface.comm

/** 本应用界面上的通话阶段。 */
enum class CallPhase {
    CONNECTING,
    DIALING,
    RINGING,
    ACTIVE,
    HOLDING,
    DISCONNECTING,
    DISCONNECTED,
    ;

    /** 通话仍在进行（含来电响铃）：返回或离开界面都不挂断，桌面锁和模式恢复不能遮住。 */
    val live: Boolean get() = this != DISCONNECTED
}

/** 某个阶段能做的操作。 */
data class CallActions(
    val answer: Boolean = false,
    val reject: Boolean = false,
    val hangUp: Boolean = false,
    val mute: Boolean = false,
    val speaker: Boolean = false,
    val keypad: Boolean = false,
    val hold: Boolean = false,
    val unhold: Boolean = false,
)

object CallRules {
    // android.telecom.Call.STATE_*；写成常量便于在纯 JVM 测试里使用。
    const val STATE_NEW = 0
    const val STATE_DIALING = 1
    const val STATE_RINGING = 2
    const val STATE_HOLDING = 3
    const val STATE_ACTIVE = 4
    const val STATE_DISCONNECTED = 7
    const val STATE_SELECT_PHONE_ACCOUNT = 8
    const val STATE_CONNECTING = 9
    const val STATE_DISCONNECTING = 10
    const val STATE_PULLING_CALL = 11
    const val STATE_AUDIO_PROCESSING = 12
    const val STATE_SIMULATED_RINGING = 13

    /** Telecom 状态 → 界面阶段。未知的新状态按“连接中”处理，不当成已结束。 */
    fun phaseOf(state: Int): CallPhase = when (state) {
        STATE_DIALING -> CallPhase.DIALING
        STATE_RINGING, STATE_SIMULATED_RINGING -> CallPhase.RINGING
        STATE_HOLDING -> CallPhase.HOLDING
        STATE_ACTIVE -> CallPhase.ACTIVE
        STATE_DISCONNECTING -> CallPhase.DISCONNECTING
        STATE_DISCONNECTED -> CallPhase.DISCONNECTED
        STATE_NEW, STATE_SELECT_PHONE_ACCOUNT, STATE_CONNECTING, STATE_PULLING_CALL, STATE_AUDIO_PROCESSING -> CallPhase.CONNECTING
        else -> CallPhase.CONNECTING
    }

    fun actionsOf(phase: CallPhase, canHold: Boolean): CallActions = when (phase) {
        CallPhase.RINGING -> CallActions(answer = true, reject = true)
        CallPhase.CONNECTING, CallPhase.DIALING -> CallActions(hangUp = true, mute = true, speaker = true, keypad = phase == CallPhase.DIALING)
        CallPhase.ACTIVE -> CallActions(hangUp = true, mute = true, speaker = true, keypad = true, hold = canHold)
        CallPhase.HOLDING -> CallActions(hangUp = true, unhold = true)
        CallPhase.DISCONNECTING, CallPhase.DISCONNECTED -> CallActions()
    }

    /** 多个通话时界面先显示哪个：响铃 > 进行中 > 拨号 > 保持 > 其他。 */
    fun primaryIndex(phases: List<CallPhase>): Int {
        if (phases.isEmpty()) return -1
        val order = listOf(CallPhase.RINGING, CallPhase.ACTIVE, CallPhase.DIALING, CallPhase.CONNECTING, CallPhase.HOLDING, CallPhase.DISCONNECTING)
        for (phase in order) {
            val i = phases.indexOf(phase)
            if (i >= 0) return i
        }
        return 0
    }

    /** 通话时长 mm:ss 或 h:mm:ss。 */
    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** DTMF 只接受 0-9 * #。 */
    fun isDtmf(c: Char): Boolean = c in '0'..'9' || c == '*' || c == '#'
}
