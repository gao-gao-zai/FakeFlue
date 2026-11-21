package com.flue.launcher.surface.system

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Root 导航守护（R29、R30）。应用用 `su` 以 `app_process` 启动它，经标准输入输出通信（[GuardProtocol]）。
 *
 * 它持有一个 Binder token 调用 `IStatusBarService.disable/disable2`。P0 在模拟器上验证过：持有方进程一死，
 * system_server 收到 token 死亡通知会自动解除限制。所以守护被杀、崩溃、重启机器都不会永久锁住下拉和多任务；
 * 也不写任何持久的系统设置，没有需要恢复的原值。
 *
 * 应用意外退出（管道断开且没有收到 QUIT）时，按 [RecoveryBudget] 有界地重启伪装桌面；预算用完、
 * 或应用活着但没有接管，就解除限制。放弃时尝试打开应用事先告知的外部桌面，并留下记录。
 * 通话中不重启、不打开外部桌面，只先解除限制。
 *
 * 参数：`<包名> <应用 uid> <APK 路径> <状态目录> <重启组件>`。
 */
object StatusBarGuardDaemon {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 5) {
            println(GuardProtocol.formatReply(GuardProtocol.Reply.Failed("bad-args")))
            exitProcess(2)
        }
        GuardDaemon(
            packageName = args[0],
            appUid = args[1].toIntOrNull() ?: -1,
            apkPath = args[2],
            stateDir = File(args[3]),
            relaunchComponent = args[4],
        ).run()
    }
}

