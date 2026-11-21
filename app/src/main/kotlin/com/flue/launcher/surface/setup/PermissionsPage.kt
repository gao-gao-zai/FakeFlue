package com.flue.launcher.surface.setup

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flue.launcher.R
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 读各项授权的真实状态（R28）。只读系统状态，不请求、不探测 Root。
 * Root 只有用户点了“检测”才运行一次 `su`，结果只记在本进程内。
 */
object PermissionProbe {
    @Volatile
    private var rootResult: Boolean? = null

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun read(context: Context): Map<PermissionItem, PermissionStatus> = PermissionItem.entries.associateWith { status(context, it) }

    private fun granted(context: Context, permission: String) =
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }

    private fun of(value: Boolean) = if (value) PermissionStatus.GRANTED else PermissionStatus.DENIED

    fun status(context: Context, item: PermissionItem): PermissionStatus = runCatching {
        when (item) {
            PermissionItem.ROOT -> rootResult?.let(::of) ?: PermissionStatus.UNCHECKED
            PermissionItem.SHIZUKU -> if (shizukuInstalled(context)) PermissionStatus.UNCHECKED else PermissionStatus.DENIED
            PermissionItem.ACCESSIBILITY -> of(accessibilityEnabled(context))
            PermissionItem.OVERLAY -> of(Settings.canDrawOverlays(context))
            PermissionItem.WRITE_SETTINGS -> of(Settings.System.canWrite(context))
            PermissionItem.NOTIFICATION_LISTENER ->
                of(context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context))
            PermissionItem.DND_ACCESS ->
                of(context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true)
            PermissionItem.POST_NOTIFICATIONS -> of(
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    granted(context, Manifest.permission.POST_NOTIFICATIONS) == PermissionStatus.GRANTED) &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )
            PermissionItem.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                of(context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true)
            } else {
                PermissionStatus.NOT_APPLICABLE
            }
            PermissionItem.LOCATION -> granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            PermissionItem.CALENDAR -> granted(context, Manifest.permission.READ_CALENDAR)
            PermissionItem.ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                granted(context, Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                PermissionStatus.NOT_APPLICABLE
            }
        }
    }.getOrDefault(PermissionStatus.DENIED)

    fun shizukuInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    private fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = ComponentName(context, FlueAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    /** 用户主动请求 Root：运行一次 `su -c id`，看是否拿到 uid 0。授权成功不代表控制已经可用（R28）。 */
    suspend fun requestRoot(): Boolean {
        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(20_000) {
                runCatching {
                    val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                    val out = process.inputStream.bufferedReader().readText()
                    process.waitFor(15, TimeUnit.SECONDS)
                    out.contains("uid=0")
                }.getOrDefault(false)
            } ?: false
        }
        rootResult = result
        return result
    }

    /** 打开对应的系统授权页；运行时权限由页面直接请求。 */
    fun settingsIntent(context: Context, item: PermissionItem): Intent? {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (item) {
            PermissionItem.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionItem.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            PermissionItem.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            PermissionItem.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            PermissionItem.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            PermissionItem.POST_NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            PermissionItem.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            } else {
                null
            }
            PermissionItem.SHIZUKU -> context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            else -> null
        }
    }

    fun runtimePermissions(item: PermissionItem): Array<String>? = when (item) {
        PermissionItem.LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionItem.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR)
        PermissionItem.POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            null
        }
        PermissionItem.ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            null
        }
        else -> null
    }
}

internal fun PermissionGroup.titleRes(): Int = when (this) {
    PermissionGroup.SYSTEM_CONTROL -> R.string.surface_perm_group_system
    PermissionGroup.PHONE_SMS -> R.string.surface_perm_group_phone
    PermissionGroup.NOTIFICATIONS -> R.string.surface_perm_group_notifications
    PermissionGroup.FACE_DATA -> R.string.surface_perm_group_face
}

internal fun PermissionItem.labelRes(): Int = when (this) {
    PermissionItem.ROOT -> R.string.surface_perm_root
    PermissionItem.SHIZUKU -> R.string.surface_perm_shizuku
    PermissionItem.ACCESSIBILITY -> R.string.surface_perm_accessibility
    PermissionItem.OVERLAY -> R.string.surface_perm_overlay
    PermissionItem.WRITE_SETTINGS -> R.string.surface_perm_write_settings
    PermissionItem.NOTIFICATION_LISTENER -> R.string.surface_perm_notification_listener
    PermissionItem.DND_ACCESS -> R.string.surface_perm_dnd
    PermissionItem.POST_NOTIFICATIONS -> R.string.surface_tool_perm_notifications
    PermissionItem.EXACT_ALARM -> R.string.surface_tool_perm_exact_alarm
    PermissionItem.LOCATION -> R.string.surface_perm_location
    PermissionItem.CALENDAR -> R.string.surface_perm_calendar
    PermissionItem.ACTIVITY -> R.string.surface_perm_activity
}

