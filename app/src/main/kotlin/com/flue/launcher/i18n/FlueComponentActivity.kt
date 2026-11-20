package com.flue.launcher.i18n

import android.content.Context
import androidx.activity.ComponentActivity
import com.flue.launcher.diagnostics.DiagnosticsController
import com.flue.launcher.status.DoNotDisturbState
import com.flue.launcher.surface.display.DisplayAdaptation

/**
 * 所有 Flue Activity 的基类。
 *
 * 覆写 [attachBaseContext] 以注入语言覆盖和应用独立 DPI，使 [stringResource]、
 * [getString] 等 API 返回正确语言的文本。
 */
abstract class FlueComponentActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // R24：语言之后再套应用独立 DPI，新页面第一帧就是最终密度。
        super.attachBaseContext(DisplayAdaptation.applyDensity(FlueLocaleManager.applyLocale(newBase)))
    }

    override fun onResume() {
        super.onResume()
        DoNotDisturbState.refresh(this)
        DiagnosticsController.onResume(this)
    }

    override fun onPause() {
        DiagnosticsController.onPause(this)
        super.onPause()
    }
}
