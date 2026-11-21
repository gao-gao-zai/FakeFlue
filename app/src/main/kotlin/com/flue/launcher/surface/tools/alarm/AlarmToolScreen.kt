package com.flue.launcher.surface.tools.alarm

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.surface.tools.ToolActions
import com.flue.launcher.surface.tools.ToolAlerts
import com.flue.launcher.surface.tools.ToolAlertsEngine
import com.flue.launcher.surface.tools.ToolNote
import com.flue.launcher.surface.tools.ToolPrimaryButton
import com.flue.launcher.surface.tools.ToolReadout
import com.flue.launcher.surface.tools.ToolSecondaryButton
import com.flue.launcher.surface.tools.ToolStepper
import com.flue.launcher.surface.tools.alert.AndroidToolNotifier
import com.flue.launcher.surface.tools.toolAlertNotice
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueSwitch
import com.flue.launcher.ui.theme.LauncherTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/** 新建时的编辑目标。 */
private const val NEW_ALARM = 0

/** 闹钟页（R31）：列表、开关、编辑；正在响的闹钟可在这里关闭。 */
@Composable
internal fun AlarmToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember(context) { ToolAlerts.engine(context) }
    val alarms by engine.alarms.collectAsStateWithLifecycle()
    val ringing by engine.ringing.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    val target = editing
    val existing = target?.let { id -> alarms.firstOrNull { it.id == id } }
    if (target != null && (target == NEW_ALARM || existing != null)) {
        BackHandler { editing = null }
        AlarmEditor(engine, existing, onDone = { editing = null })
        return
    }
    // 编辑中的闹钟已被删除（例如在另一处）：回到列表。
    if (target != null) LaunchedEffect(target) { editing = null }

    FfluePageScaffold(stateKey = "tool_alarm", title = stringResource(R.string.surface_tool_alarm_title), onBack = onBack) {
        if (ringing.isNotEmpty()) {
            item(key = "ringing") {
                AgentSettingsGroup(stringResource(R.string.surface_tool_alarm_ringing)) {
                    ringing.forEachIndexed { index, ring ->
                        val alarm = alarms.firstOrNull { it.id == ring.alarmId }
                        AgentSettingsEntry(
                            title = alarm?.let { AndroidToolNotifier.formatClock(context, it.hour, it.minute) }
                                ?: stringResource(R.string.surface_tool_alarm_title),
                            subtitle = stringResource(R.string.surface_tool_dismiss),
                            onClick = { engine.dismissAlarm(ring.alarmId) },
                            last = index == ringing.lastIndex,
                        )
                    }
                }
            }
        }
        if (alarms.isEmpty()) {
            item(key = "empty") { ToolNote(stringResource(R.string.surface_tool_alarm_empty)) }
        } else {
            item(key = "list") {
                AgentSettingsGroup {
                    alarms.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id })).let { sorted ->
                        sorted.forEachIndexed { index, alarm ->
                            AgentSettingsEntry(
                                title = AndroidToolNotifier.formatClock(context, alarm.hour, alarm.minute),
                                subtitle = alarmSubtitle(context, alarm),
                                onClick = { editing = alarm.id },
                                trailing = {
                                    FlueSwitch(checked = alarm.enabled, onCheckedChange = { engine.setAlarmEnabled(alarm.id, it) })
                                },
                                last = index == sorted.lastIndex,
                            )
                        }
                    }
                }
            }
        }
        item(key = "add") {
            ToolActions {
                ToolPrimaryButton(
                    stringResource(R.string.surface_tool_alarm_add),
                    enabled = alarms.size < AlarmSchedule.MAX_ALARMS,
                ) { editing = NEW_ALARM }
            }
        }
        toolAlertNotice(engine, key = "notice")
    }
}

@Composable
private fun alarmSubtitle(context: Context, alarm: Alarm): String {
    val repeat = repeatLabel(alarm.days)
    val next = alarm.nextTriggerMs
    if (!alarm.enabled || next == null) return "$repeat · ${stringResource(R.string.surface_tool_alarm_off)}"
    return "$repeat · ${stringResource(R.string.surface_tool_alarm_next, nextLabel(context, next))}"
}

