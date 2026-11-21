package com.flue.launcher.surface.secret

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** 打开设置页的来由，决定是否需要验证资格（R13、R17）。 */
enum class SecretSetupMode {
    /** 完整模式下首次设置或修改。 */
    INITIAL,

    /** 管理菜单里修改（已验证）。 */
    MAINTAIN,

    /** 忘记密码，版本号点满 100 次后重置，不要求旧密码。 */
    RESET,
}

/**
 * 隐藏密码设置页（R13、R17）。
 *
 * 伪装模式下只能经由管理会话或一次性重置授权打开；授权在进入时消耗，
 * 进程重建后不沿用（避免系统恢复页面时绕过验证）。伪装模式下熄屏、来电或离开页面即关闭。
 */
class SecretSetupActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = SurfaceRuntime.get(this)
        val restored = savedInstanceState?.getString(STATE_TOKEN) == runtime.processToken
        if (!restored && !runtime.consumeSecretSetupAccess()) {
            finish()
            return
        }
        val restricted = runtime.controller.configScope.value == ConfigScope.DISGUISE
        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> SecretSetupMode.entries.firstOrNull { it.name == name } }
            ?: SecretSetupMode.INITIAL
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    if (restricted) SecretInterruptionEffects(onInterrupted = ::finish)
                    SecretSetupFlow(
                        save = { digits -> withContext(Dispatchers.IO) { runtime.secretStore.save(digits) } },
                        onSaved = {
                            Toast.makeText(this, R.string.surface_secret_saved, Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onExit = ::finish,
                        title = stringResource(
                            when (mode) {
                                SecretSetupMode.INITIAL -> R.string.surface_secret_intro_title
                                SecretSetupMode.MAINTAIN -> R.string.surface_manage_change_secret
                                SecretSetupMode.RESET -> R.string.surface_secret_reset_title
                            },
                        ),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TOKEN, SurfaceRuntime.get(this).processToken)
    }

    companion object {
        private const val EXTRA_MODE = "com.flue.launcher.extra.SECRET_SETUP_MODE"
        private const val STATE_TOKEN = "secret_setup_token"

        fun intent(context: Context, mode: SecretSetupMode): Intent =
            Intent(context, SecretSetupActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}

private enum class SetupPage { INTRO, INPUT }

/**
 * 两页设置流程（R13）：介绍页选位数，全屏输入页输入两遍。两页都可以任意方向滑动回上一屏；
 * 输入页滑动清空未保存的输入。[save] 返回 false 时保留原密码并允许重试。
 * P4 教程用同一流程，传入只模拟保存的 [save]。
 */
@Composable
fun SecretSetupFlow(
    save: suspend (List<Int>) -> Boolean,
    onSaved: () -> Unit,
    onExit: () -> Unit,
    title: String = stringResource(R.string.surface_secret_intro_title),
) {
    var page by rememberSaveable { mutableStateOf(SetupPage.INTRO) }
    var length by rememberSaveable { mutableIntStateOf(SecretRules.DEFAULT_LENGTH) }
    BackHandler {
        if (page == SetupPage.INPUT) page = SetupPage.INTRO else onExit()
    }
    when (page) {
        SetupPage.INTRO -> SecretIntroPage(
            title = title,
            length = length,
            onLengthChange = { length = it.coerceIn(SecretRules.MIN_LENGTH, SecretRules.MAX_LENGTH) },
            onStart = { page = SetupPage.INPUT },
            onBack = onExit,
        )
        SetupPage.INPUT -> SecretInputPage(
            length = length,
            save = save,
            onSaved = onSaved,
            onBack = { page = SetupPage.INTRO },
        )
    }
}

@Composable
private fun SecretIntroPage(
    title: String,
    length: Int,
    onLengthChange: (Int) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val style = LauncherTheme.style
    Box(Modifier.fillMaxSize().horizontalSwipeBack(onBack)) {
        FfluePageScaffold(
            stateKey = "secret_intro",
            title = title,
            subtitle = stringResource(R.string.surface_secret_intro_body),
            onBack = onBack,
            // 左右两个方向都由外层处理；纵向留给列表滚动。
            swipeBackEnabled = false,
            bottomBar = {
                FlueButton(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.surface_secret_start), fontSize = 13.sp)
                }
            },
        ) {
            item(key = "secret_rules") {
                AgentSettingsGroup {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_secret_intro_zones_title),
                        subtitle = stringResource(R.string.surface_secret_intro_zones),
                    )
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_secret_intro_rules_title),
                        subtitle = stringResource(R.string.surface_secret_intro_rules),
                        last = true,
                    )
                }
            }
            item(key = "secret_length") {
                AgentSettingsGroup(stringResource(R.string.surface_secret_length)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlueOutlinedButton(
                            onClick = { onLengthChange(length - 1) },
                            enabled = length > SecretRules.MIN_LENGTH,
                        ) { Text("−", fontSize = 16.sp) }
                        Text(
                            text = stringResource(R.string.surface_secret_length_value, length),
                            color = style.titleColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        FlueOutlinedButton(
                            onClick = { onLengthChange(length + 1) },
                            enabled = length < SecretRules.MAX_LENGTH,
                        ) { Text("+", fontSize = 16.sp) }
                    }
                }
            }
            item(key = "secret_swipe_hint") {
                Text(
                    text = stringResource(R.string.surface_secret_intro_swipe_hint),
                    color = style.secondaryTextColor,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private enum class InputMessage { NONE, MISMATCH, SAVE_FAILED }

@Composable
private fun SecretInputPage(
    length: Int,
    save: suspend (List<Int>) -> Boolean,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()
    val entry = remember(length) { SecretEntry(length) }
    var entered by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(SecretEntry.Stage.FIRST) }
    var message by remember { mutableStateOf(InputMessage.NONE) }
    var saving by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val swipeDistance = with(LocalDensity.current) { max(touchSlop * 2f, 36.dp.toPx()) }
    val latestBack by rememberUpdatedState(onBack)
    val latestSaved by rememberUpdatedState(onSaved)

    fun onZone(zone: Int) {
        if (saving) return
        when (val result = entry.input(zone)) {
            is SecretEntry.Result.Progress -> {
                message = InputMessage.NONE
                entered = result.entered
                stage = result.stage
            }
            SecretEntry.Result.StartConfirm -> {
                entered = 0
                stage = SecretEntry.Stage.CONFIRM
            }
            SecretEntry.Result.Mismatch -> {
                entered = 0
                stage = SecretEntry.Stage.FIRST
                message = InputMessage.MISMATCH
            }
            is SecretEntry.Result.Matched -> {
                saving = true
                scope.launch {
                    val ok = runCatching { save(result.digits) }.getOrDefault(false)
                    saving = false
                    if (ok) {
                        latestSaved()
                    } else {
                        entry.clear()
                        entered = 0
                        stage = SecretEntry.Stage.FIRST
                        message = InputMessage.SAVE_FAILED
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates = it }
            .pointerInput(length) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var maxDistance = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val moved = change.position - down.position
                        maxDistance = max(maxDistance, hypot(moved.x, moved.y))
                        if (!change.pressed) break
                    }
                    when {
                        // 任意方向滑动：回上一屏，清空未保存的输入。
                        maxDistance >= swipeDistance -> latestBack()
                        // 轻微抖动仍算点击；介于抖动和滑动之间的不算任何操作。
                        maxDistance <= touchSlop -> {
                            val (width, height) = SecretInterruptions.screenSize(context)
                            val point = screenPosition(view, coordinates, down.position)
                            onZone(SecretRules.zoneOf(point.x, point.y, width, height))
                        }
                    }
                }
            },
    ) {
        val hintColor = style.titleColor
        Canvas(Modifier.fillMaxSize()) { drawZoneHints(hintColor, entered, length) }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp, start = 40.dp, end = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(
                    if (stage == SecretEntry.Stage.FIRST) R.string.surface_secret_input_first else R.string.surface_secret_input_confirm,
                ),
                color = style.titleColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            val note = when (message) {
                InputMessage.NONE -> null
                InputMessage.MISMATCH -> R.string.surface_secret_mismatch
                InputMessage.SAVE_FAILED -> R.string.surface_secret_save_failed
            }
            if (note != null) {
                Text(
                    text = stringResource(note),
                    color = style.accentColor,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = stringResource(R.string.surface_secret_input_swipe_hint),
            color = style.secondaryTextColor,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp, start = 40.dp, end = 40.dp),
        )
    }
    LaunchedEffect(length) { entry.clear() }
}

/** 左右任一方向的明显滑动都返回上一屏（R13 介绍页；纵向留给滚动）。 */
private fun Modifier.horizontalSwipeBack(onBack: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onBack)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val distance = with(LocalDensity.current) { max(touchSlop * 2f, 48.dp.toPx()) }
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) break
                if (abs(dx) >= distance && abs(dx) > abs(dy) * 1.5f) {
                    change.consume()
                    latest()
                    break
                }
            }
        }
    }
}
