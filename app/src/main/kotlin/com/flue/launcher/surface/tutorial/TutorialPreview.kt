package com.flue.launcher.surface.tutorial

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.SettingsActivity
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.surface.publicsettings.DeviceAboutPage
import com.flue.launcher.surface.publicsettings.ManagementMenuPage
import com.flue.launcher.surface.publicsettings.PublicSettingsListPage
import com.flue.launcher.surface.secret.BackDragResult
import com.flue.launcher.surface.secret.ResetTapCounter
import com.flue.launcher.surface.secret.SecretCancel
import com.flue.launcher.surface.secret.SecretChallengeOverlay
import com.flue.launcher.surface.secret.SecretSetupFlow
import com.flue.launcher.surface.secret.secretHoldGesture
import com.flue.launcher.surface.secret.showRealBatteryInfo
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.common.WatchBatteryPill
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** 教学预览里的画面。预览只模拟公开桌面的结构，不切换正式模式、不改正式配置（R20）。 */
internal enum class PreviewScreen { FACE, CONTROL_CENTER, DRAWER, APP_LIST, SETTINGS, ABOUT, MENU, SETUP }

private enum class Challenge { NONE, DRAWER, MENU }

/** 预览向宿主报告的事情；教学用来推进步骤，练习用来记成绩。 */
internal interface PreviewListener {
    /** 完成了一步（顺序由宿主判断）。 */
    fun onStep(step: TeachStep) {}

    /** 回到了某一步之前。 */
    fun onRewind(step: TeachStep) {}

    /** 密码输错（[cancel] 为 null）或验证被取消。 */
    fun onSecretMiss(cancel: SecretCancel?) {}

    /** 进入了完整设置（第二关在这里真正打开设置页）。 */
    fun onFullSettings() {}
}

/**
 * 教学与练习共用的预览环境。
 *
 * - [hints] 为 true 时画出密码分区和输入位数，并在底部显示 [hint]；练习时全部不显示（R21）。
 * - [secret] 是真实隐藏密码，只用于比较，不会被修改；重置关设置的练习密码只模拟保存。
 * - [faceExtra] 画在表盘上（练习时的“取消练习”）。
 */
