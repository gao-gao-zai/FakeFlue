package com.flue.launcher.surface.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.display.DisplayAdaptation
import com.flue.launcher.surface.entry.DisguiseEntry
import com.flue.launcher.surface.entry.EntryOrigin
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.secret.SecretRead
import com.flue.launcher.surface.secret.SecretSetupFlow
import com.flue.launcher.surface.tutorial.TutorialActivity
import com.flue.launcher.surface.tutorial.TutorialRules
import com.flue.launcher.surface.ui.AppDpiCalibrationPage
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.PublicAppPickerPage
import com.flue.launcher.surface.ui.SafeAreaCalibrationPage
import com.flue.launcher.surface.ui.homeLabel
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueSwitchIndicator
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 伪装专属配置向导（R18、R19）。按步骤配置，已确认的步骤记在设备级状态里，再进来从第一个
 * 未完成的步骤继续。只在完整／外部模式或伪装模式的管理会话里打开；不切换模式。
 * 最后一个按钮是“开始教程”（R19），教程通过后才按 [EntryOrigin] 进入伪装。
 */
class DisguiseSetupActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = SurfaceRuntime.get(this)
        if (runtime.controller.configScope.value == ConfigScope.DISGUISE && !runtime.managementSession.value) {
            finish()
            return
        }
        val origin = EntryOrigin.from(intent)
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    DisguiseSetupFlow(vm = vm, origin = origin, onExit = ::finish)
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, origin: EntryOrigin = EntryOrigin.NONE): Intent =
            Intent(context, DisguiseSetupActivity::class.java).putExtra(EntryOrigin.EXTRA, origin.name)
    }
}

private enum class SetupSub { SAFE_AREA, DPI, PUBLIC_APPS, SECRET }

