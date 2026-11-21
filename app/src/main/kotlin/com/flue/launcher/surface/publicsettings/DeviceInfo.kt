package com.flue.launcher.surface.publicsettings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.flue.launcher.R
import java.util.Locale

/** 关于页的五组信息（R26）。组内顺序即显示顺序，系统版本号靠前。 */
enum class AboutGroup(val titleRes: Int) {
    SYSTEM(R.string.about_group_system),
    DEVICE(R.string.about_group_device),
    STORAGE(R.string.about_group_storage),
    MEMORY(R.string.about_group_memory),
    BATTERY(R.string.about_group_battery),
}

enum class AboutField(val group: AboutGroup, val labelRes: Int) {
    BUILD_NUMBER(AboutGroup.SYSTEM, R.string.about_field_build_number),
    ANDROID_VERSION(AboutGroup.SYSTEM, R.string.about_field_android_version),
    MODEL(AboutGroup.DEVICE, R.string.about_field_model),
    MANUFACTURER(AboutGroup.DEVICE, R.string.about_field_manufacturer),
    STORAGE_TOTAL(AboutGroup.STORAGE, R.string.about_field_storage_total),
    STORAGE_FREE(AboutGroup.STORAGE, R.string.about_field_storage_free),
    MEMORY_TOTAL(AboutGroup.MEMORY, R.string.about_field_memory_total),
    MEMORY_FREE(AboutGroup.MEMORY, R.string.about_field_memory_free),
    BATTERY_LEVEL(AboutGroup.BATTERY, R.string.about_field_battery_level),
    BATTERY_STATUS(AboutGroup.BATTERY, R.string.about_field_battery_status),
    BATTERY_TEMPERATURE(AboutGroup.BATTERY, R.string.about_field_battery_temperature),
}

/** 纯格式化与显示规则。读不到时为 null，界面显示“未知”，不编造数值。 */
object AboutFormat {
    /** 按 1000 进制（与系统设置一致），1 GB 以上保留一位小数。 */
    fun bytes(value: Long?): String? {
        if (value == null || value < 0) return null
        val gb = value / 1_000_000_000.0
        return if (gb >= 1.0) String.format(Locale.ROOT, "%.1f GB", gb)
        else String.format(Locale.ROOT, "%d MB", value / 1_000_000)
    }

    fun batteryLevel(level: Int?, scale: Int?): String? {
        if (level == null || scale == null || level < 0 || scale <= 0) return null
        return "${(level * 100 / scale).coerceIn(0, 100)}%"
    }

    /** BatteryManager 给的是 0.1 °C。 */
    fun temperature(tenthsCelsius: Int?): String? {
        if (tenthsCelsius == null || tenthsCelsius == Int.MIN_VALUE) return null
        return String.format(Locale.ROOT, "%.1f °C", tenthsCelsius / 10.0)
    }

    /** 覆盖非空就显示覆盖值，否则真实值；都没有就是 null（未知）。覆盖只改展示。 */
    fun display(real: String?, override: String?): String? =
        override?.trim()?.takeIf(String::isNotEmpty) ?: real?.trim()?.takeIf(String::isNotEmpty)
}

/** 读取真实设备信息。每项独立读取，失败只影响该项。 */
object DeviceInfoReader {
    fun read(context: Context): Map<AboutField, String?> {
        val app = context.applicationContext
        val values = mutableMapOf<AboutField, String?>()
        values[AboutField.BUILD_NUMBER] = Build.DISPLAY?.takeIf(String::isNotBlank)
        values[AboutField.ANDROID_VERSION] = Build.VERSION.RELEASE?.takeIf(String::isNotBlank)?.let { "Android $it" }
        values[AboutField.MODEL] = Build.MODEL?.takeIf(String::isNotBlank)
        values[AboutField.MANUFACTURER] = Build.MANUFACTURER?.takeIf(String::isNotBlank)
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) }

        runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()?.let { stat ->
            values[AboutField.STORAGE_TOTAL] = AboutFormat.bytes(stat.totalBytes)
            values[AboutField.STORAGE_FREE] = AboutFormat.bytes(stat.availableBytes)
        }
        runCatching {
            val info = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            info
        }.getOrNull()?.let { info ->
            values[AboutField.MEMORY_TOTAL] = AboutFormat.bytes(info.totalMem.takeIf { it > 0 })
            values[AboutField.MEMORY_FREE] = AboutFormat.bytes(info.availMem.takeIf { it > 0 })
        }
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        if (battery != null) {
            values[AboutField.BATTERY_LEVEL] = AboutFormat.batteryLevel(
                battery.intExtraOrNull(BatteryManager.EXTRA_LEVEL),
                battery.intExtraOrNull(BatteryManager.EXTRA_SCALE),
            )
            values[AboutField.BATTERY_STATUS] = battery.intExtraOrNull(BatteryManager.EXTRA_STATUS)
                ?.let(::statusRes)?.let(app::getString)
            values[AboutField.BATTERY_TEMPERATURE] = AboutFormat.temperature(
                battery.intExtraOrNull(BatteryManager.EXTRA_TEMPERATURE),
            )
        }
        return values
    }

    private fun Intent.intExtraOrNull(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    fun statusRes(status: Int): Int? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> R.string.about_battery_charging
        BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.about_battery_discharging
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.about_battery_not_charging
        BatteryManager.BATTERY_STATUS_FULL -> R.string.about_battery_full
        else -> null
    }
}

/**
 * 关于页逐项显示覆盖（R26）。设备级、跨模式保留，只在完整设置里写；留空恢复真实值。
 * 用同步 SharedPreferences：只有十来个短字符串。
 */
object AboutOverrides {
    private const val PREFS = "fflue_about_overrides"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(field: AboutField) = field.name.lowercase(Locale.ROOT)

    fun read(context: Context): Map<AboutField, String> = runCatching {
        val p = prefs(context)
        AboutField.entries.mapNotNull { f -> p.getString(key(f), null)?.takeIf(String::isNotBlank)?.let { f to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun save(context: Context, overrides: Map<AboutField, String>): Boolean = runCatching {
        prefs(context).edit().apply {
            AboutField.entries.forEach { f ->
                val value = overrides[f]?.trim().orEmpty()
                if (value.isEmpty()) remove(key(f)) else putString(key(f), value)
            }
        }.commit()
    }.getOrDefault(false)
}
