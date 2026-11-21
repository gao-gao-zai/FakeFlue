package com.flue.launcher.surface.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.apps.HanLatin
import com.flue.launcher.surface.apps.PublicAppCandidate
import com.flue.launcher.surface.apps.PublicAppCatalog
import com.flue.launcher.surface.apps.PublicAppRules
import com.flue.launcher.surface.apps.PublicAppSort
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueSwitchIndicator
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * 伪装桌面的公开应用选择器（R10）。管理页和以后的引导共用这一页。
 *
 * 只有点保存才写入伪装配置；搜索和排序不改选中结果，也不碰桌面排序。
 * 内置入口总是公开，不在列表里出现。
 *
 * 同一页也用来选择通知单独放行的应用（R11，[AppPickerPurpose.NOTIFICATION_ALLOW]）：
 * 那份名单按包名保存，列表按包去重，不列已公开的应用（它们的通知总是展示）。
 *
 * @param apps 全部已安装条目（不经模式筛选），顺序即当前桌面顺序。
 */
@Composable
internal fun PublicAppPickerPage(
    apps: List<AppInfo>,
    onBack: () -> Unit,
    purpose: AppPickerPurpose = AppPickerPurpose.PUBLIC_APPS,
) {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val style = LauncherTheme.style

    var draft by remember { mutableStateOf<Set<String>?>(null) }
    var publicSnapshot by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(runtime, purpose) {
        val publicApps = runtime.disguisePublicApps()
        publicSnapshot = publicApps
        draft = when (purpose) {
            AppPickerPurpose.PUBLIC_APPS -> publicApps
            AppPickerPurpose.NOTIFICATION_ALLOW -> runtime.disguiseNotificationAllowedApps()
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(PublicAppSort.DESKTOP) }
    var selectedSnapshot by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }

    val candidates by produceState(initialValue = emptyList<PublicAppCandidate>(), apps, purpose, publicSnapshot) {
        val publicApps = publicSnapshot ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val latin = HanLatin.create()
            // 内置设置和本应用的内置模块总是公开，不在选择器里列出（R08、R32）。
            apps.filterNot {
                it.isBuiltInSettingsEntry || it.isFolder ||
                    com.flue.launcher.surface.apps.SelfModules.isModule(it, context.packageName)
            }
                .let { list ->
                    when (purpose) {
                        AppPickerPurpose.PUBLIC_APPS -> list
                        AppPickerPurpose.NOTIFICATION_ALLOW -> list
                            .filterNot { PublicAppRules.isPackagePublic(it.packageName, publicApps, context.packageName) }
                            .distinctBy { it.packageName }
                    }
                }
                .mapIndexed { index, app ->
                    PublicAppCandidate(
                        key = purpose.keyOf(app),
                        label = app.label,
                        packageName = app.packageName,
                        desktopIndex = index,
                        latin = latin?.invoke(app.label),
                    )
                }
        }
    }
    val iconByKey = remember(apps, purpose) { apps.reversed().associateBy(purpose::keyOf) }
    val collator = remember { Collator.getInstance() }
    val selected = draft.orEmpty()
    val shown = remember(candidates, query, sort, selectedSnapshot) {
        PublicAppCatalog.arrange(candidates, query, sort, selectedSnapshot, collator::compare)
    }
    val candidateKeys = remember(candidates) { candidates.mapTo(HashSet()) { it.key } }
    val selectedCount = selected.count { it in candidateKeys }

    FfluePageScaffold(
        stateKey = purpose.stateKey,
        title = stringResource(purpose.titleRes),
        subtitle = stringResource(purpose.subtitleRes),
        onBack = onBack,
        bottomBar = {
            FlueOutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.display_cancel), fontSize = 13.sp)
            }
            FlueButton(
                onClick = {
                    val toSave = draft ?: return@FlueButton
                    if (saving) return@FlueButton
                    saving = true
                    scope.launch {
                        val ok = runCatching {
                            when (purpose) {
                                AppPickerPurpose.PUBLIC_APPS -> runtime.savePublicApps(toSave)
                                AppPickerPurpose.NOTIFICATION_ALLOW -> runtime.saveNotificationAllowedApps(toSave)
                            }
                        }.isSuccess
                        saving = false
                        if (ok) {
                            onBack()
                        } else {
                            Toast.makeText(context, R.string.display_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = draft != null && !saving,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.display_save), fontSize = 13.sp) }
        },
    ) {
        item(key = "public_apps_search") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlueTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.surface_public_apps_search),
                    supportingText = stringResource(
                        if (HanLatin.supported) {
                            R.string.surface_public_apps_search_scope_full
                        } else {
                            R.string.surface_public_apps_search_scope_basic
                        },
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PublicAppSort.entries.forEach { option ->
                        val active = option == sort
                        Text(
                            text = stringResource(option.labelRes()),
                            color = if (active) style.screenBackground else style.bodyColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(style.smallControlShape)
                                .background(if (active) style.accentColor else style.topBarChipColor)
                                .clickable {
                                    sort = option
                                    selectedSnapshot = selected
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.surface_public_apps_count, selectedCount, candidates.size),
                    color = style.secondaryTextColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        if (draft != null && shown.isEmpty()) {
            item(key = "public_apps_empty") {
                AgentSettingsGroup {
                    AgentSettingsEntry(title = stringResource(R.string.surface_public_apps_empty), last = true)
                }
            }
        }
        if (draft != null) {
            items(shown, key = { it.key }) { candidate ->
                val checked = candidate.key in selected
                AgentSettingsGroup {
                    AgentSettingsEntry(
                        title = candidate.label,
                        subtitle = candidate.packageName,
                        onClick = {
                            draft = selected.toMutableSet().apply {
                                if (checked) remove(candidate.key) else add(candidate.key)
                            }
                        },
                        leading = iconByKey[candidate.key]?.let { app ->
                            {
                                Image(
                                    bitmap = app.cachedPlainIcon ?: app.cachedIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp).clip(style.smallControlShape),
                                )
                            }
                        },
                        trailing = { FlueSwitchIndicator(checked = checked) },
                        last = true,
                    )
                }
            }
        }
    }
}

internal enum class AppPickerPurpose(val stateKey: String, val titleRes: Int, val subtitleRes: Int) {
    PUBLIC_APPS("public_apps", R.string.surface_public_apps_title, R.string.surface_public_apps_subtitle),
    NOTIFICATION_ALLOW(
        "notification_allowed_apps",
        R.string.surface_notification_allow_title,
        R.string.surface_notification_allow_subtitle,
    ),
    ;

    fun keyOf(app: AppInfo): String = when (this) {
        PUBLIC_APPS -> app.componentKey
        NOTIFICATION_ALLOW -> app.packageName
    }
}

private fun PublicAppSort.labelRes(): Int = when (this) {
    PublicAppSort.DESKTOP -> R.string.surface_public_apps_sort_desktop
    PublicAppSort.NAME -> R.string.surface_public_apps_sort_name
    PublicAppSort.SELECTED_FIRST -> R.string.surface_public_apps_sort_selected
}
