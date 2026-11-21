package com.flue.launcher.surface.tools

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 当前是否按伪装配置显示（R28：伪装页面不出现授权字样、不请求授权）。 */
@Composable
internal fun rememberDisguiseScope(): Boolean {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val scope by runtime.controller.configScope.collectAsState()
    return scope == ConfigScope.DISGUISE
}

/** 大号读数（剩余时间、秒表、电量）。等宽数字，读数跳动时不左右晃。 */
@Composable
internal fun ToolReadout(text: String, caption: String? = null, size: TextUnit = 44.sp) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            color = style.titleColor,
            fontSize = size,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = TABULAR,
            maxLines = 1,
        )
        if (!caption.isNullOrBlank()) {
            Text(text = caption, color = style.secondaryTextColor, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

/** 一行主要操作：每个按钮等宽。 */
@Composable
internal fun ToolActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RowScope.ToolPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FlueButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Text(label, maxLines = 1)
    }
}

@Composable
internal fun RowScope.ToolSecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FlueOutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Text(label, maxLines = 1)
    }
}

@Composable
internal fun ToolNote(text: String) {
    Text(
        text = text,
        color = LauncherTheme.style.secondaryTextColor,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/**
 * 按住连续触发的圆形按钮（调整时分秒）：按下立即一次，按住 0.4 s 后每 80 ms 一次。
 * 右滑返回等外层手势接管事件时停止。
 */
@Composable
internal fun RepeatButton(label: String, description: String, onStep: () -> Unit) {
    val style = LauncherTheme.style
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(style.topBarChipColor)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    step()
                    val repeat = scope.launch {
                        delay(400)
                        while (true) {
                            step()
                            delay(80)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = style.titleColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 一个数值的步进器：− 值 +，可循环（时分秒）。 */
@Composable
internal fun ToolStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val style = LauncherTheme.style
    val latest by rememberUpdatedState(value)
    fun wrap(v: Int): Int {
        val size = range.last - range.first + 1
        return ((v - range.first) % size + size) % size + range.first
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = style.secondaryTextColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
        RepeatButton("−", stringResource(R.string.surface_tool_decrease, label)) { onChange(wrap(latest - 1)) }
        Text(
            text = String.format(java.util.Locale.ROOT, "%02d", value),
            color = style.titleColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            style = TABULAR,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 48.dp, height = 36.dp),
        )
        RepeatButton("+", stringResource(R.string.surface_tool_increase, label)) { onChange(wrap(latest + 1)) }
    }
}

/**
 * 提醒能力的如实说明（R31、R28）：系统不允许发通知时到点无法提醒；不允许精确定时时可能延后。
 * 完整模式给出主动开启的按钮；伪装模式只陈述事实，不出现授权字样、不请求。
 */
internal fun LazyListScope.toolAlertNotice(engine: ToolAlertsEngine, key: String) {
    item(key = key) { ToolAlertNotice(engine) }
}

@Composable
private fun ToolAlertNotice(engine: ToolAlertsEngine) {
    val context = LocalContext.current
    val disguise = rememberDisguiseScope()
    val resume = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val canNotify = remember(resume, refresh) { engine.canNotify() }
    val canExact = remember(resume, refresh) { engine.canScheduleExact() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    if (canNotify && canExact) return

    fun open(intent: Intent) {
        val ok = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }.isSuccess
        if (!ok) Toast.makeText(context, R.string.public_settings_open_failed, Toast.LENGTH_SHORT).show()
    }

    fun allowNotifications() {
        val runtimeMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (runtimeMissing) {
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    AgentSettingsGroup {
        if (!canNotify) {
            AgentSettingsEntry(
                title = stringResource(R.string.surface_tool_notice_no_notify_title),
                subtitle = stringResource(
                    if (disguise) R.string.surface_tool_notice_no_notify_public else R.string.surface_tool_notice_no_notify_full,
                ),
                onClick = if (disguise) null else ::allowNotifications,
                last = canExact,
            )
        }
        if (!canExact) {
            AgentSettingsEntry(
                title = stringResource(R.string.surface_tool_notice_inexact_title),
                subtitle = stringResource(
                    if (disguise) R.string.surface_tool_notice_inexact_public else R.string.surface_tool_notice_inexact_full,
                ),
                onClick = if (disguise || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    null
                } else {
                    {
                        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                },
                last = true,
            )
        }
    }
}

/** 等宽数字。 */
internal val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** 未知值统一显示。 */
@Composable
internal fun orUnknown(value: String?): String = value ?: stringResource(R.string.about_unknown)
