package com.flue.launcher.surface.system

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 用 `su` 启动 [StatusBarGuardDaemon]，经它的标准输入输出通信。
 *
 * 应用进程一死，`su` 客户端随之结束，守护读到管道断开就知道应用没了——不需要心跳或端口。
 */
class RootGuardConnection(private val context: Context) : GuardConnection {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val replies = LinkedBlockingQueue<String>()

    override val alive: Boolean get() = process?.isAlive == true

    override fun open(): String? {
        val apk = context.applicationInfo.sourceDir
        val stateDir = stateDir(context).apply { mkdirs() }
        val component = "${context.packageName}/com.flue.launcher.LauncherActivity"
        val command = "CLASSPATH=${quote(apk)} exec app_process /system/bin --nice-name=${RootGuardFiles.NICE_NAME} " +
            "${StatusBarGuardDaemon::class.java.name} ${context.packageName} ${android.os.Process.myUid()} " +
            "${quote(apk)} ${quote(stateDir.path)} $component"
        val started = runCatching { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
            .getOrElse { return "su-unavailable" }
        process = started
        writer = started.outputStream.bufferedWriter()
        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { replies.put(it) }
            }
        }.apply {
            isDaemon = true
            name = "fflue-guard-reader"
        }.start()
        // 第一次可能要等 Root 管理器授权。
        return when (val reply = awaitReply(OPEN_TIMEOUT_MS)) {
            is GuardProtocol.Reply.Ready -> if (reply.version == GuardProtocol.VERSION) null else {
                quit()
                "version-${reply.version}"
            }
            is GuardProtocol.Reply.Failed -> {
                close()
                reply.reason
            }
            else -> {
                close()
                if (started.isAlive) "timeout" else "denied"
            }
        }
    }

    override fun send(command: GuardProtocol.Command): GuardProtocol.Reply? {
        val out = writer ?: return null
        if (!alive) return null
        replies.clear()
        val ok = runCatching {
            out.write(GuardProtocol.format(command))
            out.newLine()
            out.flush()
        }.isSuccess
        if (!ok) return null
        // FALLBACK 没有回复。
        if (command is GuardProtocol.Command.Fallback) return GuardProtocol.Reply.Pong
        return awaitReply(REPLY_TIMEOUT_MS)
    }

    override fun quit() {
        runCatching {
            writer?.apply {
                write(GuardProtocol.format(GuardProtocol.Command.Quit))
                newLine()
                flush()
            }
        }
        runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
        close()
    }

    private fun close() {
        runCatching { writer?.close() }
        process?.destroy()
        process = null
        writer = null
    }

    /** 跳过守护以外的杂行（例如 Root 管理器的提示），只认协议回复。 */
    private fun awaitReply(timeoutMs: Long): GuardProtocol.Reply? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val line = replies.poll(left, TimeUnit.MILLISECONDS) ?: return null
            GuardProtocol.parseReply(line)?.let { return it }
            Log.d(TAG, "guard: $line")
        }
    }

    companion object {
        private const val TAG = "FflueGuard"
        private const val OPEN_TIMEOUT_MS = 25_000L
        private const val REPLY_TIMEOUT_MS = 8_000L

        fun stateDir(context: Context): File = File(context.filesDir, RootGuardFiles.DIR)

        fun recordFile(context: Context): File = File(stateDir(context), RootGuardFiles.RECORD_FILE)

        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
