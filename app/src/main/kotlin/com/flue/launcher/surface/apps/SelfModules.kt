package com.flue.launcher.surface.apps

/**
 * 本应用自带的内置模块（R08、R31、R32）：电话、短信、基础工具等，以本包里带 LAUNCHER 的
 * Activity（或 activity-alias）出现在应用列表。
 *
 * - 桌面本身和完整设置不是模块（应用列表本来就排除它们，设置另有内置入口）。
 * - 模块和内置“设置”一样在两种模式都公开，不需要放进公开应用白名单。
 * - 从应用列表可以启动；其他本包组件仍按原版规则阻止启动自身。
 */
object SelfModules {
    /** 应用列表不展示、也不能当模块启动的本包 LAUNCHER Activity。 */
    val HOST_ACTIVITIES: Set<String> = setOf(
        "com.flue.launcher.LauncherActivity",
        "com.flue.launcher.SettingsActivity",
    )

    fun isModule(packageName: String, activityName: String, ownPackage: String): Boolean =
        packageName == ownPackage && activityName.isNotBlank() && activityName !in HOST_ACTIVITIES

    /** 应用列表条目是不是内置模块（快捷方式、文件夹、内置设置入口都不是）。 */
    fun isModule(app: com.flue.launcher.data.model.AppInfo, ownPackage: String): Boolean =
        app.itemType == com.flue.launcher.data.model.AppListItemType.APP &&
            !app.isBuiltInSettingsEntry &&
            isModule(app.packageName, app.activityName, ownPackage)
}
