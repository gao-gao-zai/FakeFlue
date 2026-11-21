package com.flue.launcher.surface.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.apps.PublicAppOverride
import com.flue.launcher.surface.apps.PublicAppOverrides
import com.flue.launcher.surface.apps.PublicAppRules
import com.flue.launcher.surface.apps.PublicIconStore
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 伪装桌面管理 → 公开名称与图标（R10）。只列已公开的条目；改动只写伪装配置，完整桌面照旧。
 *
 * @param apps 全部已安装条目（不经模式筛选、不带覆盖），用来显示原名称和原图标。
 */
@Composable
internal fun PublicOverridesPage(apps: List<AppInfo>, onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    var revision by remember { mutableIntStateOf(0) }
    var overrides by remember { mutableStateOf<Map<String, PublicAppOverride>?>(null) }
    var publicApps by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(runtime, revision) {
        publicApps = runtime.disguisePublicApps()
        overrides = runtime.disguisePublicOverrides()
    }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val whitelist = publicApps
    val shown = remember(apps, whitelist) {
        if (whitelist == null) {
            emptyList()
        } else {
            apps.filterNot { it.isFolder }
                .filter {
                    val builtIn = it.isBuiltInSettingsEntry ||
                        com.flue.launcher.surface.apps.SelfModules.isModule(it, context.packageName)
                    PublicAppRules.isPublic(it.componentKey, it.packageName, builtIn, whitelist)
                }
                .distinctBy { it.componentKey }
        }
    }
    val editing = editingKey?.let { key -> shown.firstOrNull { it.componentKey == key } }
    if (editing != null) {
        BackHandler { editingKey = null }
        PublicOverrideEditor(
            app = editing,
            current = overrides?.get(editing.componentKey) ?: PublicAppOverride(),
            iconDir = runtime.publicIconDir(),
            onDone = {
                editingKey = null
                revision++
            },
        )
        return
    }
    FfluePageScaffold(
        stateKey = "public_overrides",
        title = stringResource(R.string.surface_public_overrides_title),
        subtitle = stringResource(R.string.surface_public_overrides_subtitle),
        onBack = onBack,
    ) {
        if (whitelist != null && shown.isEmpty()) {
            item(key = "public_overrides_empty") {
                AgentSettingsGroup {
                    AgentSettingsEntry(title = stringResource(R.string.surface_public_apps_empty), last = true)
                }
            }
        }
        items(shown, key = { it.componentKey }) { app ->
            val override = overrides?.get(app.componentKey)
            val icon = rememberOverrideIcon(runtime.publicIconDir(), override?.iconFile)
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = override?.label ?: app.label,
                    subtitle = if (override == null) {
                        stringResource(R.string.surface_public_overrides_unchanged)
                    } else {
                        stringResource(R.string.surface_public_overrides_original, app.label)
                    },
                    onClick = { editingKey = app.componentKey },
                    leading = {
                        Image(
                            bitmap = icon ?: app.cachedIcon,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp).clip(CircleShape),
                        )
                    },
                    last = true,
                )
            }
        }
    }
}

@Composable
private fun PublicOverrideEditor(
    app: AppInfo,
    current: PublicAppOverride,
    iconDir: File,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val scope = rememberCoroutineScope()
    var label by rememberSaveable(app.componentKey) { mutableStateOf(current.label.orEmpty()) }
    // 新选的图标先只存成文件，点保存才写进配置；取消时由清理逻辑删掉没被引用的文件。
    var iconFile by rememberSaveable(app.componentKey) { mutableStateOf(current.iconFile) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val name = withContext(Dispatchers.IO) { PublicIconStore.import(context, uri, iconDir) }
            busy = false
            if (name == null) {
                Toast.makeText(context, R.string.surface_public_overrides_icon_failed, Toast.LENGTH_SHORT).show()
            } else {
                iconFile = name
            }
        }
    }
    fun save(next: PublicAppOverride) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { runtime.updatePublicOverride(app.componentKey) { next } }.isSuccess
            busy = false
            if (ok) onDone() else Toast.makeText(context, R.string.display_save_failed, Toast.LENGTH_SHORT).show()
        }
    }
    fun cancel() {
        // 走一次空更新，顺便删掉这次选了又没保存的图标文件。
        scope.launch {
            runCatching { runtime.updatePublicOverride(app.componentKey) { it } }
            onDone()
        }
    }
    val preview = rememberOverrideIcon(iconDir, iconFile)
    FfluePageScaffold(
        stateKey = "public_override_${app.componentKey}",
        title = stringResource(R.string.surface_public_overrides_edit_title),
        subtitle = stringResource(R.string.surface_public_overrides_original, app.label),
        onBack = ::cancel,
        bottomBar = {
            FlueOutlinedButton(onClick = ::cancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.display_cancel), fontSize = 13.sp)
            }
            FlueButton(
                onClick = { save(PublicAppOverride(label = label, iconFile = iconFile)) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.display_save), fontSize = 13.sp) }
        },
    ) {
        item(key = "override_preview") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    bitmap = preview ?: app.cachedIcon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(CircleShape),
                )
                Text(
                    text = PublicAppOverrides.normalizeLabel(label) ?: app.label,
                    fontSize = 13.sp,
                )
            }
        }
        item(key = "override_label") {
            FlueTextField(
                value = label,
                onValueChange = { label = it.take(PublicAppOverrides.MAX_LABEL_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.surface_public_overrides_name),
                placeholder = app.label,
            )
        }
        item(key = "override_actions") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_public_overrides_pick_icon),
                    enabled = !busy,
                    onClick = { picker.launch("image/*") },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_public_overrides_reset_icon),
                    enabled = !busy && iconFile != null,
                    onClick = { iconFile = null },
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_public_overrides_reset_all),
                    enabled = !busy && (current != PublicAppOverride() || label.isNotBlank() || iconFile != null),
                    onClick = { save(PublicAppOverride()) },
                    last = true,
                )
            }
        }
    }
}

@Composable
private fun rememberOverrideIcon(dir: File, file: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, dir, file) {
        value = if (file == null || !PublicAppOverrides.isSafeIconFile(file)) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(File(dir, file).path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bitmap
}
