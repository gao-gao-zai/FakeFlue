package com.flue.launcher.surface.comm

/**
 * 用 Root 把角色交还给指定应用（R32）。只在用户已在授权页确认过 Root 时使用；
 * 命令返回不算成功，仍要读回系统实际持有者。
 */
object RootRoleCommands {
    private val packagePattern = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    fun isSafePackage(name: String): Boolean = packagePattern.matches(name)

    /**
     * @return 要依次执行的 shell 命令；包名不合法时为空（不拼接进命令）。
     */
    fun commands(role: CommRole, packageName: String, sdkInt: Int): List<String> {
        if (!isSafePackage(packageName)) return emptyList()
        return if (sdkInt >= 29) {
            // 独占角色：加入新持有者时系统会移除旧持有者（本应用）。
            listOf("cmd role add-role-holder --user 0 ${role.roleName} $packageName")
        } else {
            when (role) {
                CommRole.DIALER -> listOf("telecom set-default-dialer $packageName")
                CommRole.SMS -> listOf("settings put secure sms_default_application $packageName")
            }
        }
    }
}

/**
 * 桌面锁（R33）上锁时会把本应用的其他页面带回桌面；真实来电和通话界面不能被它拦（R32）。
 */
object CommLockExemption {
    val EXEMPT_ACTIVITIES: Set<String> = setOf(
        "com.flue.launcher.surface.comm.InCallActivity",
    )

    fun isExempt(activityClassName: String): Boolean = activityClassName in EXEMPT_ACTIVITIES
}
