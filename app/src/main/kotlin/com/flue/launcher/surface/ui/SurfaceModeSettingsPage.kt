package com.flue.launcher.surface.ui

import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.onboarding.SurfaceOnboardingActivity
import com.flue.launcher.surface.entry.DisguiseEntry
import com.flue.launcher.surface.entry.EntryOrigin
import com.flue.launcher.surface.secret.SecretRead
import com.flue.launcher.surface.setup.DisguiseSetupActivity
import com.flue.launcher.surface.tutorial.TutorialActivity
import com.flue.launcher.surface.tutorial.TutorialRules
import com.flue.launcher.surface.secret.SecretSetupActivity
import com.flue.launcher.surface.secret.SecretSetupMode
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.settings.agent.AgentSettingsScreen
import com.flue.launcher.ui.style.FlueSwitchIndicator
import kotlinx.coroutines.launch

/**
 * 设置 → 桌面模式（R05、R07）。所有模式切换都经过 [com.flue.launcher.surface.mode.SurfaceModeController]。
 *
 * “完整设置”（进入设置页）和“恢复完整桌面”（切换模式）是两个不同操作，这里只放后者。
 */
@Composable
internal fun SurfaceModeSettingsPage(
    onBack: () -> Unit,
    onOpenScreenFit: () -> Unit,
    onOpenPublicApps: () -> Unit,
    onOpenAboutOverrides: () -> Unit,
    onOpenNotificationApps: () -> Unit,
    onOpenPublicOverrides: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSystemControl: () -> Unit,
) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val controller = runtime.controller
    val mode by controller.mode.collectAsState()
    val resumeTick = rememberResumeTick()
    var targetRevision by remember { mutableStateOf(0) }
    var pickerOpen by remember { mutableStateOf(false) }

    val externalTarget = remember(targetRevision, resumeTick) {
        controller.externalTarget?.let(ComponentName::unflattenFromString)
    }
    val candidates = remember(pickerOpen, resumeTick) {
        if (pickerOpen) SurfaceHome.externalHomeCandidates(context) else emptyList()
    }
    val defaultHome = remember(resumeTick, mode) { context.defaultHomeDescription() }
    var publicCount by remember { mutableStateOf<Int?>(null) }
    var notificationAllowCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(resumeTick) {
        publicCount = runtime.disguisePublicApps().size
        notificationAllowCount = runtime.disguiseNotificationAllowedApps().size
    }
    val keepAppOnScreenOff by runtime.screenOffKeepApp.collectAsState()
    val secretState = remember(resumeTick) { runtime.secretStore.read() }
    val setupDone = remember(resumeTick) { runtime.disguiseSetupDone().size }
    val tutorialVersion by runtime.tutorialVersion.collectAsState()
    val scope = rememberCoroutineScope()

    fun switch(target: SurfaceMode) {
        context.reportModeSwitch(controller.switchTo(target), target)
    }

    AgentSettingsScreen(
        stateKey = "surface_mode_settings",
        title = stringResource(R.string.surface_settings_title),
        subtitle = stringResource(R.string.surface_settings_subtitle),
        onBack = onBack,
    ) {
        item(key = "surface_current") {
            AgentSettingsGroup(stringResource(R.string.surface_mode_current)) {
                AgentSettingsEntry(
                    title = stringResource(mode.labelRes()),
                    subtitle = stringResource(mode.descriptionRes()),
                    last = true,
                )
            }
        }
        item(key = "surface_switch") {
            AgentSettingsGroup(stringResource(R.string.surface_section_switch)) {
                if (mode != SurfaceMode.DISGUISE) {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_action_enter_disguise),
                        subtitle = stringResource(R.string.surface_mode_disguise_desc),
                        // 缺专属配置或教程时打开对应页面，走完再进入（R18、R22）。
                        onClick = { DisguiseEntry.request(context, EntryOrigin.SETTINGS) },
                    )
                }
                if (mode != SurfaceMode.FULL) {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_action_restore_full),
                        subtitle = stringResource(R.string.surface_mode_full_desc),
                        onClick = { switch(SurfaceMode.FULL) },
                    )
                }
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_action_return_external),
                    subtitle = externalTarget?.let(context::homeLabel)
                        ?: stringResource(R.string.surface_external_target_none),
                    onClick = { switch(SurfaceMode.EXTERNAL) },
                    last = true,
                )
            }
        }
        item(key = "surface_disguise") {
            AgentSettingsGroup(stringResource(R.string.surface_mode_disguise)) {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_setup_title),
                    subtitle = stringResource(
                        R.string.surface_setup_entry_sub,
                        setupDone,
                        com.flue.launcher.surface.setup.DisguiseSetupStep.entries.size,
                    ),
                    onClick = { context.startActivity(DisguiseSetupActivity.intent(context)) },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_tutorial_title),
                    subtitle = stringResource(
                        if (TutorialRules.passes(tutorialVersion)) R.string.surface_tutorial_entry_passed
                        else R.string.surface_tutorial_entry_pending,
                    ),
                    onClick = { context.startActivity(TutorialActivity.intent(context)) },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_perm_title),
                    subtitle = stringResource(R.string.surface_perm_entry_sub),
                    onClick = onOpenPermissions,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_sys_title),
                    subtitle = stringResource(R.string.surface_sys_entry_sub),
                    onClick = onOpenSystemControl,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_secret_title),
                    subtitle = stringResource(
                        when (secretState) {
                            is SecretRead.Available -> R.string.surface_secret_entry_set
                            SecretRead.NotSet -> R.string.surface_secret_entry_unset
                            SecretRead.Unreadable -> R.string.surface_secret_entry_unreadable
                        },
                    ),
                    onClick = {
                        val setupMode = if (secretState is SecretRead.Available) SecretSetupMode.MAINTAIN else SecretSetupMode.INITIAL
                        context.startActivity(SecretSetupActivity.intent(context, setupMode))
                    },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_public_apps_title),
                    subtitle = publicCount?.let { stringResource(R.string.surface_public_apps_entry_sub, it) },
                    onClick = onOpenPublicApps,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_public_overrides_title),
                    subtitle = stringResource(R.string.surface_public_overrides_entry_sub),
                    onClick = onOpenPublicOverrides,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_notification_allow_title),
                    subtitle = notificationAllowCount?.let { stringResource(R.string.surface_notification_allow_entry_sub, it) },
                    onClick = onOpenNotificationApps,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_screen_off_keep_app_title),
                    subtitle = stringResource(R.string.surface_screen_off_keep_app_sub),
                    onClick = { scope.launch { runtime.setScreenOffKeepApp(!keepAppOnScreenOff) } },
                    trailing = { FlueSwitchIndicator(checked = keepAppOnScreenOff) },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.about_overrides_title),
                    subtitle = stringResource(R.string.about_overrides_entry_sub),
                    onClick = onOpenAboutOverrides,
                    last = true,
                )
            }
        }
        item(key = "surface_external") {
            AgentSettingsGroup(stringResource(R.string.surface_section_external)) {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_external_target),
                    subtitle = externalTarget?.let(context::homeLabel)
                        ?: stringResource(R.string.surface_external_target_none),
                    onClick = { pickerOpen = !pickerOpen },
                    last = !pickerOpen,
                )
                if (pickerOpen) {
                    if (candidates.isEmpty()) {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_external_target_empty),
                            last = true,
                        )
                    }
                    candidates.forEachIndexed { index, component ->
                        AgentSettingsEntry(
                            title = context.homeLabel(component),
                            subtitle = component.packageName,
                            onClick = {
                                if (controller.setExternalTarget(component.flattenToString())) {
                                    targetRevision++
                                    pickerOpen = false
                                } else {
                                    Toast.makeText(context, R.string.surface_target_rejected, Toast.LENGTH_SHORT).show()
                                }
                            },
                            last = index == candidates.lastIndex,
                        )
                    }
                }
            }
        }
        item(key = "surface_system") {
            AgentSettingsGroup(stringResource(R.string.surface_section_system)) {
                // R25：伪装桌面管理提供和完整设置同一个屏幕适配入口。
                AgentSettingsEntry(
                    title = stringResource(R.string.screen_fit_title),
                    subtitle = stringResource(R.string.surface_screen_fit_entry_sub),
                    onClick = onOpenScreenFit,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_default_home),
                    subtitle = defaultHome,
                    onClick = { context.openDefaultHomeSettings() },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_rerun_welcome),
                    onClick = {
                        context.startActivity(Intent(context, SurfaceOnboardingActivity::class.java))
                    },
                    last = true,
                )
            }
        }
    }
}
