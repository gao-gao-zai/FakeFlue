package com.flue.launcher.surface.tools.stopwatch

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.surface.tools.TABULAR
import com.flue.launcher.surface.tools.ToolActions
import com.flue.launcher.surface.tools.ToolAlerts
import com.flue.launcher.surface.tools.ToolNote
import com.flue.launcher.surface.tools.ToolPrimaryButton
import com.flue.launcher.surface.tools.ToolReadout
import com.flue.launcher.surface.tools.ToolSecondaryButton
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/** 秒表页（R31）。读数每帧由单调时钟算出，后台、重建都不影响；状态在共用存储里。 */
@Composable
internal fun StopwatchToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember(context) { ToolAlerts.engine(context) }
    val state by engine.stopwatch.collectAsStateWithLifecycle()
    val now by produceState(SystemClock.elapsedRealtime(), state.running) {
        while (state.running) {
            value = SystemClock.elapsedRealtime()
            delay(33L)
        }
        value = SystemClock.elapsedRealtime()
    }
    val elapsed = StopwatchMachine.elapsed(state, now)
    val laps = remember(state.laps) { StopwatchMachine.lapRows(state) }

    FfluePageScaffold(stateKey = "tool_stopwatch", title = stringResource(R.string.surface_tool_stopwatch_title), onBack = onBack) {
        item(key = "readout") {
            ToolReadout(
                text = StopwatchMachine.format(elapsed),
                caption = if (state.running || !state.isZero) null else stringResource(R.string.surface_tool_stopwatch_ready),
                size = 40.sp,
            )
        }
        item(key = "actions") {
            ToolActions {
                when {
                    state.running -> {
                        ToolSecondaryButton(stringResource(R.string.surface_tool_lap), enabled = state.laps.size < StopwatchMachine.MAX_LAPS) {
                            engine.stopwatchLap()
                        }
                        ToolPrimaryButton(stringResource(R.string.surface_tool_pause)) { engine.stopwatchPause() }
                    }
                    state.isZero -> ToolPrimaryButton(stringResource(R.string.surface_tool_start)) { engine.stopwatchStart() }
                    else -> {
                        ToolSecondaryButton(stringResource(R.string.surface_tool_reset)) { engine.stopwatchReset() }
                        ToolPrimaryButton(stringResource(R.string.surface_tool_resume)) { engine.stopwatchStart() }
                    }
                }
            }
        }
        if (state.resetByReboot) {
            item(key = "reboot") { ToolNote(stringResource(R.string.surface_tool_stopwatch_reboot_reset)) }
        }
        if (laps.isNotEmpty()) {
            item(key = "laps") {
                AgentSettingsGroup(stringResource(R.string.surface_tool_laps)) {
                    laps.forEachIndexed { index, lap ->
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_tool_lap_n, lap.index),
                            subtitle = StopwatchMachine.format(lap.splitMs),
                            trailing = {
                                Text(
                                    StopwatchMachine.format(lap.totalMs),
                                    color = LauncherTheme.style.secondaryTextColor,
                                    style = TABULAR,
                                    fontSize = 13.sp,
                                )
                            },
                            last = index == laps.lastIndex,
                        )
                    }
                }
            }
        }
    }
}
