package com.flue.launcher.surface.publicsettings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 依次尝试打开系统设置页（R27）：不先查询系统状态或探测权限，直接启动，失败再试下一个。
 * 全部失败返回 false，由页面提示可重试；不改变模式或任何资格。
 */
internal fun Context.openSystemSettings(actions: List<String>): Boolean {
    for (action in actions) {
        val intent = Intent(action)
        if (this !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

/** 伪装公开的“设置”（R26）。 */
@Composable
internal fun PublicSettingsListPage(onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    fun open(item: PublicSettingsItem) {
        if (item == PublicSettingsItem.ABOUT) {
            onOpenAbout()
            return
        }
        val target = item.devicePage?.let { DeviceRouter.route(null, DeviceRouter.builtInAvailable(it)) }
            ?: DeviceRouteChoice.SYSTEM
        // 内置设备页尚不存在，target 目前总是 SYSTEM。
        if (target == DeviceRouteChoice.SYSTEM && !context.openSystemSettings(item.systemActions)) {
            Toast.makeText(context, R.string.public_settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
    FfluePageScaffold(
        stateKey = "public_settings",
        title = stringResource(R.string.public_settings_title),
        onBack = onBack,
    ) {
        item(key = "public_settings_items") {
            AgentSettingsGroup {
                val items = PublicSettingsItem.entries
                items.forEachIndexed { index, item ->
                    AgentSettingsEntry(
                        title = stringResource(item.labelRes),
                        onClick = { open(item) },
                        last = index == items.lastIndex,
                    )
                }
            }
        }
    }
}

/**
 * 关于（R26）：系统、设备、存储、内存、电池五组真实信息，按覆盖显示。
 *
 * @param onBuildNumberTap 点系统版本号时调用（公开页用来接 P3 之前的临时隐藏手势）。
 * @param extra 额外条目（完整设置在这里放“关于 FFlue”）。
 */
@Composable
internal fun DeviceAboutPage(
    onBack: () -> Unit,
    onBuildNumberTap: (() -> Unit)? = null,
    extra: (LazyListScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val real by produceState<Map<AboutField, String?>>(emptyMap(), resumeTick) {
        value = withContext(Dispatchers.IO) { DeviceInfoReader.read(context) }
    }
    val overrides = remember(resumeTick) { AboutOverrides.read(context) }
    val unknown = stringResource(R.string.about_unknown)
    FfluePageScaffold(
        stateKey = "device_about",
        title = stringResource(R.string.public_settings_about),
        onBack = onBack,
    ) {
        AboutGroup.entries.forEach { group ->
            item(key = "about_${group.name}") {
                AgentSettingsGroup(stringResource(group.titleRes)) {
                    val fields = AboutField.entries.filter { it.group == group }
                    fields.forEachIndexed { index, field ->
                        AgentSettingsEntry(
                            title = stringResource(field.labelRes),
                            subtitle = AboutFormat.display(real[field], overrides[field]) ?: unknown,
                            onClick = if (field == AboutField.BUILD_NUMBER) onBuildNumberTap else null,
                            last = index == fields.lastIndex,
                        )
                    }
                }
            }
        }
        extra?.invoke(this)
    }
}

/** 完整设置里的关于页显示覆盖（R26）。逐项填写，留空恢复真实值；只有保存才写入。 */
@Composable
internal fun AboutOverridesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val real by produceState<Map<AboutField, String?>>(emptyMap()) {
        value = withContext(Dispatchers.IO) { DeviceInfoReader.read(context) }
    }
    var draft by remember { mutableStateOf(AboutOverrides.read(context)) }
    val unknown = stringResource(R.string.about_unknown)
    FfluePageScaffold(
        stateKey = "about_overrides",
        title = stringResource(R.string.about_overrides_title),
        subtitle = stringResource(R.string.about_overrides_subtitle),
        onBack = onBack,
        bottomBar = {
            FlueOutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.display_cancel), fontSize = 13.sp)
            }
            FlueButton(
                onClick = {
                    if (AboutOverrides.save(context, draft)) {
                        onBack()
                    } else {
                        Toast.makeText(context, R.string.display_save_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.display_save), fontSize = 13.sp) }
        },
    ) {
        AboutGroup.entries.forEach { group ->
            item(key = "override_${group.name}") {
                AgentSettingsGroup(stringResource(group.titleRes)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AboutField.entries.filter { it.group == group }.forEach { field ->
                            FlueTextField(
                                value = draft[field].orEmpty(),
                                onValueChange = { text -> draft = draft + (field to text) },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(field.labelRes),
                                placeholder = real[field] ?: unknown,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 公开关于页的临时隐藏手势：连点系统版本号 7 次（P3 换成五区域密码）。 */
@Composable
internal fun rememberTemporaryManagementTap(onTriggered: () -> Unit): () -> Unit {
    val detector = remember { RepeatTapDetector() }
    return { if (detector.tap(SystemClock.elapsedRealtime())) onTriggered() }
}
