package com.flue.launcher.surface.spike

import android.os.Binder
import android.os.IBinder

/**
 * P0 导航限制验证（只在 Debug 包里）：以 root 用 `app_process` 运行，带 Binder token 调用
 * `IStatusBarService.disable/disable2`，屏蔽下拉通知栏、快捷设置和最近任务，保留 Home／返回。
 *
 * 进程退出时 system_server 收到 token 的死亡通知，应当自动解除限制——这就是要验证的“崩溃不残留”。
 *
 * 用法：
 * `CLASSPATH=<apk> app_process /system/bin com.flue.launcher.surface.spike.StatusBarDisableSpike [秒数]`
 * 秒数省略时一直保持，直到进程被杀。
 */
object StatusBarDisableSpike {
    // android.app.StatusBarManager 隐藏常量
    private const val DISABLE_EXPAND = 0x00010000
    private const val DISABLE_RECENTS = 0x01000000
    private const val DISABLE2_QUICK_SETTINGS = 1
    private const val DISABLE2_NOTIFICATION_SHADE = 1 shl 2

    private const val PACKAGE = "com.gaozai.surface"

    @JvmStatic
    fun main(args: Array<String>) {
        val holdSeconds = args.firstOrNull()?.toLongOrNull()
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "statusbar") as IBinder
        val service = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val token = Binder()
        val type = service.javaClass

        type.getMethod("disable", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(service, DISABLE_EXPAND or DISABLE_RECENTS, token, PACKAGE)
        println("FFLUE_SPIKE disable ok pid=${android.os.Process.myPid()}")

        runCatching {
            type.getMethod("disable2", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(service, DISABLE2_QUICK_SETTINGS or DISABLE2_NOTIFICATION_SHADE, token, PACKAGE)
        }.onSuccess { println("FFLUE_SPIKE disable2 ok") }
            .onFailure { println("FFLUE_SPIKE disable2 failed: ${it.cause ?: it}") }

        System.out.flush()
        if (holdSeconds != null) {
            Thread.sleep(holdSeconds * 1000)
            println("FFLUE_SPIKE exit without explicit release")
        } else {
            while (true) Thread.sleep(60_000)
        }
    }
}