/** 下次响铃：今天／明天 + 时刻，更远显示星期。 */
@Composable
private fun nextLabel(context: Context, triggerMs: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(triggerMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = DateFormat.getTimeFormat(context).format(Date(triggerMs))
    val day = when (date) {
        today -> stringResource(R.string.surface_tool_today)
        today.plusDays(1) -> stringResource(R.string.surface_tool_tomorrow)
        else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale())
    }
    return "$day $time"
}

@Composable
private fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return configuration.locales.get(0) ?: Locale.getDefault()
}

@Composable
private fun orderedDays(): List<DayOfWeek> {
    val first = WeekFields.of(currentLocale()).firstDayOfWeek
    return (0 until 7).map { first.plus(it.toLong()) }
}

@Composable
private fun repeatLabel(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> stringResource(R.string.surface_tool_alarm_once)
    days.size == 7 -> stringResource(R.string.surface_tool_alarm_daily)
    days == WORKDAYS -> stringResource(R.string.surface_tool_alarm_workdays)
    else -> {
        val locale = currentLocale()
        orderedDays().filter { it in days }.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

@Composable
private fun AlarmEditor(engine: ToolAlertsEngine, existing: Alarm?, onDone: () -> Unit) {
    val style = LauncherTheme.style
    var hour by rememberSaveable { mutableIntStateOf(existing?.hour ?: 7) }
    var minute by rememberSaveable { mutableIntStateOf(existing?.minute ?: 0) }
    var dayValues by rememberSaveable { mutableStateOf(existing?.days.orEmpty().map { it.value }.sorted()) }
    val days = dayValues.map(DayOfWeek::of).toSet()
    val days7 = orderedDays()

    FfluePageScaffold(
        stateKey = "tool_alarm_edit_${existing?.id ?: NEW_ALARM}",
        title = stringResource(if (existing == null) R.string.surface_tool_alarm_add else R.string.surface_tool_alarm_edit),
        onBack = onDone,
    ) {
        item(key = "readout") {
            ToolReadout(
                text = String.format(Locale.ROOT, "%02d:%02d", hour, minute),
                caption = repeatLabel(days),
            )
        }
        item(key = "steppers") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolStepper(stringResource(R.string.surface_tool_unit_hours), hour, 0..23) { hour = it }
                ToolStepper(stringResource(R.string.surface_tool_unit_minutes), minute, 0..59) { minute = it }
            }
        }
        item(key = "days") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days7.forEach { day ->
                    val on = day in days
                    val name = day.getDisplayName(TextStyle.NARROW, currentLocale())
                    val full = day.getDisplayName(TextStyle.FULL, currentLocale())
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(if (on) style.accentColor else style.topBarChipColor)
                            .border(1.dp, style.outlineColor, CircleShape)
                            .semantics {
                                role = Role.Checkbox
                                selected = on
                                contentDescription = full
                            }
                            .clickable {
                                dayValues = (if (on) days - day else days + day).map { it.value }.sorted()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            color = if (!on) style.titleColor else if (style.accentColor.luminance() > 0.55f) Color.Black else Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        item(key = "quick") {
            ToolActions {
                ToolSecondaryButton(stringResource(R.string.surface_tool_alarm_once)) { dayValues = emptyList() }
                ToolSecondaryButton(stringResource(R.string.surface_tool_alarm_daily)) { dayValues = (1..7).toList() }
            }
        }
        item(key = "save") {
            ToolActions {
                if (existing != null) {
                    ToolSecondaryButton(stringResource(R.string.surface_tool_delete)) {
                        engine.deleteAlarm(existing.id)
                        onDone()
                    }
                }
                ToolPrimaryButton(stringResource(R.string.surface_tool_save)) {
                    if (existing == null) engine.addAlarm(hour, minute, days) else engine.updateAlarm(existing.id, hour, minute, days)
                    onDone()
                }
            }
        }
    }
}
