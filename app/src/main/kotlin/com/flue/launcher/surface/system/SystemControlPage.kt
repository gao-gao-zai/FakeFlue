package com.flue.launcher.surface.system

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.settings.agent.AgentSettingsScreen
import java.util.Date

/** 完整设置 → 系统控制：导航限制的真实状态、重新连接、恢复失败记录（R28、R29、R30）。 */
@Composable
fun SystemControlPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val control = runtime.systemControl
    val status by control.status.collectAsState()
    val resumeTick = rememberResumeTick()
    var revision by remember { mutableIntStateOf(0) }
    val records = remember(resumeTick, revision) { control.recoveryRecords() }

    AgentSettingsScreen(
        stateKey = "surface_system_control",
        title = stringResource(R.string.surface_sys_title),
        subtitle = stringResource(R.string.surface_sys_scope_note),
        onBack = onBack,
    ) {
        item(key = "nav") {
            AgentSettingsGroup(stringResource(R.string.surface_sys_nav_group)) {
                val detail = (status as? NavGuardStatus.Unavailable)?.detail
                AgentSettingsEntry(
                    title = stringResource(status.labelRes()),
                    subtitle = detail?.let { stringResource(R.string.surface_sys_status_detail, it) },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_sys_root),
                    subtitle = stringResource(
                        if (runtime.stateStore.rootConfirmed) R.string.surface_sys_root_confirmed
                        else R.string.surface_sys_root_unconfirmed,
                    ),
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_sys_retry),
                    subtitle = stringResource(R.string.surface_sys_retry_sub),
                    onClick = { control.retry() },
                    last = true,
                )
            }
        }
        item(key = "records") {
            AgentSettingsGroup(stringResource(R.string.surface_sys_records_group)) {
                if (records.isEmpty()) {
                    AgentSettingsEntry(title = stringResource(R.string.surface_sys_records_empty), last = true)
                } else {
                    records.asReversed().forEach { record ->
                        val time = DateFormat.getMediumDateFormat(context).format(Date(record.atWallMs)) + " " +
                            DateFormat.getTimeFormat(context).format(Date(record.atWallMs))
                        AgentSettingsEntry(
                            title = time,
                            subtitle = stringResource(
                                R.string.surface_sys_record_sub,
                                stringResource(reasonRes(record.reason)),
                                record.attempts,
                                stringResource(
                                    if (record.fallbackLaunched) R.string.surface_sys_record_fallback_yes
                                    else R.string.surface_sys_record_fallback_no,
                                ),
                            ),
                        )
                    }
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_sys_records_clear),
                        onClick = {
                            control.clearRecoveryRecords()
                            revision++
                        },
                        last = true,
                    )
                }
            }
        }
    }
}

private fun NavGuardStatus.labelRes(): Int = when (this) {
    NavGuardStatus.NotNeeded -> R.string.surface_sys_status_not_needed
    NavGuardStatus.Released -> R.string.surface_sys_status_released
    NavGuardStatus.Applying -> R.string.surface_sys_status_applying
    NavGuardStatus.Active -> R.string.surface_sys_status_active
    NavGuardStatus.Unverified -> R.string.surface_sys_status_unverified
    is NavGuardStatus.Unavailable -> when (reason) {
        NavGuardStatus.Reason.NO_ROOT -> R.string.surface_sys_status_no_root
        NavGuardStatus.Reason.START_FAILED -> R.string.surface_sys_status_start_failed
        NavGuardStatus.Reason.CONNECTION_LOST -> R.string.surface_sys_status_lost
    }
}

private fun reasonRes(reason: String): Int = when (reason) {
    "budget-exhausted" -> R.string.surface_sys_reason_budget
    "no-takeover" -> R.string.surface_sys_reason_no_takeover
    "call-state-unknown" -> R.string.surface_sys_reason_call_unknown
    else -> R.string.surface_sys_reason_other
}
