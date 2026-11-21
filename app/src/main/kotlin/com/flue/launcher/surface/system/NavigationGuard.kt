package com.flue.launcher.surface.system

import com.flue.launcher.surface.mode.SurfaceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 与 Root 守护的一条连接。实现可以阻塞，调用方在后台线程使用。 */
interface GuardConnection {
    val alive: Boolean

    /** 启动守护并等它就绪；失败返回原因。 */
    fun open(): String?

    /** 发一条命令并等回复；连接断了或超时返回 null。 */
    fun send(command: GuardProtocol.Command): GuardProtocol.Reply?

    /** 主动结束：先解除限制再退出，守护不会进入恢复。 */
    fun quit()
}

/** 导航限制的物理状态，与模式、授权分开记录（R28、R29）。 */
sealed interface NavGuardStatus {
    /** 当前模式不需要限制。 */
    data object NotNeeded : NavGuardStatus

    /** 伪装中，通话或设备系统页交接临时放行。 */
    data object Released : NavGuardStatus

    data object Applying : NavGuardStatus

    /** 读回系统状态确认限制已生效。 */
    data object Active : NavGuardStatus

    /** 调用返回了但读回没看到效果，不冒充成功。 */
    data object Unverified : NavGuardStatus

    data class Unavailable(val reason: Reason, val detail: String? = null) : NavGuardStatus

    enum class Reason { NO_ROOT, START_FAILED, CONNECTION_LOST }
}

/**
 * 导航限制的唯一执行入口（§12-2）：拿事实 → [NavPolicy] 决策 → 交给守护 → 记录读回结果。
 *
 * - 连接复用；焦点、页面变化不会重新申请 Root（R29）。启动失败后不自动重试，等用户在完整设置里
 *   重新验证，或下一次进入伪装。
 * - 只有用户在授权页主动确认过 Root（[rootConfirmed]）才会启动守护，日常使用不弹 Root 授权（R28）。
 * - 离开伪装时发 QUIT：守护解除限制后退出，不会把它当成意外退出去重启伪装桌面（R30）。
 *
 * 调用方保证串行调用（单线程）。
 */
class NavigationGuard(
    private val connect: () -> GuardConnection,
    private val rootConfirmed: () -> Boolean,
    private val fallbackHome: () -> String?,
) {
    private val _status = MutableStateFlow<NavGuardStatus>(NavGuardStatus.NotNeeded)
    val status: StateFlow<NavGuardStatus> = _status.asStateFlow()

    private var connection: GuardConnection? = null
    private var startFailed: String? = null
    private var applied: Pair<Int, Int>? = null
    private var sentFallback: String? = null
    private var lastFacts: NavFacts? = null
    private var losses = 0

    /** 启动守护的次数（测试用：确认不会反复申请 Root）。 */
    var opens: Int = 0
        private set

    fun update(facts: NavFacts) {
        lastFacts = facts
        if (facts.mode != SurfaceMode.DISGUISE) {
            stop()
            // 下一次进入伪装时可以重新尝试启动。
            startFailed = null
            _status.value = NavGuardStatus.NotNeeded
            return
        }
        val wanted = NavPolicy.decide(facts)
        if (!rootConfirmed()) {
            _status.value = if (wanted.active) NavGuardStatus.Unavailable(NavGuardStatus.Reason.NO_ROOT) else NavGuardStatus.Released
            return
        }
        val live = connection?.takeIf { it.alive }
        if (live == null && !wanted.active) {
            // 没有在限制，也就不需要为了“放行”去启动守护。
            connection = null
            applied = null
            _status.value = NavGuardStatus.Released
            return
        }
        val conn = live ?: openConnection() ?: return
        syncFallback(conn)

        val flags = StatusBarFlags.of(wanted)
        if (flags == applied) return
        _status.value = NavGuardStatus.Applying
        when (val reply = conn.send(GuardProtocol.Command.Set(flags.first, flags.second))) {
            is GuardProtocol.Reply.Applied -> {
                applied = flags
                _status.value = if (wanted.active) NavGuardStatus.Active else NavGuardStatus.Released
            }
            is GuardProtocol.Reply.Unverified -> {
                applied = flags
                _status.value = if (wanted.active) NavGuardStatus.Unverified else NavGuardStatus.Released
            }
            is GuardProtocol.Reply.Failed -> {
                applied = null
                _status.value = NavGuardStatus.Unavailable(NavGuardStatus.Reason.START_FAILED, reply.reason)
            }
            else -> lost()
        }
    }

    /** 用户在完整设置里点“重新连接”。 */
    fun retry() {
        startFailed = null
        losses = 0
        if (connection?.alive != true) {
            connection = null
            applied = null
        }
        lastFacts?.let(::update)
    }

    private fun openConnection(): GuardConnection? {
        startFailed?.let {
            _status.value = NavGuardStatus.Unavailable(NavGuardStatus.Reason.START_FAILED, it)
            return null
        }
        val conn = connect()
        opens++
        val error = conn.open()
        if (error != null) {
            startFailed = error
            _status.value = NavGuardStatus.Unavailable(NavGuardStatus.Reason.START_FAILED, error)
            return null
        }
        connection = conn
        applied = null
        sentFallback = null
        return conn
    }

    private fun syncFallback(conn: GuardConnection) {
        val target = fallbackHome() ?: "-"
        if (target == sentFallback) return
        conn.send(GuardProtocol.Command.Fallback(target.takeIf { it != "-" }))
        sentFallback = target
    }

    private fun lost() {
        connection = null
        applied = null
        // 守护没了，系统已自动解除限制。允许下一次事实变化时重连一次；再断就停下等用户处理，
        // 不在每次页面变化时反复申请 Root。
        losses++
        if (losses >= MAX_LOSSES) startFailed = "connection-lost"
        _status.value = NavGuardStatus.Unavailable(NavGuardStatus.Reason.CONNECTION_LOST)
    }

    private companion object {
        const val MAX_LOSSES = 2
    }

    private fun stop() {
        connection?.takeIf { it.alive }?.quit()
        connection = null
        applied = null
        sentFallback = null
    }
}