/** 向导的当前页：某一步，或最后的汇总页（[step] 为 null）。 */
@Composable
private fun DisguiseSetupFlow(vm: LauncherViewModel, origin: EntryOrigin, onExit: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    var revision by remember { mutableIntStateOf(0) }
    val done = remember(revision) { runtime.disguiseSetupDone() }
    var stepName by rememberSaveable { mutableStateOf(DisguiseSetupStep.firstPending(runtime.disguiseSetupDone())?.name) }
    val step = stepName?.let { name -> DisguiseSetupStep.entries.firstOrNull { it.name == name } }
    var subName by rememberSaveable { mutableStateOf<String?>(null) }
    val sub = subName?.let { name -> SetupSub.entries.firstOrNull { it.name == name } }

    fun go(target: DisguiseSetupStep?) {
        stepName = target?.name
    }

    fun previous() {
        val index = step?.ordinal ?: DisguiseSetupStep.entries.size
        if (index == 0) onExit() else go(DisguiseSetupStep.entries[index - 1])
    }

    fun confirm(current: DisguiseSetupStep) {
        runtime.markDisguiseSetupStep(current)
        revision++
        go(DisguiseSetupStep.entries.getOrNull(current.ordinal + 1))
    }

    if (sub != null) {
        BackHandler { subName = null }
        when (sub) {
            SetupSub.SAFE_AREA -> SafeAreaCalibrationPage(onBack = { subName = null })
            SetupSub.DPI -> AppDpiCalibrationPage(
                onBack = { subName = null },
                onSaved = {
                    subName = null
                    DisplayAdaptation.recreateAll()
                },
            )
            SetupSub.PUBLIC_APPS -> {
                val apps by vm.catalogApps.collectAsStateWithLifecycle()
                PublicAppPickerPage(apps = apps, onBack = {
                    subName = null
                    revision++
                })
            }
            SetupSub.SECRET -> SecretSetupFlow(
                save = { digits -> withContext(Dispatchers.IO) { runtime.secretStore.save(digits) } },
                onSaved = {
                    Toast.makeText(context, R.string.surface_secret_saved, Toast.LENGTH_SHORT).show()
                    subName = null
                    revision++
                },
                onExit = { subName = null },
            )
        }
        return
    }

    BackHandler(onBack = ::previous)
    val total = DisguiseSetupStep.entries.size
    fun title(s: DisguiseSetupStep) = context.getString(R.string.surface_setup_step_title, s.ordinal + 1, total, context.getString(s.labelRes()))
    val nav: @Composable RowScope.(DisguiseSetupStep, Boolean, Int) -> Unit = { current, canContinue, nextLabel ->
        FlueOutlinedButton(onClick = ::previous, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.surface_setup_previous), fontSize = 13.sp)
        }
        FlueButton(onClick = { confirm(current) }, enabled = canContinue, modifier = Modifier.weight(1f)) {
            Text(stringResource(nextLabel), fontSize = 13.sp)
        }
    }

    when (step) {
        DisguiseSetupStep.PERMISSIONS -> PermissionsPage(
            onBack = ::previous,
            title = title(step),
            bottomBar = { nav(step, true, R.string.surface_setup_next_or_skip) },
        )

        null -> SetupSummaryPage(
            done = done,
            secretReadable = runtime.secretStore.read() is SecretRead.Available,
            origin = origin,
            onBack = ::previous,
            onOpenStep = ::go,
            onExit = onExit,
        )

        else -> FfluePageScaffold(
            stateKey = "disguise_setup_${step.name}",
            title = title(step),
            subtitle = stringResource(step.descriptionRes()),
            onBack = ::previous,
            bottomBar = {
                val canContinue = step != DisguiseSetupStep.SECRET ||
                    remember(revision) { runtime.secretStore.read() is SecretRead.Available }
                nav(step, canContinue, R.string.surface_setup_next)
            },
        ) {
            when (step) {
                DisguiseSetupStep.SAFE_AREA -> item(key = "safe_area") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_setup_open_safe_area),
                            onClick = { subName = SetupSub.SAFE_AREA.name },
                            last = true,
                        )
                    }
                }
                DisguiseSetupStep.DPI -> item(key = "dpi") {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_setup_open_dpi),
                            onClick = { subName = SetupSub.DPI.name },
                            last = true,
                        )
                    }
                }
                DisguiseSetupStep.WATCH_FACE -> item(key = "watch_face") { WatchFaceChoice(vm) }
                DisguiseSetupStep.PUBLIC_APPS -> item(key = "public_apps") {
                    var count by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(revision) { count = runtime.disguisePublicApps().size }
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_public_apps_title),
                            subtitle = count?.let { stringResource(R.string.surface_public_apps_entry_sub, it) },
                            onClick = { subName = SetupSub.PUBLIC_APPS.name },
                            last = true,
                        )
                    }
                }
                DisguiseSetupStep.SECRET -> item(key = "secret") {
                    val state = remember(revision) { runtime.secretStore.read() }
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_secret_title),
                            subtitle = stringResource(
                                when (state) {
                                    is SecretRead.Available -> R.string.surface_secret_entry_set
                                    SecretRead.NotSet -> R.string.surface_secret_entry_unset
                                    SecretRead.Unreadable -> R.string.surface_secret_entry_unreadable
                                },
                            ),
                            onClick = { subName = SetupSub.SECRET.name },
                            last = true,
                        )
                    }
                }
                DisguiseSetupStep.EXIT_TARGET -> item(key = "exit_target") { ExitTargetChoice() }
                DisguiseSetupStep.PERMISSIONS -> Unit
            }
        }
    }
}

@Composable
private fun WatchFaceChoice(vm: LauncherViewModel) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val faces by vm.availableWatchFaces.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { selected = runtime.disguiseWatchFace() }
    AgentSettingsGroup {
        if (faces.isEmpty()) {
            AgentSettingsEntry(title = stringResource(R.string.surface_setup_watch_face_loading), last = true)
        }
        faces.forEachIndexed { index, face ->
            AgentSettingsEntry(
                title = face.displayName,
                subtitle = face.summary.takeIf { it.isNotBlank() },
                onClick = {
                    selected = face.id
                    scope.launch { runtime.saveDisguiseWatchFace(face.id) }
                },
                trailing = { FlueSwitchIndicator(checked = face.id == selected) },
                last = index == faces.lastIndex,
            )
        }
    }
}

