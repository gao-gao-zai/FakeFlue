package com.flue.launcher.surface.apps

/**
 * 伪装模式的公开应用（R10、R11）。
 *
 * 白名单存于随模式设置键 [KEY]（伪装模式下实际落在 `fflue.disguise::public_apps`），
 * 内容是应用列表条目的 componentKey（`包名/Activity`、快捷方式键等），逗号分隔。
 * 完整模式不使用白名单。未选中的应用、新安装的应用默认都不公开。
 */
object PublicAppRules {
    const val KEY = "public_apps"

    fun parse(raw: String?): Set<String> =
        raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

    fun serialize(entries: Set<String>): String = entries.filter(String::isNotBlank).sorted().joinToString(",")

    /**
     * 应用列表条目是否公开：内置入口（内置设置、R31 内置工具）总是公开；否则 componentKey 或包名在白名单里。
     */
    fun isPublic(componentKey: String, packageName: String, builtIn: Boolean, whitelist: Set<String>): Boolean =
        builtIn || com.flue.launcher.surface.tools.SurfaceToolCatalog.isToolComponentKey(componentKey) ||
            componentKey in whitelist || packageName in whitelist

    /**
     * 按包名判断是否允许展示或启动（通知、表盘点击动作等只知道包名的入口）。
     * [whitelist] 为 null 表示不限制。本应用自身总是允许。
     */
    fun isPackagePublic(packageName: String?, whitelist: Set<String>?, ownPackage: String): Boolean {
        if (whitelist == null) return true
        if (packageName.isNullOrBlank()) return false
        if (packageName == ownPackage) return true
        return whitelist.any { it == packageName || it.startsWith("$packageName/") }
    }
}
