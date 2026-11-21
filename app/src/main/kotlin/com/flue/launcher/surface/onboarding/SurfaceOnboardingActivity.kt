package com.flue.launcher.surface.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.LauncherActivity
import com.flue.launcher.R
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.entry.DisguiseEntry
import com.flue.launcher.surface.entry.EntryOrigin
import com.flue.launcher.surface.mode.DefaultHomeResult
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.ui.descriptionRes
import com.flue.launcher.surface.ui.labelRes
import com.flue.launcher.surface.ui.openDefaultHomeSettings
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.surface.ui.reportModeSwitch
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.settings.agent.AgentSettingsScreen
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel

/**
 * R18 首次使用路径：欢迎 → 选择完整／伪装 → 公共默认桌面引导（可跳过）。
 *
 * 默认桌面只有读回系统实际 HOME 为本应用后才显示成功；打开过设置页、取消或跳过都不算成功。
 * 选择伪装桌面时先完成专属配置和教程（[DisguiseEntry]），进入伪装后回到默认桌面这一步。
 */
class SurfaceOnboardingActivity : FlueComponentActivity() {

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
                    OnboardingFlow(
                        resumeDefaultHome = intent.getBooleanExtra(EXTRA_RESUME_DEFAULT_HOME, false),
                        onFinished = ::finishOnboarding,
                    )
                }
            }
        }
    }

    private fun finishOnboarding(chosen: SurfaceMode) {
        val state = SurfaceRuntime.get(this).stateStore
        state.welcomeCompleted = true
        if (chosen == SurfaceMode.FULL) {
            Toast.makeText(this, R.string.onboarding_full_hint, Toast.LENGTH_LONG).show()
        }
        startActivity(
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    companion object {
        private const val EXTRA_RESUME_DEFAULT_HOME = "com.flue.launcher.extra.ONBOARDING_RESUME_DEFAULT_HOME"

        /** 选择伪装后走完配置和教程、已经进入伪装：回到默认桌面引导这一步（R18）。 */
        fun resumeDefaultHome(context: Context) {
            context.startActivity(
                Intent(context, SurfaceOnboardingActivity::class.java)
                    .putExtra(EXTRA_RESUME_DEFAULT_HOME, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }

        fun start(context: Context) {
            context.startActivity(
                Intent(context, SurfaceOnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private enum class OnboardingStep { WELCOME, CHOOSE_MODE, DEFAULT_HOME }

@Composable
private fun OnboardingFlow(resumeDefaultHome: Boolean, onFinished: (SurfaceMode) -> Unit) {
    val context = LocalContext.current
    val activity = context as? FlueComponentActivity
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    var step by rememberSaveable {
        mutableStateOf(if (resumeDefaultHome) OnboardingStep.DEFAULT_HOME else OnboardingStep.WELCOME)
    }
    var chosen by rememberSaveable { mutableStateOf(runtime.controller.mode.value) }
    var skipRequested by rememberSaveable { mutableStateOf(false) }

    fun goBack() {
        when (step) {
            OnboardingStep.WELCOME -> activity?.finish()
            OnboardingStep.CHOOSE_MODE -> step = OnboardingStep.WELCOME
            OnboardingStep.DEFAULT_HOME -> {
                skipRequested = false
                step = OnboardingStep.CHOOSE_MODE
            }
        }
    }
    remember(activity) {
        activity?.onBackPressedDispatcher?.addCallback(activity) { goBack() }
    }

    when (step) {
        OnboardingStep.WELCOME -> AgentSettingsScreen(
            stateKey = OnboardingStep.WELCOME,
            title = stringResource(R.string.onboarding_welcome_title),
            subtitle = stringResource(R.string.onboarding_welcome_body),
            onBack = ::goBack,
        ) {
            item(key = "start") {
                AgentSettingsGroup {
                    AgentSettingsEntry(
                        title = stringResource(R.string.onboarding_start),
                        onClick = { step = OnboardingStep.CHOOSE_MODE },
                        last = true,
                    )
                }
            }
        }

        OnboardingStep.CHOOSE_MODE -> AgentSettingsScreen(
            stateKey = OnboardingStep.CHOOSE_MODE,
            title = stringResource(R.string.onboarding_choose_title),
            onBack = ::goBack,
        ) {
            item(key = "modes") {
                AgentSettingsGroup {
                    listOf(SurfaceMode.FULL, SurfaceMode.DISGUISE).forEachIndexed { index, mode ->
                        AgentSettingsEntry(
                            title = stringResource(mode.labelRes()),
                            subtitle = stringResource(mode.descriptionRes()),
                            onClick = {
                                val result = runtime.controller.switchTo(mode)
                                when {
                                    result == ModeSwitchResult.Success || result == ModeSwitchResult.AlreadyInMode -> {
                                        chosen = mode
                                        step = OnboardingStep.DEFAULT_HOME
                                    }
                                    // 伪装还缺专属配置或教程：先去完成，走完后回到默认桌面引导（R18）。
                                    result is ModeSwitchResult.Blocked -> DisguiseEntry.openRequirement(
                                        context,
                                        result.requirement,
                                        EntryOrigin.ONBOARDING,
                                    )
                                    else -> context.reportModeSwitch(result, mode)
                                }
                            },
                            last = index == 1,
                        )
                    }
                }
            }
        }

        OnboardingStep.DEFAULT_HOME -> {
            val resumeTick = rememberResumeTick()
            val confirmed = remember(resumeTick) { SurfaceHome.isSelfDefaultHome(context) }
            AgentSettingsScreen(
                stateKey = OnboardingStep.DEFAULT_HOME,
                title = stringResource(R.string.onboarding_home_title),
                subtitle = stringResource(
                    if (skipRequested) R.string.onboarding_home_skip_hint else R.string.onboarding_home_body,
                ),
                onBack = ::goBack,
            ) {
                item(key = "home_status") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(
                                if (confirmed) R.string.onboarding_home_confirmed
                                else R.string.onboarding_home_not_confirmed,
                            ),
                            last = true,
                        )
                    }
                }
                item(key = "home_actions") {
                    AgentSettingsGroup {
                        if (!confirmed) {
                            AgentSettingsEntry(
                                title = stringResource(R.string.onboarding_home_open_settings),
                                onClick = { context.openDefaultHomeSettings() },
                            )
                        }
                        AgentSettingsEntry(
                            title = stringResource(R.string.onboarding_home_done),
                            enabled = confirmed,
                            onClick = {
                                // 完成前再读一次，避免用户在别处改过默认桌面。
                                if (SurfaceHome.isSelfDefaultHome(context)) {
                                    runtime.stateStore.defaultHomeResult = DefaultHomeResult.CONFIRMED
                                    onFinished(chosen)
                                }
                            },
                            last = confirmed,
                        )
                        if (!confirmed) {
                            AgentSettingsEntry(
                                title = stringResource(
                                    if (skipRequested) R.string.onboarding_home_skip_confirm
                                    else R.string.onboarding_home_skip,
                                ),
                                onClick = {
                                    if (!skipRequested) {
                                        // 第一次点击只显示跳过的后果说明。
                                        skipRequested = true
                                    } else {
                                        runtime.stateStore.defaultHomeResult = DefaultHomeResult.SKIPPED
                                        onFinished(chosen)
                                    }
                                },
                                last = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
