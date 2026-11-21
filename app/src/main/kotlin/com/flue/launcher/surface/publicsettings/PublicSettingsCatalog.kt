package com.flue.launcher.surface.publicsettings

import android.content.Intent
import android.provider.Settings
import com.flue.launcher.R

/** R27：可以选择 FFlue 内置页或系统设置的四个设备页。 */
enum class DevicePage { NETWORK, BLUETOOTH, DISPLAY, SOUND }

enum class DeviceRouteChoice { SYSTEM, FFLUE }

/**
 * 设备页路由（R27 前半）。未配置默认系统页；只有明确选了 FFlue 且已知内置页可用才进内置页。
 * 路由本身不查询系统状态、不探测权限，选系统就直接打开系统页。
 */
object DeviceRouter {
    fun route(choice: DeviceRouteChoice?, builtInAvailable: Boolean): DeviceRouteChoice =
        if (choice == DeviceRouteChoice.FFLUE && builtInAvailable) DeviceRouteChoice.FFLUE else DeviceRouteChoice.SYSTEM

    /** 目前还没有内置设备控制页（R31 新增内置模块时接入）。 */
    fun builtInAvailable(page: DevicePage): Boolean = false
}

/**
 * 伪装公开设置页的项目（R26）：只放普通手表会有的设置。不放安装卸载、切换桌面、隐藏应用管理和高级调试。
 *
 * [systemActions] 按顺序尝试，前一个打不开再试下一个；全部失败由页面提示重试。
 */
enum class PublicSettingsItem(
    val labelRes: Int,
    val systemActions: List<String>,
    val devicePage: DevicePage? = null,
) {
    NETWORK(R.string.public_settings_network, listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS), DevicePage.NETWORK),
    BLUETOOTH(R.string.public_settings_bluetooth, listOf(Settings.ACTION_BLUETOOTH_SETTINGS), DevicePage.BLUETOOTH),
    DISPLAY(R.string.public_settings_display, listOf(Settings.ACTION_DISPLAY_SETTINGS), DevicePage.DISPLAY),
    SOUND(R.string.public_settings_sound, listOf(Settings.ACTION_SOUND_SETTINGS), DevicePage.SOUND),
    NOTIFICATIONS(R.string.public_settings_notifications, listOf("android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_SETTINGS)),
    BATTERY(R.string.public_settings_battery, listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)),
    APPS(R.string.public_settings_apps, listOf(Settings.ACTION_APPLICATION_SETTINGS)),
    SECURITY(R.string.public_settings_security, listOf(Settings.ACTION_SECURITY_SETTINGS)),
    SYSTEM(R.string.public_settings_system, listOf(Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SETTINGS)),

    /** 关于是内部页面，不跳系统。 */
    ABOUT(R.string.public_settings_about, emptyList()),
}
