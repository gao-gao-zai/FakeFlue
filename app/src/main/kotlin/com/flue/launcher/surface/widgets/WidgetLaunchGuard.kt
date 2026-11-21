package com.flue.launcher.surface.widgets

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.flue.launcher.R
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小组件点击的目标检查（R11）：不能借任意 PendingIntent 绕过隐藏应用或高级功能规则。
 *
 * 系统没有公开接口在点击前读出 PendingIntent 的真实目标，所以分两层：
 * 1. 点击时：RemoteViews 的点击最终都走宿主 View 所在 Context 的 `startIntentSender`，
 *    这里拦下来，检查 PendingIntent 的创建方和填充 Intent 里显式写出的目标，不公开就不发送。
 * 2. 点击后：无障碍服务可用时，在短时间内观察实际打开的前台应用；出现未公开的应用就回到表盘。
 *    无障碍未开启时只有第一层。
 */
object WidgetLaunchCheck {
    /** 系统界面与输入法这类点击后可能短暂出现、但不是“打开了某个应用”的包。 */
    private val SYSTEM_SURFACES = setOf("android", "com.android.systemui")

    const val WATCH_WINDOW_MS = 3_000L

    fun allowsDispatch(
        creatorPackage: String?,
        fillInTargetPackage: String?,
        isPackagePublic: (String?) -> Boolean,
    ): Boolean {
        if (!isPackagePublic(creatorPackage)) return false
        return fillInTargetPackage == null || isPackagePublic(fillInTargetPackage)
    }

    /** 点击后观察到的前台包是否越界。 */
    fun violates(
        observedPackage: String,
        ownPackage: String,
        inputMethodPackage: String?,
        isPackagePublic: (String?) -> Boolean,
    ): Boolean {
        if (observedPackage == ownPackage || observedPackage in SYSTEM_SURFACES) return false
        if (observedPackage == inputMethodPackage) return false
        return !isPackagePublic(observedPackage)
    }
}

/** 小组件宿主 View 使用的 Context。见 [WidgetLaunchCheck]。 */
class WidgetLaunchGuardContext(base: Context) : ContextWrapper(base) {

    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
    ) {
        if (!checkBeforeDispatch(intent, fillInIntent)) return
        super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags)
        WidgetLaunchWatch.arm(this)
    }

    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?,
    ) {
        if (!checkBeforeDispatch(intent, fillInIntent)) return
        super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options)
        WidgetLaunchWatch.arm(this)
    }

    private fun checkBeforeDispatch(intent: IntentSender, fillInIntent: Intent?): Boolean {
        val runtime = SurfaceRuntime.get(this)
        if (runtime.publicApps.value == null) return true
        val fillInTarget = fillInIntent?.component?.packageName ?: fillInIntent?.`package`
        val allowed = WidgetLaunchCheck.allowsDispatch(intent.creatorPackage, fillInTarget, runtime::isPackagePublic)
        if (!allowed) {
            Toast.makeText(this, R.string.surface_widget_target_blocked, Toast.LENGTH_SHORT).show()
        }
        return allowed
    }
}

/** 点击后的前台观察（第二层）。同一时刻只保留最近一次点击的观察。 */
internal object WidgetLaunchWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun arm(context: Context) {
        val app = context.applicationContext
        val runtime = SurfaceRuntime.get(app)
        if (runtime.publicApps.value == null || !FlueAccessibilityService.isConnected()) return
        val armedAt = SystemClock.elapsedRealtime()
        val baseline = FlueAccessibilityService.observedForeground.value?.sequence ?: -1L
        val ime = currentInputMethodPackage(app)
        job?.cancel()
        job = scope.launch {
            val offending = withTimeoutOrNull(WidgetLaunchCheck.WATCH_WINDOW_MS) {
                FlueAccessibilityService.observedForeground
                    .filterNotNull()
                    .first { evidence ->
                        evidence.sequence > baseline &&
                            evidence.observedAtElapsedMs >= armedAt &&
                            WidgetLaunchCheck.violates(evidence.packageName, app.packageName, ime, runtime::isPackagePublic)
                    }
            } ?: return@launch
            if (runtime.publicApps.value == null) return@launch
            runCatching { app.startActivity(SurfaceHome.returnToFaceIntent(app)) }
            Toast.makeText(app, R.string.surface_widget_target_blocked, Toast.LENGTH_SHORT).show()
            android.util.Log.i("SurfaceWidget", "widget opened non-public ${offending.packageName}; returned to face")
        }
    }

    private fun currentInputMethodPackage(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    }.getOrNull()
}