@Composable
internal fun TutorialPreview(
    hints: Boolean,
    secret: List<Int>?,
    resetTaps: Int,
    drawerApps: List<AppInfo>,
    listener: PreviewListener,
    hint: String?,
    note: String? = null,
    openRealSettings: Boolean,
    faceExtra: (@Composable BoxScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(PreviewScreen.FACE) }
    var challenge by remember { mutableStateOf(Challenge.NONE) }
    var counting by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    val counter = remember(resetTaps) { ResetTapCounter(resetTaps) }
    val events by rememberUpdatedState(listener)

    fun stopCounting() {
        counting = false
        counter.clear()
        taps = 0
    }

    fun show(target: PreviewScreen) {
        when (target) {
            PreviewScreen.FACE -> {
                if (screen == PreviewScreen.DRAWER) events.onStep(TeachStep.DRAWER_BACK_TO_FACE)
                events.onRewind(TeachStep.OPEN_CONTROL_CENTER)
                events.onRewind(TeachStep.OPEN_APP_LIST)
            }
            PreviewScreen.CONTROL_CENTER -> events.onRewind(TeachStep.HOLD_BATTERY)
            PreviewScreen.APP_LIST -> events.onRewind(TeachStep.OPEN_SETTINGS)
            PreviewScreen.SETTINGS -> events.onRewind(TeachStep.OPEN_ABOUT)
            PreviewScreen.ABOUT -> {
                events.onRewind(TeachStep.DRAG_RIGHT)
                events.onRewind(TeachStep.DRAG_DOWN)
            }
            else -> Unit
        }
        stopCounting()
        screen = target
    }

    fun notInTutorial() {
        Toast.makeText(context, R.string.surface_tutorial_not_in_preview, Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            PreviewScreen.FACE -> PreviewFace(
                onSwipeDown = {
                    events.onStep(TeachStep.OPEN_CONTROL_CENTER)
                    show(PreviewScreen.CONTROL_CENTER)
                },
                onSwipeUp = {
                    events.onStep(TeachStep.OPEN_APP_LIST)
                    show(PreviewScreen.APP_LIST)
                },
                extra = faceExtra,
            )
            PreviewScreen.CONTROL_CENTER -> {
                BackHandler { show(PreviewScreen.FACE) }
                PreviewControlCenter(
                    onClose = { show(PreviewScreen.FACE) },
                    onBatteryHeld = {
                        events.onStep(TeachStep.HOLD_BATTERY)
                        challenge = Challenge.DRAWER
                    },
                )
            }
            PreviewScreen.DRAWER -> {
                BackHandler { show(PreviewScreen.FACE) }
                PreviewDrawer(drawerApps, onBack = { show(PreviewScreen.FACE) })
            }
            PreviewScreen.APP_LIST -> {
                BackHandler { show(PreviewScreen.FACE) }
                PreviewAppList(
                    onBack = { show(PreviewScreen.FACE) },
                    onOpenSettings = {
                        events.onStep(TeachStep.OPEN_SETTINGS)
                        show(PreviewScreen.SETTINGS)
                    },
                )
            }
            PreviewScreen.SETTINGS -> {
                BackHandler { show(PreviewScreen.APP_LIST) }
                PublicSettingsListPage(
                    onBack = { show(PreviewScreen.APP_LIST) },
                    onOpenAbout = {
                        events.onStep(TeachStep.OPEN_ABOUT)
                        show(PreviewScreen.ABOUT)
                    },
                )
            }
            PreviewScreen.ABOUT -> {
                BackHandler(enabled = challenge == Challenge.NONE) { show(PreviewScreen.SETTINGS) }
                DeviceAboutPage(
                    onBack = { show(PreviewScreen.SETTINGS) },
                    onBuildNumberTap = {
                        if (counting) {
                            taps = counter.count + 1
                            if (counter.tap()) {
                                events.onStep(TeachStep.TAP_BUILD_NUMBER)
                                stopCounting()
                                screen = PreviewScreen.SETUP
                            }
                        }
                    },
                    onBackAreaDrag = { result ->
                        stopCounting()
                        when (result) {
                            BackDragResult.MANAGEMENT -> {
                                events.onStep(TeachStep.DRAG_RIGHT)
                                challenge = Challenge.MENU
                            }
                            BackDragResult.RESET -> {
                                events.onStep(TeachStep.DRAG_DOWN)
                                counting = true
                            }
                            BackDragResult.NONE -> Unit
                        }
                    },
                    onPageSwipe = {
                        if (counting) events.onRewind(TeachStep.DRAG_DOWN)
                        stopCounting()
                    },
                )
            }
            PreviewScreen.MENU -> {
                BackHandler { show(PreviewScreen.ABOUT) }
                ManagementMenuPage(
                    onBack = { show(PreviewScreen.ABOUT) },
                    onOpenDisguiseSettings = ::notInTutorial,
                    onOpenFullSettings = {
                        events.onStep(TeachStep.OPEN_FULL_SETTINGS)
                        events.onFullSettings()
                        // R20：第二关必须真正打开设置页。
                        if (openRealSettings) {
                            runCatching { context.startActivity(Intent(context, SettingsActivity::class.java)) }
                        }
                    },
                    onChangeSecret = ::notInTutorial,
                    onSwitch = { notInTutorial() },
                )
            }
            PreviewScreen.SETUP -> SecretSetupFlow(
                // 练习密码只模拟保存，不覆盖真实密码（R20）。
                save = { true },
                onSaved = {
                    events.onStep(TeachStep.SET_PRACTICE_SECRET)
                    show(PreviewScreen.ABOUT)
                },
                onExit = { show(PreviewScreen.ABOUT) },
                title = stringResource(R.string.surface_tutorial_practice_secret_title),
            )
        }

        if (hints && (hint != null || (counting && screen == PreviewScreen.ABOUT))) {
            HintBanner(
                text = if (counting) stringResource(R.string.surface_tutorial_taps, taps, resetTaps) else hint.orEmpty(),
                note = note,
            )
        }

        if (challenge != Challenge.NONE) {
            val target = challenge
            SecretChallengeOverlay(
                secret = secret,
                hints = hints,
                hintColor = LauncherTheme.style.titleColor,
                onSuccess = {
                    challenge = Challenge.NONE
                    if (target == Challenge.DRAWER) {
                        events.onStep(TeachStep.DRAWER_SECRET)
                        events.onStep(TeachStep.DRAWER_OPENED)
                        screen = PreviewScreen.DRAWER
                    } else {
                        events.onStep(TeachStep.MENU_SECRET)
                        screen = PreviewScreen.MENU
                    }
                },
                onFailure = {
                    challenge = Challenge.NONE
                    events.onRewind(if (target == Challenge.DRAWER) TeachStep.HOLD_BATTERY else TeachStep.DRAG_RIGHT)
                    events.onSecretMiss(null)
                },
                onCancel = { reason ->
                    challenge = Challenge.NONE
                    events.onRewind(if (target == Challenge.DRAWER) TeachStep.HOLD_BATTERY else TeachStep.DRAG_RIGHT)
                    events.onSecretMiss(reason)
                },
            )
        }
    }
}

