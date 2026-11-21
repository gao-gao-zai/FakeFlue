package com.flue.launcher.surface.startup

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log

/**
 * R35 启动计时：表盘就绪和导航限制就绪分别记录，不拿启动动画、空窗口或 am start 的 TotalTime 代替。
 *
 * 时间都按进程启动（[Process.getStartElapsedRealtime]）起算，单位毫秒。每次冷启动（进程里第一次创建桌面）
 * 输出一行 `FflueStartup` 日志，供真机按 `adb logcat -s FflueStartup` 收集后和 P0 基线对照。
 *
 * - `face`：当前表盘选好并且首帧已经画出、手势解锁，即“完整表盘可操作”。
 * - `lock`：伪装模式启用了桌面进入密码时，密码页实际显示出来的时刻（它替代表盘成为首个可操作页面）。
 * - `guard`：伪装模式导航限制实际生效（读回确认）的时刻；不需要限制时记为 `none`。
 */
object StartupTimeline {
    const val TAG = "FflueStartup"

    enum class Mark { APP_CREATE, LAUNCHER_CREATE, FIRST_FRAME, FACE_SELECTED, FACE_INTERACTIVE, LOCK_SHOWN, GUARD_ACTIVE }

    /** 一次冷启动的计时结果；未到达的点不出现在 [marks] 里。 */
    data class Report(
        val marks: Map<Mark, Long>,
        val guardNotNeeded: Boolean,
    ) {
        fun format(): String = buildString {
            append("cold")
            Mark.entries.forEach { mark ->
                append(' ').append(mark.name.lowercase()).append('=')
                append(marks[mark]?.let { "${it}ms" } ?: "-")
            }
            if (guardNotNeeded) append(" guard=none")
        }
    }

    private val recorder = Recorder(
        clock = SystemClock::elapsedRealtime,
        processStart = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartElapsedRealtime() else SystemClock.elapsedRealtime()
        },
        sink = { Log.i(TAG, it) },
    )

    fun mark(mark: Mark) = recorder.mark(mark)

    fun guardNotNeeded() = recorder.guardNotNeeded()

    fun flushIfPending() = recorder.flushIfPending()

    fun snapshot(): Report = recorder.snapshot()

    /**
     * 记录器本体。每个点只记第一次（之后的切换、重建不覆盖冷启动结果）；首个可操作页面（表盘或密码页）
     * 和导航限制都有结论时输出一行，且只输出一次。
     */
    class Recorder(
        private val clock: () -> Long,
        private val processStart: () -> Long,
        private val sink: (String) -> Unit,
    ) {
        private val lock = Any()
        private val marks = LinkedHashMap<Mark, Long>()
        private var guardNotNeeded = false
        private var reported = false
        private var origin: Long = -1L

        fun mark(mark: Mark) {
            val line = synchronized(lock) {
                if (reported || marks.containsKey(mark)) return
                if (origin < 0) origin = processStart()
                val elapsed = (clock() - origin).coerceAtLeast(0L)
                marks[mark] = elapsed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { Trace.setCounter("fflue_startup_${mark.name.lowercase()}", elapsed) }
                }
                completedLineLocked()
            }
            line?.let(sink)
        }

        /** 导航限制本次不需要（完整模式、通话中等）：guard 不再等待。 */
        fun guardNotNeeded() {
            val line = synchronized(lock) {
                if (reported || marks.containsKey(Mark.GUARD_ACTIVE) || guardNotNeeded) return
                guardNotNeeded = true
                completedLineLocked()
            }
            line?.let(sink)
        }

        /** 导航限制迟迟没有结论（例如 Root 不可用或连接超时）时，页面就绪后先输出，标为 pending。 */
        fun flushIfPending() {
            val line = synchronized(lock) {
                if (reported || !pageReadyLocked()) return
                reported = true
                Report(LinkedHashMap(marks), guardNotNeeded).format() + " guard=pending"
            }
            sink(line)
        }

        fun snapshot(): Report = synchronized(lock) { Report(LinkedHashMap(marks), guardNotNeeded) }

        private fun pageReadyLocked(): Boolean =
            marks.containsKey(Mark.FACE_INTERACTIVE) || marks.containsKey(Mark.LOCK_SHOWN)

        private fun completedLineLocked(): String? {
            val guardDone = guardNotNeeded || marks.containsKey(Mark.GUARD_ACTIVE)
            if (!pageReadyLocked() || !guardDone) return null
            reported = true
            return Report(LinkedHashMap(marks), guardNotNeeded).format()
        }
    }

    /** 主线程上的耗时段，配合 systrace / Perfetto 查看。 */
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }
}
