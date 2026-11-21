package com.flue.launcher.surface.tutorial

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.entry.DisguiseEntry
import com.flue.launcher.surface.entry.EntryOrigin
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.secret.SecretCancel
import com.flue.launcher.surface.secret.SecretInterruptions
import com.flue.launcher.surface.secret.readableSecret
import com.flue.launcher.surface.setup.DisguiseSetupActivity
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import kotlinx.coroutines.delay

/**
 * 强制教程（R20–R22）：三关带提示教学，然后是一轮无提示独立练习。
 *
 * 全程在预览环境里进行，不切换正式模式、不改正式配置；只有最终练习通过才写入当前版本成绩，
 * 然后按 [EntryOrigin] 继续原来的进入请求。取消保留原有成绩和配置。
 * 进程重建后最终练习从头开始，不拼接不同轮的成绩。
 */
class TutorialActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = SurfaceRuntime.get(this)
        if (runtime.controller.configScope.value == ConfigScope.DISGUISE && !runtime.managementSession.value) {
            finish()
            return
        }
        val origin = EntryOrigin.from(intent)
        val secret = runtime.readableSecret()
        if (secret == null) {
            // 教学用真实密码练习验证；没有可读密码先去配置（R19）。
            Toast.makeText(this, R.string.surface_tutorial_need_secret, Toast.LENGTH_SHORT).show()
            startActivity(DisguiseSetupActivity.intent(this, origin))
            finish()
            return
        }
        val sameProcess = savedInstanceState?.getString(STATE_TOKEN) == runtime.processToken
        val startVersion = runtime.tutorialVersion.value
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    // ADB 跳过（R22）：成绩在教程进行中被更新时，按原请求继续。
                    val version by runtime.tutorialVersion.collectAsStateWithLifecycle()
                    LaunchedEffect(version) {
                        if (version != startVersion && TutorialRules.passes(version)) continueAfterPass(origin)
                    }
                    TutorialFlow(
                        vm = vm,
                        secret = secret,
                        origin = origin,
                        restartPractice = !sameProcess,
                        onPassed = {
                            runtime.recordTutorialPassed()
                            continueAfterPass(origin)
                        },
                        onLeave = ::finish,
                    )
                }
            }
        }
    }

    private fun continueAfterPass(origin: EntryOrigin) {
        if (isFinishing) return
        if (origin == EntryOrigin.NONE) {
            Toast.makeText(this, R.string.surface_tutorial_passed, Toast.LENGTH_SHORT).show()
        } else {
            DisguiseEntry.request(this, origin)
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TOKEN, SurfaceRuntime.get(this).processToken)
    }

    companion object {
        private const val STATE_TOKEN = "tutorial_token"

        fun intent(context: Context, origin: EntryOrigin = EntryOrigin.NONE): Intent =
            Intent(context, TutorialActivity::class.java).putExtra(EntryOrigin.EXTRA, origin.name)
    }
}

private enum class TutorialStage { INTRO, LEVEL_INTRO, LEVEL, LEVEL_DONE, PRACTICE_INTRO, PRACTICE, ASK_RELEARN }

