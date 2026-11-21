package com.flue.launcher.surface.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.secret.SecretChallengeOverlay
import com.flue.launcher.surface.secret.readableSecret
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

private val ErrorColor = Color(0xFFFF7A7A)

/** 已输入位数的圆点。 */
@Composable
internal fun PinDots(entered: Int, modifier: Modifier = Modifier) {
    val style = LauncherTheme.style
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(DesktopPinRules.LENGTH) { index ->
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (index < entered) style.titleColor else style.outlineColor),
            )
        }
    }
}

/** 0–9 数字键盘；左下角可放一个文字动作（忘记密码、取消）。 */
@Composable
internal fun PinPad(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    leftLabel: String? = null,
    onLeft: () -> Unit = {},
    enabled: Boolean = true,
) {
    val style = LauncherTheme.style
    @Composable
    fun Key(label: String, fontSize: Int = 20, onClick: () -> Unit) {
        Box(
            Modifier
                .size(width = 52.dp, height = 40.dp)
                .clip(style.compactShape)
                .background(style.cardColor)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = style.titleColor, fontSize = fontSize.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { digit -> Key(digit.toString()) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (leftLabel != null) Key(leftLabel, fontSize = 11, onClick = onLeft) else Spacer(Modifier.width(52.dp))
            Key("0") { onDigit(0) }
            Key("⌫", fontSize = 16, onClick = onDelete)
        }
    }
}

/**
 * 输入两次新密码；[save] 返回 false 表示保存失败，留在本页重新输入。
 */
@Composable
internal fun PinSetupPanel(title: String, save: (List<Int>) -> Boolean, onCancel: () -> Unit) {
    val style = LauncherTheme.style
    val setup = remember { PinSetup() }
    val entry = remember { PinEntry() }
    var entered by remember { mutableIntStateOf(0) }
    var message by remember { mutableIntStateOf(R.string.surface_lock_setup_first) }
    var error by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = style.titleColor, fontSize = 14.sp)
        Text(
            stringResource(message),
            color = if (error) ErrorColor else style.secondaryTextColor,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        PinDots(entered)
        Spacer(Modifier.height(8.dp))
        PinPad(
            onDigit = { digit ->
                val digits = entry.press(digit)
                entered = entry.entered
                if (digits != null) {
                    when (val step = setup.submit(digits)) {
                        PinSetup.Step.Confirm -> {
                            message = R.string.surface_lock_setup_confirm
                            error = false
                        }
                        PinSetup.Step.Mismatch -> {
                            message = R.string.surface_lock_setup_mismatch
                            error = true
                        }
                        is PinSetup.Step.Done -> if (!save(step.digits)) {
                            message = R.string.surface_lock_setup_save_failed
                            error = true
                            // 回到第一次输入。
                            setup.submit(emptyList())
                        }
                        PinSetup.Step.First -> Unit
                    }
                }
            },
            onDelete = {
                entry.delete()
                entered = entry.entered
            },
            leftLabel = stringResource(R.string.display_cancel),
            onLeft = onCancel,
        )
    }
}

/**
 * 桌面根部的进入密码页（R33）。上锁时盖住整个桌面并吃掉所有触摸，返回键不起作用；
 * 错误留在本页，连续五次错误冷却 30 秒。忘记密码：静默五区域管理密码验证 → 直接设置新密码，
 * 失败或取消仍然锁着，也不授予管理权限。
 */
@Composable
fun DesktopLockHost() {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val lock = runtime.desktopLock
    val locked by lock.locked.collectAsState()
    if (!locked) return
    // R35：启用桌面密码时，密码页就是首个可操作页面。
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        com.flue.launcher.surface.startup.StartupTimeline.mark(com.flue.launcher.surface.startup.StartupTimeline.Mark.LOCK_SHOWN)
    }

    val style = LauncherTheme.style
    val entry = remember { PinEntry() }
    var entered by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var coolingUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var stage by remember { mutableStateOf(LockStage.ENTER) }

    LaunchedEffect(Unit) {
        val state = lock.lockout()
        if (state.lockedOut(android.os.SystemClock.elapsedRealtime())) {
            coolingUntil = state.untilElapsed
        }
    }
    LaunchedEffect(coolingUntil) {
        while (android.os.SystemClock.elapsedRealtime() < coolingUntil) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
        now = android.os.SystemClock.elapsedRealtime()
    }
    val cooling = now < coolingUntil

    BackHandler { /* 返回不能绕过 */ }
    Box(
        Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        when (stage) {
            LockStage.ENTER, LockStage.CHALLENGE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.surface_lock_title), color = style.titleColor, fontSize = 14.sp)
                val hint = when {
                    cooling -> stringResource(R.string.surface_lock_cooling, ((coolingUntil - now + 999) / 1000).toInt())
                    else -> message
                }
                Text(
                    hint ?: stringResource(R.string.surface_lock_hint),
                    color = if (cooling || message != null) ErrorColor else style.secondaryTextColor,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(6.dp))
                PinDots(entered)
                Spacer(Modifier.height(8.dp))
                PinPad(
                    enabled = !cooling && stage == LockStage.ENTER,
                    onDigit = { digit ->
                        val digits = entry.press(digit)
                        entered = entry.entered
                        if (digits != null) {
                            when (val result = lock.tryUnlock(digits)) {
                                DesktopLock.Result.Unlocked -> message = null
                                is DesktopLock.Result.Wrong ->
                                    message = context.getString(R.string.surface_lock_wrong, result.triesLeft)
                                is DesktopLock.Result.Cooling -> {
                                    message = null
                                    coolingUntil = android.os.SystemClock.elapsedRealtime() + result.remainingMs
                                }
                                DesktopLock.Result.Unreadable ->
                                    message = context.getString(R.string.surface_lock_unreadable)
                            }
                        }
                    },
                    onDelete = {
                        entry.delete()
                        entered = entry.entered
                    },
                    leftLabel = stringResource(R.string.surface_lock_forgot),
                    onLeft = {
                        entry.clear()
                        entered = 0
                        stage = LockStage.CHALLENGE
                    },
                )
            }
            LockStage.RESET -> PinSetupPanel(
                title = stringResource(R.string.surface_lock_reset_title),
                save = lock::resetWithManagement,
                onCancel = { stage = LockStage.ENTER },
            )
        }
        if (stage == LockStage.CHALLENGE) {
            val secret = remember { runtime.readableSecret() }
            SecretChallengeOverlay(
                secret = secret,
                onSuccess = { stage = LockStage.RESET },
                onFailure = { stage = LockStage.ENTER },
                onCancel = { stage = LockStage.ENTER },
            )
        }
    }
}

private enum class LockStage { ENTER, CHALLENGE, RESET }

