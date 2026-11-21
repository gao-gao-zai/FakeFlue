package com.flue.launcher.surface.mode

/**
 * R09：伪装模式下在第三方应用里熄屏，默认下次亮屏回表盘，应用留在后台。
 *
 * 管理中可改为继续原应用（随模式键 [KEY]，只在伪装配置里有意义）。真实通话例外：
 * 通话中贴脸熄屏、亮屏后应当还在通话界面。本应用自己的页面在前台时交给桌面自己的熄屏规则。
 */
object ThirdPartyScreenOff {
    const val KEY = "screen_off_keep_app"

    fun shouldReturnToFace(
        scope: ConfigScope,
        keepApp: Boolean,
        ownActivityStarted: Boolean,
        inCall: Boolean,
    ): Boolean = scope == ConfigScope.DISGUISE && !keepApp && !ownActivityStarted && !inCall
}