@Composable
private fun BoxScope.HintBanner(text: String, note: String?) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 22.dp, start = 28.dp, end = 28.dp)
            .widthIn(max = 320.dp)
            .clip(style.compactShape)
            .background(style.accentColor.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = style.screenBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (note != null) {
            Text(
                text = note,
                color = style.screenBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 纵向明显滑动：下滑 / 上滑。 */
private fun Modifier.verticalSwipes(onDown: () -> Unit, onUp: () -> Unit): Modifier = composed {
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val distance = with(LocalDensity.current) { 48.dp.toPx() }
    pointerInput(Unit) {
        awaitEachGesture {
            val start = awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == start.id } ?: break
                val dy = change.position.y - start.position.y
                val dx = change.position.x - start.position.x
                if (abs(dy) >= distance && abs(dy) > abs(dx) * 1.5f) {
                    change.consume()
                    if (dy > 0) down() else up()
                    break
                }
                if (!change.pressed) break
            }
        }
    }
}

@Composable
private fun PreviewFace(onSwipeDown: () -> Unit, onSwipeUp: () -> Unit, extra: (@Composable BoxScope.() -> Unit)?) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1_000)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalSwipes(onDown = onSwipeDown, onUp = onSwipeUp),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now),
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(now),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
        }
        extra?.invoke(this)
    }
}

@Composable
private fun PreviewControlCenter(onClose: () -> Unit, onBatteryHeld: () -> Unit) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val battery = remember { context.batteryLevel() }
    Box(
        Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            .verticalSwipes(onDown = {}, onUp = onClose),
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WatchBatteryPill(
                level = battery,
                charging = false,
                modifier = Modifier.secretHoldGesture(
                    onTap = { context.showRealBatteryInfo() },
                    onHeld = onBatteryHeld,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { PreviewTile() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { PreviewTile() }
            }
        }
    }
}

@Composable
private fun PreviewTile() {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(LauncherTheme.style.topBarChipColor),
    )
}

@Composable
private fun PreviewDrawer(apps: List<AppInfo>, onBack: () -> Unit) {
    FfluePageScaffold(
        stateKey = "tutorial_drawer",
        title = stringResource(R.string.surface_hidden_drawer_title),
        subtitle = stringResource(R.string.surface_tutorial_drawer_preview),
        onBack = onBack,
    ) {
        item(key = "tutorial_drawer_apps") {
            AgentSettingsGroup {
                val shown = apps.filterNot { it.isFolder || it.isBuiltInSettingsEntry }.take(12)
                shown.forEachIndexed { index, app ->
                    AgentSettingsEntry(title = app.label, last = index == shown.lastIndex)
                }
                if (shown.isEmpty()) AgentSettingsEntry(title = stringResource(R.string.surface_public_apps_empty), last = true)
            }
        }
    }
}

@Composable
private fun PreviewAppList(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    FfluePageScaffold(
        stateKey = "tutorial_app_list",
        title = stringResource(R.string.surface_tutorial_app_list),
        onBack = onBack,
    ) {
        item(key = "tutorial_apps") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.public_settings_title),
                    onClick = onOpenSettings,
                    last = true,
                )
            }
        }
        item(key = "tutorial_apps_space") { Box(Modifier.fillMaxWidth().height(8.dp)) }
    }
}

private fun android.content.Context.batteryLevel(): Int =
    getSystemService(android.os.BatteryManager::class.java)
        ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.coerceIn(0, 100) ?: 0
