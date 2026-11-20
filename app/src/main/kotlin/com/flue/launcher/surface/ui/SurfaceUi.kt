package com.flue.launcher.surface.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.mode.DisguiseEntryRequirement
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode

internal fun SurfaceMode.labelRes(): Int = when (this) {
    SurfaceMode.FULL -> R.string.surface_mode_full
    SurfaceMode.DISGUISE -> R.string.surface_mode_disguise
    SurfaceMode.EXTERNAL -> R.string.surface_mode_external
}

internal fun SurfaceMode.descriptionRes(): Int = when (this) {
    SurfaceMode.FULL -> R.string.surface_mode_full_desc
    SurfaceMode.DISGUISE -> R.string.surface_mode_disguise_desc
    SurfaceMode.EXTERNAL -> R.string.surface_mode_external_desc
}

/** 把切换结果告诉用户；需要用户在系统里选默认桌面时直接打开真实默认应用设置（R07）。 */
internal fun Context.reportModeSwitch(result: ModeSwitchResult, target: SurfaceMode) {
    val message = when (result) {
        ModeSwitchResult.Success, ModeSwitchResult.AlreadyInMode -> when (target) {
            SurfaceMode.DISGUISE -> R.string.surface_result_entered_disguise
            SurfaceMode.FULL -> R.string.surface_result_restored_full
            SurfaceMode.EXTERNAL -> null
        }
        ModeSwitchResult.ExitedButDefaultHomeIsSelf -> {
            openDefaultHomeSettings()
            R.string.surface_result_default_home_self
        }
        ModeSwitchResult.Busy -> R.string.surface_result_busy
        is ModeSwitchResult.Blocked -> when (result.requirement) {
            DisguiseEntryRequirement.BASE_CONFIGURATION -> R.string.surface_result_blocked_config
            DisguiseEntryRequirement.TUTORIAL -> R.string.surface_result_blocked_tutorial
        }
        is ModeSwitchResult.Failed -> when (result.reason) {
            ModeSwitchResult.FailureReason.PERSIST_FAILED -> R.string.surface_result_failed_persist
            ModeSwitchResult.FailureReason.EXTERNAL_TARGET_MISSING -> R.string.surface_result_target_missing
            ModeSwitchResult.FailureReason.EXTERNAL_TARGET_INVALID -> R.string.surface_result_target_invalid
            ModeSwitchResult.FailureReason.EXTERNAL_LAUNCH_FAILED -> R.string.surface_result_launch_failed
        }
    }
    if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** 打开系统默认桌面设置；没有该页面时退回默认应用设置，再退回系统设置。 */
internal fun Context.openDefaultHomeSettings() {
    val candidates = listOf(
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

internal fun Context.homeLabel(component: ComponentName): String =
    runCatching {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: component.packageName

/** 系统默认桌面的说明文字；每次回到前台都重新读回。 */
internal fun Context.defaultHomeDescription(): String {
    val pkg = SurfaceHome.defaultHomePackage(this) ?: return getString(R.string.surface_default_home_none)
    if (pkg == packageName) return getString(R.string.surface_default_home_is_self)
    val label = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    return getString(R.string.surface_default_home_other_fmt, label)
}

/** 每次 ON_RESUME 加一，用于从系统设置返回后重新读回状态。 */
@Composable
internal fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}
