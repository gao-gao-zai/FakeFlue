package com.flue.launcher.surface.lock

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.mode.SurfaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 桌面进入密码的锁状态（R33）。只在伪装模式使用：进入伪装、进程重启、熄屏后上锁；
 * 完整模式和教程预览不使用。只存在内存里，所以进程重建后总是重新上锁。
 *
 * 解开桌面密码不授予管理权限；它也不删除、替换或绕过 Android 系统锁——本应用页面只有在系统锁解开后才可见。
 */
class DesktopLock(
    private val store: () -> DesktopLockStore,
    private val mode: StateFlow<SurfaceMode>,
    scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _locked = MutableStateFlow(shouldLock())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    init {
        scope.launch {
            mode.drop(1).collect { _locked.value = shouldLock() }
        }
    }

    private fun shouldLock(): Boolean = mode.value == SurfaceMode.DISGUISE && store().enabled

    fun enabled(): Boolean = store().enabled

    fun read(): PinRead = store().read()

    /** 熄屏后重新上锁。 */
    fun onScreenOff() {
        if (shouldLock()) _locked.value = true
    }

    sealed interface Result {
        data object Unlocked : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Cooling(val remainingMs: Long) : Result

        /** 密文解不开：只能用管理密码恢复。 */
        data object Unreadable : Result
    }

    fun lockout(): LockoutState = store().lockout.normalize(clock())

    fun tryUnlock(digits: List<Int>): Result {
        val s = store()
        val now = clock()
        val state = s.lockout.normalize(now)
        if (state.lockedOut(now)) return Result.Cooling(state.remainingMs(now))
        val pin = when (val read = s.read()) {
            is PinRead.Available -> read.digits
            PinRead.NotSet -> {
                _locked.value = false
                return Result.Unlocked
            }
            PinRead.Unreadable -> return Result.Unreadable
        }
        if (digits == pin) {
            s.lockout = LockoutState()
            _locked.value = false
            return Result.Unlocked
        }
        val next = state.fail(now)
        s.lockout = next
        return if (next.lockedOut(now)) Result.Cooling(next.remainingMs(now)) else Result.Wrong(next.triesLeft)
    }

    /** 用管理密码验证后直接设置新密码；保存成功才解锁（R33）。 */
    fun resetWithManagement(digits: List<Int>): Boolean {
        if (!store().save(digits)) return false
        _locked.value = false
        return true
    }

    /**
     * 上锁期间打开本应用的其他页面（快捷方式、旧返回栈、通知）一律先回桌面，桌面上显示密码页。
     * 桌面本身和崩溃页不拦。
     */
    fun attach(app: Application, isExempt: (Activity) -> Boolean) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!_locked.value || isExempt(activity)) return
                runCatching { activity.startActivity(SurfaceHome.returnToFaceIntent(activity)) }
                activity.finish()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
