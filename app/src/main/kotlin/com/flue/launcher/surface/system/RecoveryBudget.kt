package com.flue.launcher.surface.system

/**
 * 伪装应用意外退出后的重启预算（R30）：最多 [MAX_ATTEMPTS] 次，每次最多等 [WAIT_MS]。
 *
 * 状态写在文件里，由守护进程读写：新守护接管旧守护时继承次数，避免“应用起来就崩 → 新守护 →
 * 预算重新开始”的无限循环。应用连续稳定运行 [STABLE_RESET_MS] 后才清零。
 */
data class RecoveryBudget(val attempts: Int = 0, val lastAttemptAt: Long = 0) {

    val exhausted: Boolean get() = attempts >= MAX_ATTEMPTS

    fun consume(nowMs: Long): RecoveryBudget = copy(attempts = attempts + 1, lastAttemptAt = nowMs)

    /** 应用已经稳定运行足够久就清零；时间倒退（重启过机器）也清零。 */
    fun settle(nowMs: Long): RecoveryBudget = when {
        attempts == 0 -> this
        nowMs < lastAttemptAt -> RecoveryBudget()
        nowMs - lastAttemptAt >= STABLE_RESET_MS -> RecoveryBudget()
        else -> this
    }

    fun serialize(): String = "$attempts $lastAttemptAt"

    companion object {
        const val MAX_ATTEMPTS = 3
        const val WAIT_MS = 10_000L
        const val STABLE_RESET_MS = 120_000L

        fun parse(text: String?): RecoveryBudget {
            val parts = text?.trim()?.split(' ') ?: return RecoveryBudget()
            val attempts = parts.getOrNull(0)?.toIntOrNull() ?: return RecoveryBudget()
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return RecoveryBudget()
            return RecoveryBudget(attempts.coerceIn(0, MAX_ATTEMPTS), at)
        }
    }
}

/** 通话状态（R30：有通话或无法确认时不冒险切换）。 */
enum class CallState { IDLE, ACTIVE, UNKNOWN }

object CallStateParser {
    /** `dumpsys telephony.registry` 里的 `mCallState=0`；多张卡取任一非空闲。 */
    fun parse(text: String): CallState {
        val states = Regex("""mCallState=(\d+)""").findAll(text).map { it.groupValues[1].toInt() }.toList()
        return when {
            states.isEmpty() -> CallState.UNKNOWN
            states.any { it != 0 } -> CallState.ACTIVE
            else -> CallState.IDLE
        }
    }
}

/** 一次恢复失败的记录，在完整设置里查看和处理（R30）。 */
data class RecoveryRecord(val atWallMs: Long, val reason: String, val attempts: Int, val fallbackLaunched: Boolean) {
    fun serialize(): String = "$atWallMs\t$reason\t$attempts\t$fallbackLaunched"

    companion object {
        fun parseAll(text: String?): List<RecoveryRecord> = text.orEmpty().lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) return@mapNotNull null
            RecoveryRecord(p[0].toLongOrNull() ?: return@mapNotNull null, p[1], p[2].toIntOrNull() ?: 0, p[3] == "true")
        }.toList()
    }
}

/**
 * 应用与 Root 守护之间的文本协议，一行一条。守护经 `su` 的标准输入输出通信：
 * 只有启动它的应用持有管道，不需要另开端口或校验来源；管道断开即说明应用进程没了。
 */
object GuardProtocol {
    const val VERSION = 1

    sealed interface Command {
        data class Hello(val version: Int) : Command
        data class Set(val d1: Int, val d2: Int) : Command
        data class Fallback(val component: String?) : Command
        data object Quit : Command
        data object Ping : Command
    }

    fun parse(line: String): Command? {
        val parts = line.trim().split(' ')
        return when (parts.firstOrNull()) {
            "HELLO" -> parts.getOrNull(1)?.toIntOrNull()?.let(Command::Hello)
            "SET" -> {
                val d1 = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val d2 = parts.getOrNull(2)?.toIntOrNull() ?: return null
                Command.Set(d1, d2)
            }
            "FALLBACK" -> Command.Fallback(parts.getOrNull(1)?.takeIf { it != "-" })
            "QUIT" -> Command.Quit
            "PING" -> Command.Ping
            else -> null
        }
    }

    fun format(command: Command): String = when (command) {
        is Command.Hello -> "HELLO ${command.version}"
        is Command.Set -> "SET ${command.d1} ${command.d2}"
        is Command.Fallback -> "FALLBACK ${command.component ?: "-"}"
        Command.Quit -> "QUIT"
        Command.Ping -> "PING"
    }

    /** 守护的回复。 */
    sealed interface Reply {
        data class Ready(val version: Int, val pid: Int) : Reply
        /** 读回系统状态确认生效。 */
        data class Applied(val d1: Int, val d2: Int) : Reply
        /** 调用返回了，但读回没看到我们要的位。 */
        data class Unverified(val d1: Int, val d2: Int) : Reply
        data class Failed(val reason: String) : Reply
        data object Pong : Reply
    }

    fun parseReply(line: String): Reply? {
        val parts = line.trim().split(' ')
        fun int(i: Int) = parts.getOrNull(i)?.toIntOrNull()
        return when (parts.firstOrNull()) {
            "READY" -> Reply.Ready(int(1) ?: return null, int(2) ?: return null)
            "APPLIED" -> Reply.Applied(int(1) ?: return null, int(2) ?: return null)
            "UNVERIFIED" -> Reply.Unverified(int(1) ?: -1, int(2) ?: -1)
            "FAILED" -> Reply.Failed(parts.drop(1).joinToString(" ").ifEmpty { "unknown" })
            "PONG" -> Reply.Pong
            else -> null
        }
    }

    fun formatReply(reply: Reply): String = when (reply) {
        is Reply.Ready -> "READY ${reply.version} ${reply.pid}"
        is Reply.Applied -> "APPLIED ${reply.d1} ${reply.d2}"
        is Reply.Unverified -> "UNVERIFIED ${reply.d1} ${reply.d2}"
        is Reply.Failed -> "FAILED ${reply.reason.replace('\n', ' ')}"
        Reply.Pong -> "PONG"
    }
}
