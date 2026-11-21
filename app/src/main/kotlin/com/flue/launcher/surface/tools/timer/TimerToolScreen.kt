package com.flue.launcher.surface.tools.timer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.surface.tools.ToolActions
import com.flue.launcher.surface.tools.ToolAlerts
import com.flue.launcher.surface.tools.ToolNote
import com.flue.launcher.surface.tools.ToolPrimaryButton
import com.flue.launcher.surface.tools.ToolReadout
import com.flue.launcher.surface.tools.ToolSecondaryButton
import com.flue.launcher.surface.tools.ToolStepper
import com.flue.launcher.surface.tools.alert.AndroidToolNotifier
import com.flue.launcher.surface.tools.toolAlertNotice
import com.flue.launcher.surface.ui.FfluePageScaffold
import kotlinx.coroutines.delay

private val PRESET_MINUTES = listOf(1, 3, 5, 10, 15, 30)

/** 计时器页（R31）。到点由系统登记提醒；页面开着时倒数到零也走同一条去重的到点路径。 */
@Composable
internal fun TimerToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember(context) { ToolAlerts.engine(context) }
    val state by engine.timer.collectAsStateWithLifecycle()
    val running = state is TimerState.Running
    val now by produceState(SystemClock.elapsedRealtime(), running) {
        while (running) {
            value = SystemClock.elapsedRealtime()
            delay(100L - value % 100L)
        }
        value = SystemClock.elapsedRealtime()
    }
    val remaining = TimerMachine.remaining(state, now)

    val current = state
    if (current is TimerState.Running && remaining <= 0L) {
        LaunchedEffect(current.runId) { engine.timerDue(current.runId) }
    }
    if (current is TimerState.Fired) {
        // 系统不允许通知时，至少在页面开着时振动提示一次。
        LaunchedEffect(current.runId) {
            val fresh = System.currentTimeMillis() - current.firedAtWallMs in 0..60_000L
            if (fresh && !engine.canNotify()) vibrateOnce(context)
        }
    }

    FfluePageScaffold(stateKey = "tool_timer", title = stringResource(R.string.surface_tool_timer_title), onBack = onBack) {
        item(key = "readout") {
            ToolReadout(
                text = AndroidToolNotifier.formatDuration(remaining),
                caption = stringResource(
                    when (current) {
                        is TimerState.Idle -> R.string.surface_tool_timer_state_idle
                        is TimerState.Running -> R.string.surface_tool_timer_state_running
                        is TimerState.Paused -> R.string.surface_tool_timer_state_paused
                        is TimerState.Fired -> R.string.surface_tool_timer_state_fired
                    },
                ),
            )
        }
        when (current) {
            is TimerState.Idle -> {
                item(key = "steppers") {
                    val total = current.durationMs / 1000
                    val h = (total / 3600).toInt()
                    val m = ((total % 3600) / 60).toInt()
                    val s = (total % 60).toInt()
                    fun set(hh: Int, mm: Int, ss: Int) = engine.setTimerDuration((hh * 3600L + mm * 60L + ss) * 1000L)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolStepper(stringResource(R.string.surface_tool_unit_hours), h, 0..23) { set(it, m, s) }
                        ToolStepper(stringResource(R.string.surface_tool_unit_minutes), m, 0..59) { set(h, it, s) }
                        ToolStepper(stringResource(R.string.surface_tool_unit_seconds), s, 0..59) { set(h, m, it) }
                    }
                }
                PRESET_MINUTES.chunked(3).forEachIndexed { row, presets ->
                    item(key = "presets_$row") {
                        ToolActions {
                            presets.forEach { minutes ->
                                ToolSecondaryButton(stringResource(R.string.surface_tool_timer_preset, minutes)) {
                                    engine.setTimerDuration(minutes * 60_000L)
                                }
                            }
                        }
                    }
                }
                item(key = "start") {
                    ToolActions {
                        ToolPrimaryButton(stringResource(R.string.surface_tool_start), enabled = current.durationMs >= 1000L) {
                            engine.startTimer(current.durationMs)
                        }
                    }
                }
            }
            is TimerState.Running -> {
                item(key = "actions") {
                    ToolActions {
                        ToolSecondaryButton(stringResource(R.string.surface_tool_cancel)) { engine.cancelTimer() }
                        ToolPrimaryButton(stringResource(R.string.surface_tool_pause)) { engine.pauseTimer() }
                    }
                }
                if (current.estimatedAfterReboot) {
                    item(key = "estimated") { ToolNote(stringResource(R.string.surface_tool_timer_estimated)) }
                }
            }
            is TimerState.Paused -> item(key = "actions") {
                ToolActions {
                    ToolSecondaryButton(stringResource(R.string.surface_tool_cancel)) { engine.cancelTimer() }
                    ToolPrimaryButton(stringResource(R.string.surface_tool_resume)) { engine.resumeTimer() }
                }
            }
            is TimerState.Fired -> {
                item(key = "actions") {
                    ToolActions {
                        ToolSecondaryButton(stringResource(R.string.surface_tool_restart)) {
                            engine.dismissTimer()
                            engine.startTimer(current.durationMs)
                        }
                        ToolPrimaryButton(stringResource(R.string.surface_tool_dismiss)) { engine.dismissTimer() }
                    }
                }
                if (current.late) item(key = "late") { ToolNote(stringResource(R.string.surface_tool_timer_late)) }
            }
        }
        toolAlertNotice(engine, key = "notice")
    }
}

internal fun vibrateOnce(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1))
        }
    }
}
