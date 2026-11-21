package com.flue.launcher.surface.access

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.flue.launcher.surface.mode.SurfaceModeController
import java.lang.ref.WeakReference

/**
 * 页面级访问守卫（R07、R08）：受限页面在伪装模式下打开时直接关闭；切入伪装模式时，
 * 已经打开的受限页面也会被关闭。无论从按钮、快捷方式、旧返回栈还是外部 Intent 进入都一样。
 *
 * P1 只覆盖独立 Activity；页面内入口（设置子页、长按菜单等）在 P2 用 [FeatureAccessPolicy] 逐一覆盖。
 * 完整设置页在 P4 隐藏管理接入前保持可进入，否则伪装模式下没有恢复完整桌面的入口。
 */
class SurfaceActivityGuard {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val live = mutableListOf<WeakReference<Activity>>()
    private var controller: SurfaceModeController? = null
    private var startedCount = 0
    private var lastAllStoppedAt = Long.MIN_VALUE / 2
    private var lastStopWasScreenOff = false

    /** 本应用是否有页面处于可见（started）状态。 */
    val hasStartedActivity: Boolean get() = startedCount > 0

    /**
     * 本应用页面是否在前台，或刚刚因熄屏被停止。熄屏时系统停止页面和发出熄屏广播的先后不固定，
     * 只看 [hasStartedActivity] 会把“在桌面上熄屏”误判成“在第三方应用里熄屏”。
     * 宽限只给熄屏引起的停止（停止时屏幕已不可交互）；亮屏时切到别的应用不算。
     */
    fun ownActivityVisibleAround(nowElapsedMs: Long, windowMs: Long = OWN_STOP_GRACE_MS): Boolean =
        startedCount > 0 || (lastStopWasScreenOff && nowElapsedMs - lastAllStoppedAt < windowMs)

    fun attach(app: Application, controller: SurfaceModeController) {
        if (this.controller != null) return
        this.controller = controller
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                synchronized(live) { live += WeakReference(activity) }
                if (isBlocked(activity)) activity.finish()
            }

            override fun onActivityResumed(activity: Activity) {
                if (isBlocked(activity)) activity.finish()
            }

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(live) { live.removeAll { it.get() == null || it.get() === activity } }
            }

            override fun onActivityStarted(activity: Activity) {
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0) {
                    lastAllStoppedAt = android.os.SystemClock.elapsedRealtime()
                    val power = activity.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                    lastStopWasScreenOff = power?.isInteractive == false
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** 关闭当前模式下不允许的已打开页面。可在任意线程调用。 */
    fun enforceNow() {
        mainHandler.post {
            val snapshot = synchronized(live) { live.mapNotNull { it.get() } }
            snapshot.filter { !it.isFinishing && isBlocked(it) }.forEach { it.finish() }
        }
    }

    private fun isBlocked(activity: Activity): Boolean {
        val mode = controller?.mode?.value ?: return false
        val feature = featureOf(activity.javaClass.name) ?: return false
        return !FeatureAccessPolicy.isAllowed(mode, feature)
    }

    companion object {
        /** 独立 Activity → R08 功能。未列出的 Activity 不受页面级限制。 */
        val ACTIVITY_FEATURES: Map<String, SurfaceFeature> = mapOf(
            "com.flue.launcher.WatchStoreActivity" to SurfaceFeature.WATCH_STORE,
            "com.flue.launcher.ui.store.WatchStoreWebActivity" to SurfaceFeature.WATCH_STORE,
            "com.flue.launcher.BuiltInFileManagerActivity" to SurfaceFeature.FILE_MANAGER,
            "com.flue.launcher.CanvasAdjustActivity" to SurfaceFeature.ADVANCED_CANVAS,
            // R08、R32：电话、短信两种模式都公开；来电／通话界面不列入，任何模式都不能被关闭。
            "com.flue.launcher.surface.comm.PhoneActivity" to SurfaceFeature.TOOLS,
            "com.flue.launcher.surface.comm.MessagesActivity" to SurfaceFeature.TOOLS,
        ) + com.flue.launcher.surface.tools.SurfaceTool.entries.associate { it.className to SurfaceFeature.TOOLS }

        fun featureOf(activityClassName: String): SurfaceFeature? = ACTIVITY_FEATURES[activityClassName]

        /** 熄屏广播与页面停止的先后误差。 */
        const val OWN_STOP_GRACE_MS = 2_000L
    }
}