@Composable
private fun TutorialFlow(
    vm: LauncherViewModel,
    secret: List<Int>,
    origin: EntryOrigin,
    restartPractice: Boolean,
    onPassed: () -> Unit,
    onLeave: () -> Unit,
) {
    var stageName by rememberSaveable { mutableStateOf(TutorialStage.INTRO.name) }
    var levelName by rememberSaveable { mutableStateOf(TeachLevel.HIDDEN_DRAWER.name) }
    var reasonName by rememberSaveable { mutableStateOf<String?>(null) }
    // 进程重建：最终练习从说明页重来；教学关卡不保存中间状态，从本关开头重来。
    remember {
        if (restartPractice) {
            when (stageName) {
                TutorialStage.PRACTICE.name -> stageName = TutorialStage.PRACTICE_INTRO.name
                TutorialStage.LEVEL.name -> stageName = TutorialStage.LEVEL_INTRO.name
            }
        }
        true
    }
    val stage = TutorialStage.valueOf(stageName)
    val level = TeachLevel.valueOf(levelName)
    fun go(next: TutorialStage) {
        stageName = next.name
    }
    val apps by vm.catalogApps.collectAsStateWithLifecycle()
    val cancelLabel = if (origin == EntryOrigin.NONE) R.string.surface_tutorial_leave else R.string.surface_tutorial_cancel_entry

    when (stage) {
        TutorialStage.INTRO -> InfoPage(
            stateKey = "tutorial_intro",
            title = stringResource(R.string.surface_tutorial_title),
            body = stringResource(R.string.surface_tutorial_intro),
            primary = R.string.surface_tutorial_begin,
            onPrimary = {
                levelName = TeachLevel.HIDDEN_DRAWER.name
                go(TutorialStage.LEVEL_INTRO)
            },
            secondary = cancelLabel,
            onSecondary = onLeave,
            onBack = onLeave,
        )

        TutorialStage.LEVEL_INTRO -> InfoPage(
            stateKey = "tutorial_level_intro_${level.name}",
            title = stringResource(R.string.surface_tutorial_level_title, level.ordinal + 1, stringResource(level.titleRes())),
            body = stringResource(level.introRes()),
            primary = R.string.surface_tutorial_level_start,
            onPrimary = { go(TutorialStage.LEVEL) },
            secondary = cancelLabel,
            onSecondary = onLeave,
            onBack = onLeave,
        )

        TutorialStage.LEVEL -> TeachLevelStage(
            level = level,
            secret = secret,
            apps = apps,
            onCompleted = { go(TutorialStage.LEVEL_DONE) },
            onQuit = { go(TutorialStage.LEVEL_INTRO) },
        )

        TutorialStage.LEVEL_DONE -> {
            val next = TeachLevel.entries.getOrNull(level.ordinal + 1)
            InfoPage(
                stateKey = "tutorial_level_done_${level.name}",
                title = stringResource(R.string.surface_tutorial_level_done),
                body = stringResource(
                    if (next == null) R.string.surface_tutorial_all_levels_done else R.string.surface_tutorial_level_done_body,
                ),
                primary = if (next == null) R.string.surface_tutorial_practice_next else R.string.surface_tutorial_next_level,
                onPrimary = {
                    if (next == null) {
                        go(TutorialStage.PRACTICE_INTRO)
                    } else {
                        levelName = next.name
                        go(TutorialStage.LEVEL_INTRO)
                    }
                },
                secondary = R.string.surface_tutorial_repeat_level,
                onSecondary = { go(TutorialStage.LEVEL_INTRO) },
                onBack = { go(TutorialStage.LEVEL_INTRO) },
            )
        }

        TutorialStage.PRACTICE_INTRO -> InfoPage(
            stateKey = "tutorial_practice_intro",
            title = stringResource(R.string.surface_tutorial_practice_title),
            body = stringResource(R.string.surface_tutorial_practice_intro),
            primary = R.string.surface_tutorial_practice_start,
            onPrimary = { go(TutorialStage.PRACTICE) },
            secondary = cancelLabel,
            onSecondary = onLeave,
            onBack = onLeave,
        )

        TutorialStage.PRACTICE -> PracticeStage(
            secret = secret,
            apps = apps,
            onPassed = onPassed,
            onAskRelearn = { reason ->
                reasonName = reason.name
                go(TutorialStage.ASK_RELEARN)
            },
        )

        TutorialStage.ASK_RELEARN -> {
            val reason = reasonName?.let { name -> PracticeSession.Reason.entries.firstOrNull { it.name == name } }
            InfoPage(
                stateKey = "tutorial_relearn",
                title = stringResource(R.string.surface_tutorial_relearn_title),
                body = stringResource(
                    when (reason) {
                        PracticeSession.Reason.TIMEOUT -> R.string.surface_tutorial_relearn_timeout
                        PracticeSession.Reason.MISSES -> R.string.surface_tutorial_relearn_misses
                        PracticeSession.Reason.CANCELLED, null -> R.string.surface_tutorial_relearn_cancelled
                    },
                ),
                primary = R.string.surface_tutorial_relearn,
                onPrimary = {
                    levelName = TeachLevel.HIDDEN_DRAWER.name
                    go(TutorialStage.LEVEL_INTRO)
                },
                secondary = cancelLabel,
                onSecondary = onLeave,
                onBack = onLeave,
            )
        }
    }
}

