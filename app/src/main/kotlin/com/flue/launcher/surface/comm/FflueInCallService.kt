package com.flue.launcher.surface.comm

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 默认电话应用的通话服务（R32）。系统只在本应用是默认电话时绑定它。
 *
 * - 新来电／去电立即打开 [InCallActivity]（系统绑定的 InCallService 允许从后台打开界面），
 *   同时发全屏通知作为兜底；响铃由系统负责（未声明自行响铃）。
 * - 离开通话界面不挂断：通话中保持一条可回到通话的通知。
 */
class FflueInCallService : InCallService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        CallRegistry.attach(this)
        scope.launch {
            CallRegistry.snapshots.collect { CommNotifications.updateCall(this@FflueInCallService, it) }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallRegistry.add(call)
        openUi()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallRegistry.remove(call)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        CallRegistry.onAudioState(audioState)
    }

    private fun openUi() {
        runCatching {
            startActivity(InCallActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "open in-call ui failed; full-screen notification remains", it) }
    }

    override fun onDestroy() {
        CallRegistry.detach(this)
        CommNotifications.updateCall(this, emptyList())
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FflueInCall"
    }
}
