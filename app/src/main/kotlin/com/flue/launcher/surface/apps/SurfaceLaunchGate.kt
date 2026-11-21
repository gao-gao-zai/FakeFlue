package com.flue.launcher.surface.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.flue.launcher.surface.SurfaceRuntime

/**
 * 表盘点击动作等直接 startActivity 的入口在伪装模式下的启动检查（R08、R10）。
 *
 * 先解析出真正会打开的包；解析不出或会弹系统选择器时视为不公开。
 */
object SurfaceLaunchGate {
    fun allows(context: Context, intent: Intent): Boolean {
        val runtime = SurfaceRuntime.get(context)
        if (runtime.publicApps.value == null) return true
        return runtime.isPackagePublic(targetPackage(context, intent))
    }

    @Suppress("DEPRECATION")
    fun targetPackage(context: Context, intent: Intent): String? {
        intent.component?.packageName?.let { return it }
        intent.`package`?.let { return it }
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()?.activityInfo?.packageName
        // 没有默认项时解析到系统选择器（包名 "android"），实际目标未知。
        return resolved?.takeUnless { it == "android" }
    }
}
