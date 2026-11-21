package com.flue.launcher.surface.tools.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import com.flue.launcher.surface.tools.ToolNote
import com.flue.launcher.surface.tools.ToolReadout
import com.flue.launcher.surface.tools.orUnknown
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup

/** 电池页（R31）：页面可见期间跟随系统电池广播实时刷新。 */
@Composable
internal fun BatteryToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(BatteryReader.read(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                info = BatteryReader.from(c, intent)
            }
        }
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull()
        if (sticky != null) info = BatteryReader.from(context, sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val unknown = stringResource(R.string.about_unknown)
    FfluePageScaffold(stateKey = "tool_battery", title = stringResource(R.string.surface_tool_battery_title), onBack = onBack) {
        item(key = "readout") {
            ToolReadout(
                text = info.levelPercent?.let { "$it%" } ?: unknown,
                caption = if (!info.present) {
                    stringResource(R.string.surface_tool_battery_absent)
                } else {
                    info.statusRes?.let { stringResource(it) } ?: unknown
                },
            )
        }
        item(key = "details") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_tool_battery_status),
                    subtitle = info.statusRes?.let { stringResource(it) } ?: unknown,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_tool_battery_plugged),
                    subtitle = info.pluggedRes?.let { stringResource(it) } ?: unknown,
                )
                info.chargeTimeRemainingMs?.let { ms ->
                    val minutes = (ms + 59_999) / 60_000
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_tool_battery_full_in),
                        subtitle = stringResource(R.string.surface_tool_battery_hours_minutes, minutes / 60, minutes % 60),
                    )
                }
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_tool_battery_health),
                    subtitle = info.healthRes?.let { stringResource(it) } ?: unknown,
                )
                AgentSettingsEntry(title = stringResource(R.string.surface_tool_battery_temperature), subtitle = orUnknown(info.temperature))
                AgentSettingsEntry(title = stringResource(R.string.surface_tool_battery_voltage), subtitle = orUnknown(info.voltage))
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_tool_battery_technology),
                    subtitle = orUnknown(info.technology),
                    last = true,
                )
            }
        }
        item(key = "note") { ToolNote(stringResource(R.string.surface_tool_battery_note)) }
    }
}