/** 退出目标（R19）：明确区分完整 FFlue 与外部桌面；不把本应用保存成外部目标。 */
@Composable
private fun ExitTargetChoice() {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val resumeTick = rememberResumeTick()
    var revision by remember { mutableIntStateOf(0) }
    val preferred = remember(revision) { runtime.stateStore.preferredExit }
    val target = remember(revision, resumeTick) { runtime.controller.externalTarget }
    val candidates = remember(resumeTick) { SurfaceHome.externalHomeCandidates(context) }
    AgentSettingsGroup {
        AgentSettingsEntry(
            title = stringResource(R.string.surface_setup_exit_full),
            subtitle = stringResource(R.string.surface_setup_exit_full_sub),
            onClick = {
                runtime.stateStore.preferredExit = SurfaceMode.FULL
                revision++
            },
            trailing = { FlueSwitchIndicator(checked = preferred == SurfaceMode.FULL) },
            last = candidates.isEmpty(),
        )
        candidates.forEachIndexed { index, component ->
            val flat = component.flattenToString()
            AgentSettingsEntry(
                title = context.homeLabel(component),
                subtitle = stringResource(R.string.surface_setup_exit_external_sub),
                onClick = {
                    if (runtime.controller.setExternalTarget(flat)) {
                        runtime.stateStore.preferredExit = SurfaceMode.EXTERNAL
                        revision++
                    } else {
                        Toast.makeText(context, R.string.surface_target_rejected, Toast.LENGTH_SHORT).show()
                    }
                },
                trailing = {
                    FlueSwitchIndicator(
                        checked = preferred == SurfaceMode.EXTERNAL &&
                            target?.let(ComponentName::unflattenFromString) == component,
                    )
                },
                last = index == candidates.lastIndex,
            )
        }
    }
}

@Composable
private fun SetupSummaryPage(
    done: Set<DisguiseSetupStep>,
    secretReadable: Boolean,
    origin: EntryOrigin,
    onBack: () -> Unit,
    onOpenStep: (DisguiseSetupStep) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val ready = DisguiseSetupRules.baseConfigured(done, secretReadable)
    val tutorialPassed = TutorialRules.passes(runtime.tutorialVersion.collectAsState().value)
    val action = when {
        !tutorialPassed -> R.string.surface_setup_start_tutorial
        origin != EntryOrigin.NONE -> R.string.surface_action_enter_disguise
        else -> R.string.surface_setup_finish
    }
    FfluePageScaffold(
        stateKey = "disguise_setup_summary",
        title = stringResource(R.string.surface_setup_summary_title),
        subtitle = stringResource(if (ready) R.string.surface_setup_summary_ready else R.string.surface_setup_summary_missing),
        onBack = onBack,
        bottomBar = {
            FlueButton(
                onClick = {
                    when (action) {
                        R.string.surface_setup_start_tutorial -> context.startActivity(TutorialActivity.intent(context, origin))
                        R.string.surface_action_enter_disguise -> DisguiseEntry.request(context, origin)
                    }
                    onExit()
                },
                enabled = ready,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(action), fontSize = 13.sp) }
        },
    ) {
        item(key = "summary_steps") {
            AgentSettingsGroup {
                DisguiseSetupStep.entries.forEachIndexed { index, step ->
                    val ok = step in done && (step != DisguiseSetupStep.SECRET || secretReadable)
                    AgentSettingsEntry(
                        title = stringResource(step.labelRes()),
                        subtitle = stringResource(if (ok) R.string.surface_setup_step_done else R.string.surface_setup_step_pending),
                        onClick = { onOpenStep(step) },
                        last = index == DisguiseSetupStep.entries.lastIndex,
                    )
                }
            }
        }
    }
}

internal fun DisguiseSetupStep.labelRes(): Int = when (this) {
    DisguiseSetupStep.SAFE_AREA -> R.string.surface_setup_safe_area
    DisguiseSetupStep.DPI -> R.string.surface_setup_dpi
    DisguiseSetupStep.PERMISSIONS -> R.string.surface_setup_permissions
    DisguiseSetupStep.WATCH_FACE -> R.string.surface_setup_watch_face
    DisguiseSetupStep.PUBLIC_APPS -> R.string.surface_setup_public_apps
    DisguiseSetupStep.SECRET -> R.string.surface_setup_secret
    DisguiseSetupStep.EXIT_TARGET -> R.string.surface_setup_exit_target
}

private fun DisguiseSetupStep.descriptionRes(): Int = when (this) {
    DisguiseSetupStep.SAFE_AREA -> R.string.surface_setup_safe_area_desc
    DisguiseSetupStep.DPI -> R.string.surface_setup_dpi_desc
    DisguiseSetupStep.PERMISSIONS -> R.string.surface_perm_subtitle
    DisguiseSetupStep.WATCH_FACE -> R.string.surface_setup_watch_face_desc
    DisguiseSetupStep.PUBLIC_APPS -> R.string.surface_setup_public_apps_desc
    DisguiseSetupStep.SECRET -> R.string.surface_setup_secret_desc
    DisguiseSetupStep.EXIT_TARGET -> R.string.surface_setup_exit_target_desc
}