private class GuardDaemon(
    private val packageName: String,
    private val appUid: Int,
    private val apkPath: String,
    private val stateDir: File,
    private val relaunchComponent: String,
) {
    private val token = Binder()
    private val pidFile = File(stateDir, "guard.pid")
    private val budgetFile = File(stateDir, "guard.budget")
    private val recordFile = File(stateDir, RootGuardFiles.RECORD_FILE)
    private val myPid = android.os.Process.myPid()

    @Volatile private var current = 0 to 0
    @Volatile private var fallback: String? = null
    private var tookOver = false

    private val service: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "statusbar") as IBinder
        Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    fun run() {
        stateDir.mkdirs()
        val previous = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        pidFile.writeText(myPid.toString())
        watchPackage()
        reply(GuardProtocol.Reply.Ready(GuardProtocol.VERSION, myPid))

        val input = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = runCatching { input.readLine() }.getOrNull() ?: break
            when (val command = GuardProtocol.parse(line)) {
                is GuardProtocol.Command.Hello -> reply(GuardProtocol.Reply.Ready(GuardProtocol.VERSION, myPid))
                is GuardProtocol.Command.Set -> {
                    reply(apply(command.d1, command.d2))
                    // 自己的限制生效后再结束旧守护，接管期间不出现空档。
                    if (!tookOver) {
                        tookOver = true
                        previous?.takeIf { it != myPid && isGuardProcess(it) }?.let { runCatching { Os.kill(it, 9) } }
                    }
                }
                is GuardProtocol.Command.Fallback -> fallback = command.component
                GuardProtocol.Command.Ping -> reply(GuardProtocol.Reply.Pong)
                GuardProtocol.Command.Quit -> {
                    apply(0, 0)
                    finish()
                }
                null -> reply(GuardProtocol.Reply.Failed("unknown-command"))
            }
        }
        // 管道断开：应用进程没了。没有限制就直接退出；有限制才走恢复。
        if (current == (0 to 0)) finish()
        recover()
    }

    private fun reply(reply: GuardProtocol.Reply) {
        runCatching {
            println(GuardProtocol.formatReply(reply))
            System.out.flush()
        }
    }

    private fun apply(d1: Int, d2: Int): GuardProtocol.Reply {
        val type = service.javaClass
        return runCatching {
            type.getMethod("disable", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(service, d1, token, packageName)
            runCatching {
                type.getMethod("disable2", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                    .invoke(service, d2, token, packageName)
            }
            current = d1 to d2
            if (d1 == 0 && d2 == 0) return GuardProtocol.Reply.Applied(0, 0)
            // 命令返回不算生效：读回系统合并后的禁用位。
            val actual = StatusBarFlags.parseDumpsys(exec("dumpsys", "statusbar"))
            if (actual != null && StatusBarFlags.covers(actual, d1 to d2)) {
                GuardProtocol.Reply.Applied(actual.first, actual.second)
            } else {
                GuardProtocol.Reply.Unverified(actual?.first ?: -1, actual?.second ?: -1)
            }
        }.getOrElse { GuardProtocol.Reply.Failed((it.cause ?: it).javaClass.simpleName) }
    }

    private fun recover() {
        val wanted = current
        var unknownSince = -1L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val budget = RecoveryBudget.parse(budgetFile.takeIf { it.exists() }?.readText()).settle(now)
            if (budget.exhausted) giveUp("budget-exhausted", budget.attempts, launchFallback = true)

            when (callState()) {
                CallState.ACTIVE -> {
                    // 通话优先：先放开，等通话结束，不重启、不覆盖通话界面。
                    apply(0, 0)
                    unknownSince = -1
                    SystemClock.sleep(2_000)
                    continue
                }
                CallState.UNKNOWN -> {
                    apply(0, 0)
                    if (unknownSince < 0) unknownSince = now
                    if (now - unknownSince > UNKNOWN_CALL_LIMIT_MS) giveUp("call-state-unknown", budget.attempts, launchFallback = false)
                    SystemClock.sleep(2_000)
                    continue
                }
                CallState.IDLE -> unknownSince = -1
            }

            apply(wanted.first, wanted.second)
            val next = budget.consume(now)
            budgetFile.writeText(next.serialize())
            exec("am", "start", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
                "-f", "0x10000000", "-n", relaunchComponent)
            // 等新守护接管（它会结束本进程）。
            SystemClock.sleep(RecoveryBudget.WAIT_MS)
            if (appAlive()) {
                // 应用起来了却没有接管（例如 Root 被撤销）：不再限制，也不打开外部桌面盖住它。
                giveUp("no-takeover", next.attempts, launchFallback = false)
            }
        }
    }

    private fun giveUp(reason: String, attempts: Int, launchFallback: Boolean): Nothing {
        apply(0, 0)
        var launched = false
        val target = fallback
        if (launchFallback && target != null && callState() == CallState.IDLE) {
            launched = exec("am", "start", "--user", "0", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.HOME", "-f", "0x10000000", "-n", target).contains("Starting")
        }
        writeRecord(RecoveryRecord(System.currentTimeMillis(), reason, attempts, launched))
        budgetFile.delete()
        finish()
    }

    private fun writeRecord(record: RecoveryRecord) {
        runCatching {
            recordFile.appendText(record.serialize() + "\n")
            // 让应用自己能读：属主改回应用，SELinux 标签照抄所在目录。
            Os.chown(recordFile.path, appUid, appUid)
            val label = Os.getxattr(stateDir.path, "security.selinux")
            Os.setxattr(recordFile.path, "security.selinux", label, 0)
        }
    }

    private fun finish(): Nothing {
        if (pidFile.takeIf { it.exists() }?.readText()?.trim() == myPid.toString()) pidFile.delete()
        exitProcess(0)
    }

    /** 卸载或升级后 APK 路径失效：直接退出，token 随进程释放。 */
    private fun watchPackage() {
        Thread {
            while (true) {
                SystemClock.sleep(5_000)
                if (!File(apkPath).exists()) {
                    current = 0 to 0
                    finish()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun callState(): CallState = CallStateParser.parse(exec("dumpsys", "telephony.registry"))

    private fun appAlive(): Boolean = File("/proc").listFiles().orEmpty().any { dir ->
        dir.name.all(Char::isDigit) && runCatching {
            File(dir, "cmdline").readText().substringBefore('\u0000') == packageName
        }.getOrDefault(false)
    }

    private fun isGuardProcess(pid: Int): Boolean = runCatching {
        File("/proc/$pid/cmdline").readText().contains(RootGuardFiles.NICE_NAME)
    }.getOrDefault(false)

    private fun exec(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        out
    }.getOrDefault("")

    private companion object {
        const val UNKNOWN_CALL_LIMIT_MS = 60_000L
    }
}

/** 守护和应用共用的文件名。 */
object RootGuardFiles {
    const val DIR = "fflue_guard"
    const val RECORD_FILE = "recovery.log"
    const val NICE_NAME = "fflue_guard"
}
