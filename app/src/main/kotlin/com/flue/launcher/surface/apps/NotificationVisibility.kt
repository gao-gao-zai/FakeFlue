package com.flue.launcher.surface.apps

/**
 * 伪装模式的通知展示规则（R11）。
 *
 * 公开应用的通知总是展示；未公开应用默认隐藏，可按应用单独放行（包名存于随模式键 [KEY]）。
 * 放行只影响展示：打开未公开应用的通知要先静默验证（P3），在此之前留在通知中心。
 */
object NotificationVisibility {
    const val KEY = "notification_allowed_apps"

    /** [publicApps] 为 null 表示不限制（完整模式）。 */
    fun isVisible(
        packageName: String?,
        publicApps: Set<String>?,
        allowedPackages: Set<String>?,
        ownPackage: String,
    ): Boolean {
        if (PublicAppRules.isPackagePublic(packageName, publicApps, ownPackage)) return true
        return !packageName.isNullOrBlank() && allowedPackages.orEmpty().contains(packageName)
    }

    /** 点通知或通知按钮时能否直接打开：只有公开应用可以，放行展示的未公开应用要先验证。 */
    fun canOpenDirectly(packageName: String?, publicApps: Set<String>?, ownPackage: String): Boolean =
        PublicAppRules.isPackagePublic(packageName, publicApps, ownPackage)
}
