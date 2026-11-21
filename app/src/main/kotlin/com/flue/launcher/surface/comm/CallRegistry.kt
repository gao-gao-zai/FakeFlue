package com.flue.launcher.surface.comm

import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** 界面用的通话快照。 */
data class CallSnapshot(
    val id: Int,
    val number: String?,
    val name: String?,
    val phase: CallPhase,
    val incoming: Boolean,
    val connectTimeMillis: Long,
    val canHold: Boolean,
) {
    val actions: CallActions get() = CallRules.actionsOf(phase, canHold)
}

data class CallAudio(val muted: Boolean, val speaker: Boolean, val speakerSupported: Boolean)

/**
 * 系统绑定本应用为默认电话时，[FflueInCallService] 把通话交到这里；界面、通知、导航放行
 * （R29 通话放行）和桌面锁豁免都看这里的事实。只在内存里：进程重建后由系统重新绑定并补发通话。
 */
object CallRegistry {
    private val lock = Any()
    private val calls = LinkedHashMap<Call, Int>()
    private var nextId = 1
    private var serviceRef: WeakReference<InCallService>? = null

    private val _calls = MutableStateFlow<List<CallSnapshot>>(emptyList())
    val snapshots: StateFlow<List<CallSnapshot>> = _calls.asStateFlow()

    private val _hasLiveCall = MutableStateFlow(false)

    /** 有进行中的通话或响铃的来电。 */
    val hasLiveCall: StateFlow<Boolean> = _hasLiveCall.asStateFlow()

    private val _audio = MutableStateFlow<CallAudio?>(null)
    val audio: StateFlow<CallAudio?> = _audio.asStateFlow()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    fun attach(service: InCallService) {
        serviceRef = WeakReference(service)
    }

    fun detach(service: InCallService) {
        if (serviceRef?.get() === service) serviceRef = null
        synchronized(lock) {
            calls.keys.forEach { it.unregisterCallback(callback) }
            calls.clear()
        }
        _audio.value = null
        publish()
    }

    fun add(call: Call): Int {
        val id = synchronized(lock) {
            calls.getOrPut(call) { nextId++ }
        }
        call.registerCallback(callback)
        publish()
        return id
    }

    fun remove(call: Call) {
        synchronized(lock) { calls.remove(call) }
        call.unregisterCallback(callback)
        publish()
    }

    @Suppress("DEPRECATION")
    fun onAudioState(state: CallAudioState?) {
        _audio.value = state?.let {
            CallAudio(
                muted = it.isMuted,
                speaker = it.route == CallAudioState.ROUTE_SPEAKER,
                speakerSupported = it.supportedRouteMask and CallAudioState.ROUTE_SPEAKER != 0,
            )
        }
    }

    private fun find(id: Int): Call? = synchronized(lock) { calls.entries.firstOrNull { it.value == id }?.key }

    fun answer(id: Int) {
        find(id)?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject(id: Int) {
        find(id)?.reject(false, null)
    }

    fun hangUp(id: Int) {
        find(id)?.disconnect()
    }

    fun hold(id: Int, on: Boolean) {
        val call = find(id) ?: return
        if (on) call.hold() else call.unhold()
    }

    fun dtmf(id: Int, c: Char) {
        if (!CallRules.isDtmf(c)) return
        val call = find(id) ?: return
        call.playDtmfTone(c)
        call.stopDtmfTone()
    }

    fun setMuted(muted: Boolean) {
        serviceRef?.get()?.setMuted(muted)
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        serviceRef?.get()?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    @Suppress("DEPRECATION")
    private fun snapshot(call: Call, id: Int): CallSnapshot {
        val details = call.details
        val state = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) details.state else call.state
        val handle: Uri? = details?.handle
        val incoming = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            details.callDirection == Call.Details.DIRECTION_INCOMING
        } else {
            state == Call.STATE_RINGING
        }
        return CallSnapshot(
            id = id,
            number = handle?.schemeSpecificPart,
            name = details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: details?.contactDisplayNameCompat(),
            phase = CallRules.phaseOf(state),
            incoming = incoming,
            connectTimeMillis = details?.connectTimeMillis ?: 0L,
            canHold = details?.can(Call.Details.CAPABILITY_HOLD) == true,
        )
    }

    private fun Call.Details.contactDisplayNameCompat(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) contactDisplayName?.takeIf { it.isNotBlank() } else null

    private fun publish() {
        val list = synchronized(lock) { calls.entries.map { snapshot(it.key, it.value) } }
        _calls.value = list
        _hasLiveCall.value = list.any { it.phase.live }
    }
}
