package com.flue.launcher.surface.publicsettings

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.EXTRA_SETTINGS_DESTINATION
import com.flue.launcher.SETTINGS_DESTINATION_SURFACE
import com.flue.launcher.SettingsActivity
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.secret.BackDragResult
import com.flue.launcher.surface.secret.ResetTapCounter
import com.flue.launcher.surface.secret.SecretChallengeOverlay
import com.flue.launcher.surface.secret.SecretInterruptionEffects
import com.flue.launcher.surface.secret.SecretSetupActivity
import com.flue.launcher.surface.secret.SecretSetupMode
import com.flue.launcher.surface.secret.readableSecret
import com.flue.launcher.surface.ui.reportModeSwitch
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel

/**
 * 伪装模式下内置“设置”入口打开的公开设置（R26）。完整设置不经过这里。
 *
 * 关于页同时承载两个隐藏入口：返回区域按住 2 秒后右拖 → 静默验证 → 管理菜单（R15）；
 * 下拖 → 本次流程点系统版本号 100 次 → 不验证旧密码设置新密码（R17）。
 * 两者都不留任何可见提示。
 */
class PublicSettingsActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    /** 退出外部前交还通信角色（R32）；恢复页完成退出后关闭本页，取消则停在管理菜单。 */
    private val commRestore = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    PublicSettingsContent()
                }
            }
        }
    }

    @Composable
    private fun PublicSettingsContent() {
        val runtime = remember { SurfaceRuntime.get(this) }
        val inManagement by runtime.managementSession.collectAsStateWithLifecycle()
        var aboutOpen by rememberSaveable { mutableStateOf(false) }
        var managementOpen by rememberSaveable { mutableStateOf(false) }
        var challenge by remember { mutableStateOf(false) }
        var resetCounting by remember { mutableStateOf(false) }
        val resetCounter = remember { ResetTapCounter() }

        fun stopResetCounting() {
            resetCounting = false
            resetCounter.clear()
        }

        // 熄屏、来电、模式改变会结束会话；页面跟着回到关于页，重建进程后也不会停在菜单上。
        LaunchedEffect(inManagement) { if (!inManagement) managementOpen = false }
        if (resetCounting) SecretInterruptionEffects(onInterrupted = ::stopResetCounting)

        when {
            managementOpen && inManagement -> {
                fun leave() {
                    runtime.endManagementSession()
                    managementOpen = false
                }
                BackHandler(onBack = ::leave)
                ManagementMenuPage(
                    onBack = ::leave,
                    onOpenDisguiseSettings = {
                        startActivity(
                            Intent(this, SettingsActivity::class.java)
                                .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_SURFACE),
                        )
                    },
                    onOpenFullSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onChangeSecret = { startActivity(SecretSetupActivity.intent(this, SecretSetupMode.MAINTAIN)) },
                    onSwitch = ::switchMode,
                    onOpenTutorial = {
                        startActivity(com.flue.launcher.surface.tutorial.TutorialActivity.intent(this))
                    },
                )
            }

            aboutOpen -> {
                fun closeAbout() {
                    stopResetCounting()
                    aboutOpen = false
                }
                BackHandler(enabled = !challenge, onBack = ::closeAbout)
                Box(Modifier.fillMaxSize()) {
                    DeviceAboutPage(
                        onBack = ::closeAbout,
                        onBuildNumberTap = {
                            if (resetCounting && resetCounter.tap()) {
                                stopResetCounting()
                                runtime.grantSecretReset()
                                startActivity(SecretSetupActivity.intent(this@PublicSettingsActivity, SecretSetupMode.RESET))
                            }
                        },
                        onBackAreaDrag = { result ->
                            stopResetCounting()
                            when (result) {
                                BackDragResult.MANAGEMENT -> challenge = true
                                BackDragResult.RESET -> resetCounting = true
                                BackDragResult.NONE -> Unit
                            }
                        },
                        onPageSwipe = ::stopResetCounting,
                    )
                    if (challenge) {
                        val secret = remember { runtime.readableSecret() }
                        SecretChallengeOverlay(
                            secret = secret,
                            onSuccess = {
                                challenge = false
                                runtime.beginManagementSession()
                                managementOpen = true
                            },
                            onFailure = { challenge = false },
                            onCancel = { challenge = false },
                        )
                    }
                }
            }

            else -> PublicSettingsListPage(onBack = ::finish, onOpenAbout = { aboutOpen = true })
        }
    }

    private fun switchMode(target: SurfaceMode) {
        if (target == SurfaceMode.EXTERNAL && com.flue.launcher.surface.comm.CommExit.needsRestoreFirst(this)) {
            commRestore.launch(com.flue.launcher.surface.comm.CommRestoreActivity.intent(this))
            return
        }
        val result = SurfaceRuntime.get(this).controller.switchTo(target)
        reportModeSwitch(result, target)
        if (result != ModeSwitchResult.Success && result != ModeSwitchResult.AlreadyInMode) return
        // 恢复完整桌面后回到表盘；切到外部桌面时控制器已经把外部桌面带到前台。
        if (target == SurfaceMode.FULL) runCatching { startActivity(SurfaceHome.returnToFaceIntent(this)) }
        finish()
    }
}
