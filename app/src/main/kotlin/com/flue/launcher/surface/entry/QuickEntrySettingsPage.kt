package com.flue.launcher.surface.entry

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.settings.agent.AgentSettingsScreen

/** 录制时不接受的键：系统导航键录不到，或者录了会让人回不去。 */
private val UNBINDABLE = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_UNKNOWN)

/** 完整设置 → 快速进入（R34）：磁贴、Intent、实体按键。 */
@Composable
fun QuickEntrySettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state = remember(context) { SurfaceRuntime.get(context).stateStore }
    val resumeTick = rememberResumeTick()
    var keyCode by remember { mutableIntStateOf(state.quickKeyCode) }
    var pattern by remember { mutableStateOf(state.quickKeyPattern) }
    var recording by remember { mutableStateOf(false) }
    val serviceOn = remember(resumeTick) { QuickKeyService.isEnabled(context) }
    val focus = remember { FocusRequester() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!recording || event.type != KeyEventType.KeyDown || native.keyCode in UNBINDABLE) {
                    return@onPreviewKeyEvent false
                }
                state.quickKeyCode = native.keyCode
                keyCode = native.keyCode
                recording = false
                true
            },
    ) {
        LaunchedEffect(recording) { if (recording) runCatching { focus.requestFocus() } }
        AgentSettingsScreen(
            stateKey = "surface_quick_entry",
            title = stringResource(R.string.surface_quick_title),
            subtitle = stringResource(R.string.surface_quick_sub),
            onBack = onBack,
        ) {
            item(key = "tile") {
                AgentSettingsGroup(stringResource(R.string.surface_quick_tile_group)) {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_quick_tile_label),
                        subtitle = stringResource(R.string.surface_quick_tile_hint),
                    )
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_quick_intent),
                        subtitle = QuickEntryActivity.ACTION_ENTER_DISGUISE,
                        last = true,
                    )
                }
            }
            item(key = "key") {
                AgentSettingsGroup(stringResource(R.string.surface_quick_key_group)) {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_quick_key_service),
                        subtitle = stringResource(
                            if (serviceOn) R.string.surface_quick_key_service_on else R.string.surface_quick_key_service_off,
                        ),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                    AgentSettingsEntry(
                        title = stringResource(
                            if (recording) R.string.surface_quick_key_recording else R.string.surface_quick_key_record,
                        ),
                        subtitle = if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                            stringResource(R.string.surface_quick_key_none)
                        } else {
                            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                        },
                        onClick = { recording = !recording },
                    )
                    KeyPattern.entries.forEach { option ->
                        AgentSettingsEntry(
                            title = stringResource(option.labelRes()),
                            subtitle = if (option == pattern) stringResource(R.string.surface_quick_key_selected) else null,
                            onClick = {
                                state.quickKeyPattern = option
                                pattern = option
                            },
                        )
                    }
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_quick_key_clear),
                        onClick = {
                            state.quickKeyCode = KeyEvent.KEYCODE_UNKNOWN
                            keyCode = KeyEvent.KEYCODE_UNKNOWN
                            recording = false
                        },
                        last = true,
                    )
                }
            }
        }
    }
}

private fun KeyPattern.labelRes(): Int = when (this) {
    KeyPattern.SINGLE -> R.string.surface_quick_pattern_single
    KeyPattern.DOUBLE -> R.string.surface_quick_pattern_double
    KeyPattern.TRIPLE -> R.string.surface_quick_pattern_triple
    KeyPattern.LONG -> R.string.surface_quick_pattern_long
    KeyPattern.DOUBLE_THEN_LONG -> R.string.surface_quick_pattern_double_long
    KeyPattern.TRIPLE_THEN_LONG -> R.string.surface_quick_pattern_triple_long
}
