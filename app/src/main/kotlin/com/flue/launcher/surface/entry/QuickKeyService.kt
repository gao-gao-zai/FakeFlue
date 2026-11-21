package com.flue.launcher.surface.entry

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.flue.launcher.surface.SurfaceRuntime

/**
 * 实体按键快速进入伪装（R34）。单独的无障碍服务，只在用户在完整设置里配置了按键并自己开启后工作；
 * 原版的无障碍服务不受影响。
 *
 * 只观察配置的那个键，不拦截任何按键（总是返回 false），按键原本的系统功能照常。
 * 识别出配置的方式后打开 [QuickEntryActivity]，走统一门槛。
 */
class QuickKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val detector = KeyPatternDetector()
    private val timer = Runnable { deliver(detector.onTimer(SystemClock.uptimeMillis())) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val state = SurfaceRuntime.get(this).stateStore
        val code = state.quickKeyCode
        if (code == KeyEvent.KEYCODE_UNKNOWN || event.keyCode != code || event.repeatCount > 0) return false
        val result = when (event.action) {
            KeyEvent.ACTION_DOWN -> detector.onDown(event.eventTime)
            KeyEvent.ACTION_UP -> detector.onUp(event.eventTime)
            else -> null
        }
        deliver(result)
        return false
    }

    private fun deliver(result: KeyPattern?) {
        handler.removeCallbacks(timer)
        detector.deadline?.let { handler.postAtTime(timer, it) }
        val wanted = SurfaceRuntime.get(this).stateStore.quickKeyPattern
        if (result != null && result == wanted) {
            runCatching { startActivity(QuickEntryActivity.intent(this)) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val ours = ComponentName(context, QuickKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
        }
    }
}
