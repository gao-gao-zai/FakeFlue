package com.flue.launcher.surface.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.flue.launcher.surface.SurfaceRuntime

/**
 * ADB 一键通过教程（R22），Debug／Release 都保留：
 *
 * ```
 * adb shell am broadcast --user current --include-stopped-packages \
 *   -a com.gaozai.surface.action.SKIP_TUTORIAL \
 *   -n com.gaozai.surface/com.flue.launcher.surface.entry.AdbTutorialSkipReceiver
 * ```
 *
 * 清单要求发送方持有 `android.permission.DUMP`（只有 shell、root 和系统有），普通第三方应用发不进来；
 * 系统能告诉我们发送方 UID 时（Android 14+ 且发送方共享了身份）再核对一次。
 * 只写入当前版本的教程成绩：不设置密码、不授予 Root、不改默认角色、不跳过基础配置，
 * 也不把任何页面拉到前台；前台正在进行的教程会自己按原请求继续。
 */
class AdbTutorialSkipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbTutorialSkip.ACTION) return
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid else -1
        if (!AdbTutorialSkip.callerAllowed(uid)) {
            Log.w(TAG, "rejected tutorial skip from uid $uid")
            return
        }
        SurfaceRuntime.get(context).recordTutorialPassed()
        Log.i(TAG, "tutorial marked passed via adb")
    }

    private companion object {
        const val TAG = "SurfaceTutorial"
    }
}

object AdbTutorialSkip {
    const val ACTION = "com.gaozai.surface.action.SKIP_TUTORIAL"

    private const val ROOT_UID = 0
    private const val SHELL_UID = 2000

    /** 发送方 UID 未知时只靠清单里的权限把关；已知时只接受 shell 和 root。 */
    fun callerAllowed(uid: Int): Boolean = uid < 0 || uid == ROOT_UID || uid == SHELL_UID
}
