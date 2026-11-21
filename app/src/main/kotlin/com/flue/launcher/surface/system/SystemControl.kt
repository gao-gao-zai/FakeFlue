package com.flue.launcher.surface.system

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.mode.SurfaceModeController
import com.flue.launcher.surface.mode.SurfaceStateStore
import com.flue.launcher.surface.secret.SecretInterruptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 系统控制（R27 交接、R29、R30）在应用里的接线：收集事实、交给 [NavigationGuard]、
 * 维护设备系统页放行、隐藏伪装自有页面的状态栏、读恢复记录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemControl(
    private val app: Application,
    private val controller: SurfaceModeController,
    private val stateStore: SurfaceStateStore,
    private val scope: CoroutineScope,
    fallbackHome: () -> String?,
    connect: () -> GuardConnection = { RootGuardConnection(app) },
) {
    private val serial = Dispatchers.IO.limitedParallelism(1)

    val guard = NavigationGuard(connect, rootConfirmed = { stateStore.rootConfirmed }, fallbackHome = fallbackHome)

    val status: StateFlow<NavGuardStatus> get() = guard.status

    /** R35：导航限制就绪单独计时；Applying、失败等中间状态不算结论。 */
    private fun reportStartup(status: NavGuardStatus) {
        when (status) {
            NavGuardStatus.Active -> com.flue.launcher.surface.startup.StartupTimeline.mark(
                com.flue.launcher.surface.startup.StartupTimeline.Mark.GUARD_ACTIVE,
            )
            NavGuardStatus.NotNeeded, NavGuardStatus.Released ->
                com.flue.launcher.surface.startup.StartupTimeline.guardNotNeeded()
            else -> Unit
        }
    }

    private val handoff = HandoffTracker()
    private val _handoffActive = MutableStateFlow(false)
    val handoffActive: StateFlow<Boolean> = _handoffActive.asStateFlow()

    /** 只在伪装模式里轮询音频模式判断通话、响铃，不申请电话权限。 */
    private val inCall = controller.mode.flatMapLatest { mode ->
        if (mode == SurfaceMode.DISGUISE) {
            flow {
                while (true) {
                    emit(SecretInterruptions.inCall(app))
                    delay(CALL_POLL_MS)
                }
            }
        } else {
            flowOf(false)
        }
    }.distinctUntilChanged()

    init {
        scope.launch(serial) {
            // R35：建目录也是磁盘操作，放在守护的串行线程上，不占 Application.onCreate 的主线程。
            RootGuardConnection.stateDir(app).mkdirs()
            // 本应用的通话服务一有来电／通话就放行，不等下一次音频轮询（R29 通话放行，R32）。
            combine(controller.mode, inCall, _handoffActive, com.flue.launcher.surface.comm.CallRegistry.hasLiveCall) { mode, call, handoff, ownCall ->
                NavFacts(mode, call || ownCall, handoff)
            }
                .distinctUntilChanged()
                .collect {
                    guard.update(it)
                    reportStartup(guard.status.value)
                }
        }
        scope.launch {
            controller.mode.collect { mode ->
                endHandoff()
                if (!NavPolicy.hideOwnStatusBar(mode)) restoreStatusBars()
            }
        }
    }

    /** 打开四类设备系统页之前调用（R27）。 */
    fun beginHandoff() {
        if (controller.mode.value != SurfaceMode.DISGUISE) return
        handoff.begin()
        _handoffActive.value = true
    }

    /** 本应用页面回到前台、熄屏、模式改变、系统页没打开时调用。 */
    fun endHandoff() {
        if (handoff.end()) _handoffActive.value = false
    }

    /** 完整设置里的“重新连接”。 */
    fun retry() {
        scope.launch(serial) { guard.retry() }
    }

    fun recoveryRecords(): List<RecoveryRecord> =
        RecoveryRecord.parseAll(runCatching { RootGuardConnection.recordFile(app).readText() }.getOrNull())

    fun clearRecoveryRecords() {
        RootGuardConnection.recordFile(app).delete()
    }

    private val hiddenBars = mutableListOf<WeakReference<Activity>>()

    fun attach() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // Back 回原页面或 Home 回桌面都会让本应用某个页面回到前台：结束这次放行。
                endHandoff()
                if (NavPolicy.hideOwnStatusBar(controller.mode.value)) hideStatusBar(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                synchronized(hiddenBars) { hiddenBars.removeAll { it.get() == null || it.get() === activity } }
            }
        })
    }

    private fun hideStatusBar(activity: Activity) {
        val window = activity.window ?: return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        synchronized(hiddenBars) {
            if (hiddenBars.none { it.get() === activity }) hiddenBars += WeakReference(activity)
        }
    }

    /** 离开伪装：只恢复本应用隐藏过的状态栏，原版页面本来就不隐藏。 */
    private fun restoreStatusBars() {
        val activities = synchronized(hiddenBars) {
            hiddenBars.mapNotNull { it.get() }.also { hiddenBars.clear() }
        }
        activities.forEach { activity ->
            activity.runOnUiThread {
                val window = activity.window ?: return@runOnUiThread
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    private companion object {
        const val CALL_POLL_MS = 1_000L
    }
}