@Composable
private fun InfoPage(
    stateKey: String,
    title: String,
    body: String,
    primary: Int,
    onPrimary: () -> Unit,
    secondary: Int,
    onSecondary: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    FfluePageScaffold(
        stateKey = stateKey,
        title = title,
        onBack = onBack,
        bottomBar = {
            FlueOutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) {
                Text(stringResource(secondary), fontSize = 13.sp)
            }
            FlueButton(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                Text(stringResource(primary), fontSize = 13.sp)
            }
        },
    ) {
        item(key = "body") {
            AgentSettingsGroup { AgentSettingsEntry(title = body, last = true) }
        }
    }
}

/** 一关带提示教学（R20）：分区、位数、步骤和错误提示都显示；做错回到对应步骤重试。 */
@Composable
private fun TeachLevelStage(
    level: TeachLevel,
    secret: List<Int>,
    apps: List<com.flue.launcher.data.model.AppInfo>,
    onCompleted: () -> Unit,
    onQuit: () -> Unit,
) {
    BackHandler(onBack = onQuit)
    val track = remember(level) { TeachTrack(level) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<Int?>(null) }
    var opened by remember { mutableStateOf(false) }
    val current = remember(tick) { track.current }
    val listener = remember(track) {
        object : PreviewListener {
            override fun onStep(step: TeachStep) {
                if (track.report(step)) {
                    error = null
                    tick++
                }
            }

            override fun onRewind(step: TeachStep) {
                if (track.completed) return
                track.rewindTo(step)
                tick++
            }

            override fun onSecretMiss(cancel: SecretCancel?) {
                error = when (cancel) {
                    null -> R.string.surface_tutorial_error_wrong
                    SecretCancel.SYSTEM -> R.string.surface_tutorial_error_interrupted
                    else -> R.string.surface_tutorial_error_cancelled
                }
            }

            override fun onFullSettings() {
                opened = true
            }
        }
    }
    // 第二关打开了真实设置页：回到教程时这一关完成。其余关在最后一步完成时结束。
    LaunchedEffect(tick, opened) {
        if (track.completed && (level != TeachLevel.MANAGEMENT || opened)) {
            if (level == TeachLevel.MANAGEMENT) delay(400)
            onCompleted()
        }
    }
    val hint = when {
        error != null -> stringResource(error!!)
        current != null -> stringResource(current.hintRes())
        else -> null
    }
    TutorialPreview(
        hints = true,
        secret = secret,
        resetTaps = TutorialRules.TEACH_RESET_TAPS,
        drawerApps = apps,
        listener = listener,
        hint = hint,
        note = if (level == TeachLevel.RESET) stringResource(R.string.surface_tutorial_reset_note) else null,
        openRealSettings = true,
        faceExtra = {
            Text(
                text = stringResource(R.string.surface_tutorial_quit_level),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 30.dp)
                    .clickableNoRipple(onQuit),
            )
        },
    )
}

/**
 * 最终无提示练习（R21）。不显示步骤、分区、位数和对错；表盘上临时有“取消练习”。
 * 熄屏、来电暂停计时，被系统打断的验证不算失败。
 */
