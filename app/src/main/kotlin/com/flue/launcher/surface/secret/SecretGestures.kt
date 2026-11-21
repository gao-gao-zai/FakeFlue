package com.flue.launcher.surface.secret

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import com.flue.launcher.R
import java.util.Locale

/**
 * 按住再松手的手势（R14 电量按钮）：普通点击调用 [onTap]，按满 2 秒且没移动就松手调用 [onHeld]，
 * 中途移动则什么都不做。按下期间调用 [onPressedChange]，宿主可借此锁住自己的滑动手势。
 * [onHeld] 为 null 时按满 2 秒也只当作什么都没发生（不暴露入口）。
 */
fun Modifier.secretHoldGesture(
    onTap: () -> Unit,
    onHeld: (() -> Unit)?,
    onPressedChange: (Boolean) -> Unit = {},
): Modifier = composed {
    val slop = LocalViewConfiguration.current.touchSlop
    val tap by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHeld)
    val pressedChange by rememberUpdatedState(onPressedChange)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            pressedChange(true)
            var moved = false
            var releasedAt = -1L
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > slop) moved = true
                    change.consume()
                    if (!change.pressed) {
                        releasedAt = change.uptimeMillis
                        break
                    }
                }
            } finally {
                pressedChange(false)
            }
            if (releasedAt < 0) return@awaitEachGesture
            when (HoldGesture.classify(releasedAt - down.uptimeMillis, moved)) {
                HoldOutcome.TAP -> tap()
                HoldOutcome.HELD -> held?.invoke()
                HoldOutcome.MOVED -> Unit
            }
        }
    }
}

/**
 * 关于页返回区域（R15、R17）：点击即返回；按住 2 秒后拖动，松手时按安全区判断右拖／下拖。
 * 按满 2 秒之前移动、或拖动不满足阈值，都什么也不发生，也不返回。
 *
 * 坐标统一用窗口坐标；[safeBounds] 给出安全区在窗口中的像素位置。
 * 事件全部消费，外层右滑返回不会抢走这次拖动。
 */
fun Modifier.backAreaGesture(
    onTap: () -> Unit,
    onDrag: (BackDragResult) -> Unit,
    safeBounds: () -> SafeBounds?,
    onPressedChange: (Boolean) -> Unit = {},
): Modifier = composed {
    val slop = LocalViewConfiguration.current.touchSlop
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    val bounds by rememberUpdatedState(safeBounds)
    val pressedChange by rememberUpdatedState(onPressedChange)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun window(local: Offset): Offset = coordinates?.takeIf { it.isAttached }?.localToWindow(local) ?: local
    onGloballyPositioned { coordinates = it }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            pressedChange(true)
            val downWindow = window(down.position)
            var movedBeforeHold = false
            var last = down.position
            var releasedAt = -1L
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    val held = change.uptimeMillis - down.uptimeMillis >= SecretRules.HOLD_MS
                    if (!held && (change.position - down.position).getDistance() > slop) movedBeforeHold = true
                    last = change.position
                    if (!change.pressed) {
                        releasedAt = change.uptimeMillis
                        break
                    }
                }
            } finally {
                pressedChange(false)
            }
            if (releasedAt < 0) return@awaitEachGesture
            val pressedMs = releasedAt - down.uptimeMillis
            when (HoldGesture.classify(pressedMs, movedBeforeHold)) {
                HoldOutcome.TAP -> if ((last - down.position).getDistance() <= slop) tap()
                HoldOutcome.MOVED -> Unit
                HoldOutcome.HELD -> {
                    val safe = bounds() ?: return@awaitEachGesture
                    val up = window(last)
                    val result = BackAreaDrag.classify(downWindow.x, downWindow.y, up.x, up.y, safe, slop)
                    if (result != BackDragResult.NONE) drag(result)
                }
            }
        }
    }
}

/** 控制中心电量按钮的普通点击（R14）：显示真实电池信息，不受关于页显示覆盖影响。 */
fun Context.showRealBatteryInfo() {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val manager = getSystemService(BatteryManager::class.java)
    val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
    val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val temperature = if (tenths == Int.MIN_VALUE) {
        getString(R.string.surface_battery_temp_unknown)
    } else {
        String.format(Locale.getDefault(), "%.1f °C", tenths / 10f)
    }
    val text = getString(
        R.string.surface_battery_info,
        level?.let { "$it%" } ?: getString(R.string.about_unknown),
        getString(if (charging) R.string.surface_battery_charging else R.string.surface_battery_not_charging),
        temperature,
    )
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
