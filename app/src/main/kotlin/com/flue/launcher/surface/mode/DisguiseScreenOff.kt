package com.flue.launcher.surface.mode

import com.flue.launcher.ui.navigation.ScreenState

/**
 * 伪装模式熄屏后回到表盘：下次亮屏看到的是表盘，而不是熄屏前停留的抽屉、侧屏或通知。
 *
 * [ScreenState.App] 是应用启动／返回会话的一部分，交给启动会话自己收尾，这里不动。
 */
object DisguiseScreenOff {
    fun targetState(scope: ConfigScope, current: ScreenState): ScreenState? = when {
        scope != ConfigScope.DISGUISE -> null
        current == ScreenState.Face || current == ScreenState.App -> null
        else -> ScreenState.Face
    }
}
