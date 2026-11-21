package com.flue.launcher.surface.tools

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.tools.alarm.AlarmToolScreen
import com.flue.launcher.surface.tools.battery.BatteryToolScreen
import com.flue.launcher.surface.tools.calculator.CalculatorToolScreen
import com.flue.launcher.surface.tools.stopwatch.StopwatchToolScreen
import com.flue.launcher.surface.tools.timer.TimerToolScreen
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel

/**
 * 工具页面的共同外壳（R31、R25）：沿用 Flue 主题、界面缩放和屏幕形状；返回按钮与右滑返回关闭页面。
 *
 * 由到点通知的全屏入口打开时（[EXTRA_FROM_ALERT]）允许在锁屏上显示并点亮屏幕，便于直接关闭响铃。
 * 每次回到前台都从持久状态复算一次，进程被杀后重新打开也显示真实状态。
 */
abstract class ToolActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    @Composable
    protected abstract fun Content(onBack: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAlertFlags(intent)
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    Content(onBack = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAlertFlags(intent)
    }

    override fun onResume() {
        super.onResume()
        ToolAlerts.reconcile(this)
    }

    private fun applyAlertFlags(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_ALERT, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    companion object {
        const val EXTRA_FROM_ALERT = "com.flue.launcher.surface.tools.FROM_ALERT"

        /**
         * 桌面锁（R33）不拦正在响的闹钟、计时器：只认由到点提醒拉起、而且确实还在响的工具页，
         * 关掉响铃后再回到前台就照常被锁拦回桌面。
         */
        fun isRingingAlert(activity: android.app.Activity): Boolean {
            if (activity !is ToolActivity) return false
            if (activity.intent?.getBooleanExtra(EXTRA_FROM_ALERT, false) != true) return false
            val engine = ToolAlerts.engine(activity)
            return engine.ringing.value.isNotEmpty() ||
                engine.timer.value is com.flue.launcher.surface.tools.timer.TimerState.Fired
        }
    }
}

class BatteryToolActivity : ToolActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = BatteryToolScreen(onBack)
}

class AlarmToolActivity : ToolActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = AlarmToolScreen(onBack)
}

class TimerToolActivity : ToolActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = TimerToolScreen(onBack)
}

class StopwatchToolActivity : ToolActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = StopwatchToolScreen(onBack)
}

class CalculatorToolActivity : ToolActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = CalculatorToolScreen(onBack)
}
