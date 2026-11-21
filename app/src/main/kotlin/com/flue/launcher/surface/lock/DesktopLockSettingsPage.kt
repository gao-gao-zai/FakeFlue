package com.flue.launcher.surface.lock

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.settings.agent.AgentSettingsScreen
import com.flue.launcher.ui.theme.LauncherTheme

private enum class LockSettingsStage { LIST, WARNING, SETUP }

/**
 * 完整设置／有效隐藏管理里的“桌面进入密码”（R33）：设置、更改、关闭。
 * 设置或更改前必须先确认“这个密码可能并不安全”；两次一致且保存成功才启用。
 */
@Composable
fun DesktopLockSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val store = remember { runtime.desktopLockStore }
    var stage by rememberSaveable { mutableStateOf(LockSettingsStage.LIST) }
    var revision by remember { mutableIntStateOf(0) }
    val state = remember(revision) { store.read() }

    when (stage) {
        LockSettingsStage.LIST -> AgentSettingsScreen(
            stateKey = "surface_desktop_lock",
            title = stringResource(R.string.surface_lock_settings_title),
            subtitle = stringResource(R.string.surface_lock_settings_sub),
            onBack = onBack,
        ) {
            item(key = "status") {
                AgentSettingsGroup {
                    AgentSettingsEntry(
                        title = stringResource(
                            when (state) {
                                PinRead.NotSet -> R.string.surface_lock_status_off
                                is PinRead.Available -> R.string.surface_lock_status_on
                                PinRead.Unreadable -> R.string.surface_lock_status_unreadable
                            },
                        ),
                    )
                    AgentSettingsEntry(
                        title = stringResource(
                            if (state == PinRead.NotSet) R.string.surface_lock_enable else R.string.surface_lock_change,
                        ),
                        onClick = { stage = LockSettingsStage.WARNING },
                        last = state == PinRead.NotSet,
                    )
                    if (state != PinRead.NotSet) {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_lock_disable),
                            onClick = {
                                if (store.disable()) revision++
                            },
                            last = true,
                        )
                    }
                }
            }
        }

        LockSettingsStage.WARNING -> AgentSettingsScreen(
            stateKey = "surface_desktop_lock_warning",
            title = stringResource(R.string.surface_lock_warning_title),
            subtitle = stringResource(R.string.surface_lock_warning_body),
            onBack = { stage = LockSettingsStage.LIST },
        ) {
            item(key = "actions") {
                AgentSettingsGroup {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_lock_warning_accept),
                        onClick = { stage = LockSettingsStage.SETUP },
                    )
                    AgentSettingsEntry(
                        title = stringResource(R.string.display_cancel),
                        onClick = { stage = LockSettingsStage.LIST },
                        last = true,
                    )
                }
            }
        }

        LockSettingsStage.SETUP -> {
            BackHandler { stage = LockSettingsStage.LIST }
            Box(
                Modifier.fillMaxSize().background(LauncherTheme.style.screenBackground),
                contentAlignment = Alignment.Center,
            ) {
                PinSetupPanel(
                    title = stringResource(R.string.surface_lock_setup_title),
                    save = { digits ->
                        store.save(digits).also { ok ->
                            if (ok) {
                                Toast.makeText(context, R.string.surface_lock_saved, Toast.LENGTH_SHORT).show()
                                revision++
                                stage = LockSettingsStage.LIST
                            }
                        }
                    },
                    onCancel = { stage = LockSettingsStage.LIST },
                )
            }
        }
    }
}