@Composable
private fun PracticeStage(
    secret: List<Int>,
    apps: List<com.flue.launcher.data.model.AppInfo>,
    onPassed: () -> Unit,
    onAskRelearn: (PracticeSession.Reason) -> Unit,
) {
    val context = LocalContext.current
    val session = remember { PracticeSession(SystemClock.elapsedRealtime()) }
    var tick by remember { mutableIntStateOf(0) }

    fun settle(state: PracticeSession.State) {
        when (state) {
            PracticeSession.State.Passed -> onPassed()
            is PracticeSession.State.AskRelearn -> onAskRelearn(state.reason)
            else -> tick++
        }
    }

    LaunchedEffect(session) {
        val power = context.getSystemService(PowerManager::class.java)
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val interrupted = power?.isInteractive == false || SecretInterruptions.inCall(context)
            if (interrupted) session.pause(now) else session.resume(now)
            val state = session.tick(now)
            if (state is PracticeSession.State.AskRelearn) {
                onAskRelearn(state.reason)
                break
            }
            delay(1_000)
        }
    }
    val listener = remember(session) {
        object : PreviewListener {
            override fun onStep(step: TeachStep) {
                val now = SystemClock.elapsedRealtime()
                when (step) {
                    TeachStep.DRAWER_OPENED -> settle(session.complete(PracticeTask.HIDDEN_DRAWER, now))
                    else -> Unit
                }
            }

            override fun onFullSettings() {
                settle(session.complete(PracticeTask.FULL_SETTINGS, SystemClock.elapsedRealtime()))
            }

            override fun onSecretMiss(cancel: SecretCancel?) {
                // 系统中断取消验证，不算用户错误（R21）。
                if (cancel == SecretCancel.SYSTEM) return
                settle(session.miss(SystemClock.elapsedRealtime()))
            }
        }
    }
    BackHandler { settle(session.cancel()) }
    Box {
        TutorialPreview(
            hints = false,
            secret = secret,
            resetTaps = TutorialRules.TEACH_RESET_TAPS,
            drawerApps = apps,
            listener = listener,
            hint = null,
            openRealSettings = false,
            faceExtra = {
                Text(
                    text = stringResource(R.string.surface_tutorial_cancel_practice),
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 30.dp)
                        .clickableNoRipple { settle(session.cancel()) },
                )
            },
        )
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = padding(4.dp).clickable(onClick = onClick)

internal fun TeachLevel.titleRes(): Int = when (this) {
    TeachLevel.HIDDEN_DRAWER -> R.string.surface_tutorial_level_drawer
    TeachLevel.MANAGEMENT -> R.string.surface_tutorial_level_menu
    TeachLevel.RESET -> R.string.surface_tutorial_level_reset
}

private fun TeachLevel.introRes(): Int = when (this) {
    TeachLevel.HIDDEN_DRAWER -> R.string.surface_tutorial_level_drawer_intro
    TeachLevel.MANAGEMENT -> R.string.surface_tutorial_level_menu_intro
    TeachLevel.RESET -> R.string.surface_tutorial_level_reset_intro
}

private fun TeachStep.hintRes(): Int = when (this) {
    TeachStep.OPEN_CONTROL_CENTER -> R.string.surface_tutorial_hint_open_cc
    TeachStep.HOLD_BATTERY -> R.string.surface_tutorial_hint_hold_battery
    TeachStep.DRAWER_SECRET, TeachStep.MENU_SECRET -> R.string.surface_tutorial_hint_secret
    TeachStep.DRAWER_OPENED -> R.string.surface_tutorial_hint_secret
    TeachStep.DRAWER_BACK_TO_FACE -> R.string.surface_tutorial_hint_back_to_face
    TeachStep.OPEN_APP_LIST -> R.string.surface_tutorial_hint_open_apps
    TeachStep.OPEN_SETTINGS -> R.string.surface_tutorial_hint_open_settings
    TeachStep.OPEN_ABOUT -> R.string.surface_tutorial_hint_open_about
    TeachStep.DRAG_RIGHT -> R.string.surface_tutorial_hint_drag_right
    TeachStep.OPEN_FULL_SETTINGS -> R.string.surface_tutorial_hint_full_settings
    TeachStep.DRAG_DOWN -> R.string.surface_tutorial_hint_drag_down
    TeachStep.TAP_BUILD_NUMBER -> R.string.surface_tutorial_hint_tap_build
    TeachStep.SET_PRACTICE_SECRET -> R.string.surface_tutorial_hint_practice_secret
}
