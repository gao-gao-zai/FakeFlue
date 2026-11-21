package com.flue.launcher.surface.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.min

/** 验证与设置时的系统中断判断（R12、R15）：熄屏、来电。 */
object SecretInterruptions {
    /** 不申请电话权限，按音频模式判断正在通话或响铃。 */
    fun inCall(context: Context): Boolean {
        val mode = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /** 整块屏幕的像素尺寸（不扣系统栏），分区按它计算。 */
    @Suppress("DEPRECATION")
    fun screenSize(context: Context): Pair<Float, Float> {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.maximumWindowMetrics.bounds
            return bounds.width().toFloat() to bounds.height().toFloat()
        }
        val point = Point()
        wm?.defaultDisplay?.getRealSize(point)
        return point.x.toFloat() to point.y.toFloat()
    }
}

/**
 * 熄屏、来电、离开页面、系统返回时调用 [onInterrupted]。验证层和设置页共用。
 */
@Composable
internal fun SecretInterruptionEffects(onInterrupted: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onInterrupted)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) latest()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) latest() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(context) {
        while (true) {
            if (SecretInterruptions.inCall(context)) latest()
            delay(500)
        }
    }
}

/** 点击在屏幕上的绝对位置：组件内坐标 + 组件在窗口中的位置 + 窗口在屏幕上的位置。 */
internal fun screenPosition(view: View, coordinates: LayoutCoordinates?, local: Offset): Offset {
    val inWindow = coordinates?.localToWindow(local) ?: local
    val location = IntArray(2)
    runCatching { view.rootView.getLocationOnScreen(location) }
    return Offset(inWindow.x + location[0], inWindow.y + location[1])
}

/** 验证被取消的原因：用户操作（滑动、10 秒无输入、返回）算主动取消，熄屏、来电、离开页面算系统中断（R21）。 */
enum class SecretCancel { SWIPE, IDLE, BACK, SYSTEM }

/**
 * 覆盖在原画面上的五区域验证（R12、R14、R15）。
 *
 * 正式验证（[hints] = false）完全透明：不显示区域、数字、位数、错误，不振动；原页面不再响应点击。
 * 任意超过触摸容差的滑动、10 秒无输入、熄屏、来电、离开页面或系统返回都调用 [onCancel]；
 * 输错一位调用 [onFailure]，全部正确调用 [onSuccess]。[secret] 为 null（没设置或读不出）时
 * 任何输入都失败，不会被当作成功。
 *
 * [hints] 用于教程：画出分区和数字，并显示已输入位数。
 */
@Composable
fun SecretChallengeOverlay(
    secret: List<Int>?,
    onSuccess: () -> Unit,
    onFailure: () -> Unit,
    onCancel: (SecretCancel) -> Unit,
    hints: Boolean = false,
    hintColor: Color = Color.White,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val attempt = remember(secret) { SecretAttempt(secret.orEmpty()) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var entered by remember { mutableIntStateOf(0) }
    var finished by remember { mutableIntStateOf(0) }
    val success by rememberUpdatedState(onSuccess)
    val failure by rememberUpdatedState(onFailure)
    val cancel by rememberUpdatedState(onCancel)
    fun end(action: () -> Unit) {
        if (finished != 0) return
        finished = 1
        action()
    }
    SecretInterruptionEffects { end { cancel(SecretCancel.SYSTEM) } }
    BackHandler { end { cancel(SecretCancel.BACK) } }
    LaunchedEffect(lastInput) {
        delay(SecretRules.IDLE_TIMEOUT_MS)
        end { cancel(SecretCancel.IDLE) }
    }
    var coordinates by remember { mutableStateOfCoordinates() }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates = it }
            .pointerInput(secret) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var swiped = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val moved = change.position - down.position
                        if (hypot(moved.x, moved.y) > touchSlop) swiped = true
                        if (!change.pressed) break
                    }
                    if (finished != 0) return@awaitEachGesture
                    if (swiped) {
                        end { cancel(SecretCancel.SWIPE) }
                        return@awaitEachGesture
                    }
                    val (width, height) = SecretInterruptions.screenSize(context)
                    val point = screenPosition(view, coordinates, down.position)
                    lastInput = System.currentTimeMillis()
                    when (val step = attempt.input(SecretRules.zoneOf(point.x, point.y, width, height))) {
                        is SecretAttempt.Step.Continue -> entered = step.entered
                        SecretAttempt.Step.Success -> end(success)
                        SecretAttempt.Step.Failure -> end(failure)
                    }
                }
            },
    ) {
        if (hints) {
            Canvas(Modifier.fillMaxSize()) { drawZoneHints(hintColor, entered, secret?.size ?: 0) }
        }
    }
}

private fun mutableStateOfCoordinates() = androidx.compose.runtime.mutableStateOf<LayoutCoordinates?>(null)

/** 画出五个区域、数字和输入进度。只在教程和设置页使用（R13、R20）。 */
internal fun DrawScope.drawZoneHints(color: Color, entered: Int, total: Int) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(size.width, size.height) * SecretRules.CENTER_RADIUS_FRACTION
    val stroke = Stroke(width = 1.5.dp.toPx())
    drawLine(color.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, cy - radius), stroke.width)
    drawLine(color.copy(alpha = 0.45f), Offset(cx, cy + radius), Offset(cx, size.height), stroke.width)
    drawLine(color.copy(alpha = 0.45f), Offset(0f, cy), Offset(cx - radius, cy), stroke.width)
    drawLine(color.copy(alpha = 0.45f), Offset(cx + radius, cy), Offset(size.width, cy), stroke.width)
    drawCircle(color.copy(alpha = 0.12f), radius, Offset(cx, cy))
    drawCircle(color.copy(alpha = 0.55f), radius, Offset(cx, cy), style = stroke)
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 230).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
        )
        textSize = 22.dp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    val corner = (min(size.width, size.height) / 2f - radius) / 2f + radius * 0.35f
    val labels = listOf(
        "1" to Offset(cx - corner, cy - corner),
        "2" to Offset(cx + corner, cy - corner),
        "3" to Offset(cx - corner, cy + corner),
        "4" to Offset(cx + corner, cy + corner),
        "5" to Offset(cx, cy),
    )
    labels.forEach { (text, at) -> drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y + paint.textSize / 3f, paint) }
    if (total > 0) {
        val dot = 3.dp.toPx()
        val gap = 9.dp.toPx()
        val startX = cx - (total - 1) * gap / 2f
        val y = cy + radius * 0.55f
        repeat(total) { i ->
            val center = Offset(startX + i * gap, y)
            if (i < entered) drawCircle(color, dot, center) else drawCircle(color.copy(alpha = 0.4f), dot, center, style = stroke)
        }
    }
}
