package com.flue.launcher.surface.config

import com.flue.launcher.surface.apps.PublicAppOverrides
import com.flue.launcher.surface.mode.ConfigScope

/**
 * 备份包里伪装配置的媒体文件（R06、R10）。
 *
 * 设置区段会带上全部 `fflue.disguise::*` 键，但伪装模式的图片／视频表盘、字体、应用列表背景
 * 只以本机绝对路径存着，公开图标只存文件名；只带键过去，换机或重装后都指向不存在的文件。
 * 这里定义这些文件在包里的位置，恢复时落进本机伪装媒体目录并改写路径。
 */
object DisguiseBackup {
    const val MANIFEST_KEY = "disguiseMedia"

    const val PHOTO_ENTRY = "disguise/watchfaces/photo/media"
    const val VIDEO_ENTRY = "disguise/watchfaces/video/media"
    const val FONT_ENTRY = "disguise/watchfaces/font/current"
    const val APP_LIST_BACKGROUND_ENTRY = "disguise/app_list_background/image"
    const val PUBLIC_ICONS_PREFIX = "disguise/public_icons/"

    /** 只在本机有意义的伪装键（绝对路径、上次错误），不随设置区段导出或导入，由媒体恢复重写。 */
    fun localOnlyPhysicalNames(pathKeys: Collection<String>): Set<String> =
        pathKeys.mapTo(HashSet()) { LauncherSettingsScopes.physicalName(it, ConfigScope.DISGUISE) }

    /** 设置区段里按原名清洗（应用顺序、隐藏集、侧屏）时用的名称：伪装键还原成原名。 */
    fun logicalName(physical: String): String =
        if (LauncherSettingsScopes.isDisguisePhysical(physical)) LauncherSettingsScopes.logicalNameOfDisguise(physical) else physical

    /** 公开图标在包里的条目；不安全的文件名直接跳过。 */
    fun publicIconEntry(fileName: String): String? =
        fileName.takeIf(PublicAppOverrides::isSafeIconFile)?.let { PUBLIC_ICONS_PREFIX + it }

    /** 包里的公开图标条目还原成文件名；越界或不安全时返回 null。 */
    fun publicIconName(entry: String): String? =
        entry.removePrefix(PUBLIC_ICONS_PREFIX).takeIf { entry.startsWith(PUBLIC_ICONS_PREFIX) && PublicAppOverrides.isSafeIconFile(it) }

    /** 条目扩展名：只取安全的字母数字后缀，否则用默认值。 */
    fun extensionOf(fileName: String, default: String): String =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: default
}
