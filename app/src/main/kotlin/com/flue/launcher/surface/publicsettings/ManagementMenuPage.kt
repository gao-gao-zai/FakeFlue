package com.flue.launcher.surface.publicsettings

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.homeLabel
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup

/**
 * 隐藏管理菜单（R15、R16）。只在本次验证后的管理会话里显示；返回即结束会话。
 *
 * “临时完整设置”保持伪装模式，设置页按伪装配置读写；“恢复完整桌面”才切换模式。
 */
@Composable
internal fun ManagementMenuPage(
    onBack: () -> Unit,
    onOpenDisguiseSettings: () -> Unit,
    onOpenFullSettings: () -> Unit,
    onChangeSecret: () -> Unit,
    onSwitch: (SurfaceMode) -> Unit,
) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val externalTarget = remember(resumeTick) {
        SurfaceRuntime.get(context).controller.externalTarget?.let(ComponentName::unflattenFromString)
    }
    FfluePageScaffold(
        stateKey = "surface_management",
        title = stringResource(R.string.surface_manage_title),
        subtitle = stringResource(R.string.surface_manage_subtitle),
        onBack = onBack,
    ) {
        item(key = "manage_settings") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_manage_disguise_settings),
                    subtitle = stringResource(R.string.surface_manage_disguise_settings_sub),
                    onClick = onOpenDisguiseSettings,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_manage_full_settings),
                    subtitle = stringResource(R.string.surface_manage_full_settings_sub),
                    onClick = onOpenFullSettings,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_manage_change_secret),
                    onClick = onChangeSecret,
                    last = true,
                )
            }
        }
        item(key = "manage_leave") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_action_restore_full),
                    subtitle = stringResource(R.string.surface_mode_full_desc),
                    onClick = { onSwitch(SurfaceMode.FULL) },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_action_return_external),
                    subtitle = externalTarget?.let(context::homeLabel)
                        ?: stringResource(R.string.surface_external_target_none),
                    onClick = { onSwitch(SurfaceMode.EXTERNAL) },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_manage_exit),
                    onClick = onBack,
                    last = true,
                )
            }
        }
    }
}
