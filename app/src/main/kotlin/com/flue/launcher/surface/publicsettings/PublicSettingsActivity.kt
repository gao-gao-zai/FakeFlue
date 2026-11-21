package com.flue.launcher.surface.publicsettings

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.EXTRA_SETTINGS_DESTINATION
import com.flue.launcher.SETTINGS_DESTINATION_SURFACE
import com.flue.launcher.SettingsActivity
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel

/**
 * 伪装模式下内置“设置”入口打开的公开设置（R26）。完整设置不经过这里。
 */
class PublicSettingsActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    var aboutOpen by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = aboutOpen) { aboutOpen = false }
                    if (aboutOpen) {
                        DeviceAboutPage(
                            onBack = { aboutOpen = false },
                            onBuildNumberTap = rememberTemporaryManagementTap(::openManagement),
                        )
                    } else {
                        PublicSettingsListPage(onBack = ::finish, onOpenAbout = { aboutOpen = true })
                    }
                }
            }
        }
    }

    /**
     * 临时：P3 五区域密码之前离开伪装的唯一入口，打开完整设置的桌面模式页。
     * P3 接入隐藏管理菜单后删除。
     *
     * 同时开始本次管理流程（熄屏或模式改变即失效），期间可编辑伪装小组件（R11）。
     */
    private fun openManagement() {
        com.flue.launcher.surface.SurfaceRuntime.get(this).beginManagementSession()
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_SURFACE),
        )
        finish()
    }
}
