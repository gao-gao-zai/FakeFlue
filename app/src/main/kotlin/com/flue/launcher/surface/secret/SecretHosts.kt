package com.flue.launcher.surface.secret

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.drawer.HiddenDrawerActivity

/** 当前保存的密码；没设置或读不出时为 null，此时验证任何输入都失败（R12）。 */
internal fun SurfaceRuntime.readableSecret(): List<Int>? = (secretStore.read() as? SecretRead.Available)?.digits

/**
 * 桌面根部的隐藏抽屉验证层（R14）。控制中心电量按住 2 秒松手后出现在原画面上；
 * 成功只授予本次完整抽屉，失败或取消静默回到原画面。
 */
@Composable
fun HiddenDrawerChallengeHost() {
    val context = LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val active by runtime.hiddenDrawerChallenge.collectAsState()
    if (!active) return
    val secret = remember { runtime.readableSecret() }
    SecretChallengeOverlay(
        secret = secret,
        onSuccess = {
            runtime.clearHiddenDrawerChallenge()
            runtime.grantHiddenDrawer()
            runCatching { context.startActivity(HiddenDrawerActivity.intent(context)) }
        },
        onFailure = runtime::clearHiddenDrawerChallenge,
        onCancel = { runtime.clearHiddenDrawerChallenge() },
    )
}
