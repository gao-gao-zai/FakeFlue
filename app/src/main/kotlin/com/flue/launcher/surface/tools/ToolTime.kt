package com.flue.launcher.surface.tools

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.ZoneId
import kotlin.math.abs

/**
 * 一次开机的身份（R31）。`elapsedRealtime` 只在同一次开机里有意义，重启后从零开始；
 * 计时器、秒表保存的单调时刻必须先确认还是同一次开机才能直接使用。
 *
 * 系统提供开机次数（`Settings.Global.BOOT_COUNT`）时以它为准；读不到时用
 * “墙上时间 − 开机时长” 估出的开机时刻比较，容差 [BOOT_WALL_TOLERANCE_MS] 吸收网络校时。
 */
data class BootSession(val bootCount: Int?, val bootWallMs: Long) {
    fun sameAs(other: BootSession): Boolean {
        if (bootCount != null && other.bootCount != null) return bootCount == other.bootCount
        return abs(bootWallMs - other.bootWallMs) <= BOOT_WALL_TOLERANCE_MS
    }

    companion object {
        const val BOOT_WALL_TOLERANCE_MS = 10 * 60_000L
    }
}

/** 工具使用的时钟。界面、接收器和测试共用同一套状态机，只换时钟。 */
interface ToolClock {
    /** 墙上时间（可被用户或网络校时修改）。 */
    fun wallMs(): Long

    /** 本次开机后的单调时长，含休眠。 */
    fun elapsedMs(): Long

    fun zone(): ZoneId

    fun boot(): BootSession
}

class SystemToolClock(context: Context) : ToolClock {
    private val app = context.applicationContext

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun boot(): BootSession {
        val count = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1).takeIf { it >= 0 }
        return BootSession(count, wallMs() - elapsedMs())
    }
}