/**
 * 授权页（R19、R28）：按用途分组，组标题显示“已配置／本系统总项数”和已配置的小项；
 * 每行是小项名称、真实状态和操作。只有点了某一行才请求，回到页面后重新读真实状态。
 */
@Composable
internal fun PermissionsPage(
    onBack: () -> Unit,
    title: String = stringResource(R.string.surface_perm_title),
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val statuses = remember(resumeTick, refresh) { PermissionProbe.read(context) }
    val summaries = remember(statuses) { PermissionSummary.of(statuses) }
    val runtimeRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
    }
    var rootChecking by remember { mutableIntStateOf(0) }

    fun act(item: PermissionItem) {
        if (item == PermissionItem.ROOT) {
            if (rootChecking > 0) return
            rootChecking++
            scope.launch {
                val ok = PermissionProbe.requestRoot()
                // 记下用户主动确认的结果：只有确认过才会启动导航守护（R28、R29）。
                val runtime = com.flue.launcher.surface.SurfaceRuntime.get(context)
                runtime.stateStore.rootConfirmed = ok
                runtime.systemControl.retry()
                rootChecking = 0
                refresh++
                Toast.makeText(
                    context,
                    if (ok) R.string.surface_perm_root_ok else R.string.surface_perm_root_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        // 运行时权限已给但仍未生效（例如通知在系统里被关）时改开对应系统页，不反复弹已通过的请求。
        PermissionProbe.runtimePermissions(item)
            ?.takeUnless { perms -> perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
            ?.let {
                runtimeRequest.launch(it)
                return
            }
        val intent = PermissionProbe.settingsIntent(context, item)
        val opened = intent != null && runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            val fallback = runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }.isSuccess
            if (!fallback) Toast.makeText(context, R.string.public_settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val configuredLabels = summaries.associate { summary ->
        summary.group to summary.configuredItems.map { stringResource(it.labelRes()) }
    }
    FfluePageScaffold(
        stateKey = "surface_permissions",
        title = title,
        subtitle = stringResource(R.string.surface_perm_subtitle),
        onBack = onBack,
        bottomBar = bottomBar,
    ) {
        summaries.forEach { summary ->
            item(key = "perm_${summary.group.name}") {
                val names = configuredLabels[summary.group].orEmpty()
                AgentSettingsGroup(stringResource(summary.group.titleRes())) {
                    val items = PermissionItem.entries.filter {
                        it.group == summary.group && statuses[it] != PermissionStatus.NOT_APPLICABLE
                    }
                    if (items.isEmpty()) {
                        AgentSettingsEntry(title = stringResource(R.string.surface_perm_group_empty), last = true)
                    } else {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_perm_group_count, summary.configured, summary.total),
                            subtitle = if (names.isEmpty()) {
                                stringResource(R.string.surface_perm_configured_none)
                            } else {
                                names.joinToString(stringResource(R.string.surface_perm_list_separator))
                            },
                        )
                        items.forEachIndexed { index, item ->
                            val status = statuses[item] ?: PermissionStatus.DENIED
                            AgentSettingsEntry(
                                title = stringResource(item.labelRes()),
                                subtitle = stringResource(
                                    when {
                                        item == PermissionItem.ROOT && rootChecking > 0 -> R.string.surface_perm_status_checking
                                        status == PermissionStatus.GRANTED -> R.string.surface_perm_status_granted
                                        item == PermissionItem.ROOT -> R.string.surface_perm_status_root_unchecked
                                        item == PermissionItem.SHIZUKU && status == PermissionStatus.UNCHECKED ->
                                            R.string.surface_perm_status_shizuku_installed
                                        item == PermissionItem.SHIZUKU -> R.string.surface_perm_status_shizuku_missing
                                        else -> R.string.surface_perm_status_denied
                                    },
                                ),
                                onClick = if (status == PermissionStatus.GRANTED && item != PermissionItem.ROOT) {
                                    null
                                } else {
                                    { act(item) }
                                },
                                last = index == items.lastIndex,
                            )
                        }
                    }
                }
            }
        }
        item(key = "perm_note") {
            Text(
                text = stringResource(R.string.surface_perm_note),
                color = style.secondaryTextColor,
                fontSize = 12.sp,
            )
        }
    }
}
