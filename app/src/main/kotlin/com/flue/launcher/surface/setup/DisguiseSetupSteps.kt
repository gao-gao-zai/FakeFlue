package com.flue.launcher.surface.setup

/**
 * 伪装专属配置的步骤（R19）。按这个顺序引导；已完成的步骤记在设备级状态里，
 * 再次进入从第一个未完成的步骤继续（R18）。
 */
enum class DisguiseSetupStep {
    SAFE_AREA,
    DPI,
    PERMISSIONS,
    WATCH_FACE,
    PUBLIC_APPS,
    SECRET,
    EXIT_TARGET,
    ;

    companion object {
        fun parse(stored: Set<String>?): Set<DisguiseSetupStep> =
            stored.orEmpty().mapNotNullTo(mutableSetOf()) { name -> entries.firstOrNull { it.name == name } }

        /** 第一个未完成的步骤；全部完成为 null。 */
        fun firstPending(done: Set<DisguiseSetupStep>): DisguiseSetupStep? = entries.firstOrNull { it !in done }
    }
}

/**
 * 基础配置是否完成：每一步都确认过，并且隐藏密码真的可读。
 * 密码在步骤记录之外再查一次：标记完成之后密码被清掉或读不出来，也不能进入伪装。
 */
object DisguiseSetupRules {
    fun baseConfigured(done: Set<DisguiseSetupStep>, secretReadable: Boolean): Boolean =
        secretReadable && DisguiseSetupStep.entries.all { it in done }
}
