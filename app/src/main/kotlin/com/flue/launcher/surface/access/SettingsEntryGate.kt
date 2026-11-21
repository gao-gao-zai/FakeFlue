package com.flue.launcher.surface.access

import com.flue.launcher.surface.mode.ConfigScope

/**
 * 完整设置页（SettingsActivity）的进入检查（R08、R26）。
 *
 * SettingsActivity 是导出的，还有一个静态快捷方式和本应用多处入口。伪装模式下只要没有本次管理会话，
 * 就只放行“表盘长按 → 表盘设置”这一条（直达表盘页、返回即回表盘）；其余任何进入方式——外部 Intent、
 * 静态快捷方式、崩溃页、旧返回栈——都转到公开设置。
 */
object SettingsEntryGate {
    enum class Decision { ALLOW, REDIRECT_PUBLIC }

    /** 不需要管理会话的唯一目的地，对应 `SETTINGS_DESTINATION_WATCH_FACES`。 */
    const val WATCH_FACES_DESTINATION = "watch_faces"

    fun decide(
        scope: ConfigScope,
        inManagement: Boolean,
        destination: String?,
        returnToFaceOnBack: Boolean,
    ): Decision = when {
        scope != ConfigScope.DISGUISE -> Decision.ALLOW
        inManagement -> Decision.ALLOW
        destination == WATCH_FACES_DESTINATION && returnToFaceOnBack -> Decision.ALLOW
        else -> Decision.REDIRECT_PUBLIC
    }
}
