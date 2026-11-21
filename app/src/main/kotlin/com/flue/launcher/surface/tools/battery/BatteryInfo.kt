package com.flue.launcher.surface.tools.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.flue.launcher.R
import com.flue.launcher.surface.publicsettings.AboutFormat
import com.flue.launcher.surface.publicsettings.DeviceInfoReader
import java.util.Locale

/**
 * 真实电池信息（R31）。数据来自系统电池粘性广播与 [BatteryManager]；某项读不到就是 null，
 * 界面显示“未知”，不编造。不使用关于页的显示覆盖——这里展示的是真实值。
 */
data class BatteryInfo(
    val present: Boolean = true,
    val levelPercent: Int? = null,
    /** 字符串资源；null 为未知。 */
    val statusRes: Int? = null,
    val pluggedRes: Int? = null,
    val healthRes: Int? = null,
    val temperature: String? = null,
    val voltage: String? = null,
    val technology: String? = null,
    /** 充满还需（毫秒），系统给不出时为 null。 */
    val chargeTimeRemainingMs: Long? = null,
)

object BatteryFormat {
    fun levelPercent(level: Int?, scale: Int?): Int? =
        AboutFormat.batteryLevel(level, scale)?.removeSuffix("%")?.toIntOrNull()

    /** 系统给的是毫伏。 */
    fun voltage(millivolts: Int?): String? {
        if (millivolts == null || millivolts <= 0) return null
        return String.format(Locale.ROOT, "%.2f V", millivolts / 1000.0)
    }

    fun pluggedRes(plugged: Int?): Int? = when (plugged) {
        null -> null
        0 -> R.string.surface_tool_battery_plug_none
        BatteryManager.BATTERY_PLUGGED_AC -> R.string.surface_tool_battery_plug_ac
        BatteryManager.BATTERY_PLUGGED_USB -> R.string.surface_tool_battery_plug_usb
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.surface_tool_battery_plug_wireless
        PLUGGED_DOCK -> R.string.surface_tool_battery_plug_dock
        else -> null
    }

    fun healthRes(health: Int?): Int? = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> R.string.surface_tool_battery_health_good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.surface_tool_battery_health_overheat
        BatteryManager.BATTERY_HEALTH_DEAD -> R.string.surface_tool_battery_health_dead
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.surface_tool_battery_health_over_voltage
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.surface_tool_battery_health_failure
        BatteryManager.BATTERY_HEALTH_COLD -> R.string.surface_tool_battery_health_cold
        else -> null
    }

    /** `BatteryManager.BATTERY_PLUGGED_DOCK`（API 33）。 */
    private const val PLUGGED_DOCK = 8
}

object BatteryReader {
    fun read(context: Context): BatteryInfo {
        val app = context.applicationContext
        val sticky = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        return from(app, sticky)
    }

    fun from(context: Context, battery: Intent?): BatteryInfo {
        if (battery == null) return BatteryInfo(chargeTimeRemainingMs = null)
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val status = battery.intOrNull(BatteryManager.EXTRA_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val remaining = if (charging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager?.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it > 0 }
        } else {
            null
        }
        return BatteryInfo(
            present = battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
            levelPercent = BatteryFormat.levelPercent(
                battery.intOrNull(BatteryManager.EXTRA_LEVEL),
                battery.intOrNull(BatteryManager.EXTRA_SCALE),
            ),
            statusRes = status?.let(DeviceInfoReader::statusRes),
            pluggedRes = BatteryFormat.pluggedRes(battery.intOrNull(BatteryManager.EXTRA_PLUGGED)),
            healthRes = BatteryFormat.healthRes(battery.intOrNull(BatteryManager.EXTRA_HEALTH)),
            temperature = AboutFormat.temperature(battery.intOrNull(BatteryManager.EXTRA_TEMPERATURE)),
            voltage = BatteryFormat.voltage(battery.intOrNull(BatteryManager.EXTRA_VOLTAGE)),
            technology = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.trim()?.takeIf(String::isNotEmpty),
            chargeTimeRemainingMs = remaining,
        )
    }

    private fun Intent.intOrNull(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
}
