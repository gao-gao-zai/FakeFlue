package com.flue.launcher.surface.comm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/**
 * 来电、拨号中与通话中界面（R32）。
 *
 * - 锁屏和熄屏时也显示（showWhenLocked／turnScreenOn）；桌面锁（R33）对它豁免。
 * - 返回只离开界面，不挂断；通话通知和电话页都能回到这里。
 * - 没有进行中的通话时显示“已结束”后自动关闭。
 */
class InCallActivity : LightCommActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleAnswer(intent)
        setLightContent { InCallScreen(onLeave = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnswer(intent)
    }

    private fun handleAnswer(intent: Intent?) {
        if (intent?.action != ACTION_ANSWER) return
        val id = intent.getIntExtra(CallActionReceiver.EXTRA_ID, -1)
        if (id >= 0) CallRegistry.answer(id)
        // 只处理一次，重建时不再接听。
        intent.action = null
    }

    companion object {
        private const val ACTION_ANSWER = "com.flue.launcher.comm.ANSWER"

        fun intent(context: Context): Intent = Intent(context, InCallActivity::class.java)

        fun answerIntent(context: Context, callId: Int): Intent =
            intent(context).setAction(ACTION_ANSWER).putExtra(CallActionReceiver.EXTRA_ID, callId)
    }
}

@Composable
private fun InCallScreen(onLeave: () -> Unit) {
    val style = LauncherTheme.style
    val calls by CallRegistry.snapshots.collectAsStateWithLifecycle()
    val audio by CallRegistry.audio.collectAsStateWithLifecycle()
    val live = calls.filter { it.phase.live }
    var keypad by rememberSaveable { mutableStateOf(false) }
    var dtmfShown by rememberSaveable { mutableStateOf("") }
    BackHandler(onBack = onLeave)

    LaunchedEffect(live.isEmpty()) {
        if (live.isEmpty()) {
            delay(1_200)
            onLeave()
        }
    }

    val system = WindowInsets.safeDrawing.asPaddingValues()
    Box(
        Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            .padding(system)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (live.isEmpty()) {
            Text(stringResource(R.string.surface_comm_call_ended), color = style.secondaryTextColor, fontSize = 14.sp)
            return@Box
        }
        val call = live[CallRules.primaryIndex(live.map { it.phase })]
        val actions = call.actions
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val unknown = stringResource(R.string.surface_comm_unknown_number)
            Text(
                call.name ?: call.number?.takeIf { it.isNotBlank() } ?: unknown,
                color = style.titleColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (call.name != null && !call.number.isNullOrBlank()) {
                Text(call.number, color = style.secondaryTextColor, fontSize = 12.sp, maxLines = 1)
            }
            CallStatusLine(call)
            if (live.size > 1) {
                Text(
                    stringResource(R.string.surface_comm_call_others_fmt, live.size - 1),
                    color = style.secondaryTextColor,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (keypad && actions.keypad) {
                Text(dtmfShown.takeLast(14), color = style.titleColor, fontSize = 16.sp, maxLines = 1)
                CommDialPad(onKey = { c ->
                    CallRegistry.dtmf(call.id, c)
                    dtmfShown += c
                })
            } else if (actions.mute || actions.speaker || actions.keypad || actions.hold || actions.unhold) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (actions.mute) {
                        CommToggle(stringResource(R.string.surface_comm_call_mute), checked = audio?.muted == true) {
                            CallRegistry.setMuted(audio?.muted != true)
                        }
                    }
                    if (actions.speaker) {
                        CommToggle(
                            stringResource(R.string.surface_comm_call_speaker),
                            checked = audio?.speaker == true,
                            enabled = audio?.speakerSupported != false,
                        ) { CallRegistry.setSpeaker(audio?.speaker != true) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (actions.keypad) {
                        CommToggle(stringResource(R.string.surface_comm_call_keypad), checked = false) { keypad = true }
                    }
                    if (actions.hold) {
                        CommToggle(stringResource(R.string.surface_comm_call_hold), checked = false) { CallRegistry.hold(call.id, true) }
                    }
                    if (actions.unhold) {
                        CommToggle(stringResource(R.string.surface_comm_call_unhold), checked = true) { CallRegistry.hold(call.id, false) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (actions.reject) {
                    CommRoundButton(stringResource(R.string.surface_comm_call_reject), Icons.Filled.CallEnd, CommRed, onClick = { CallRegistry.reject(call.id) })
                }
                if (actions.answer) {
                    CommRoundButton(stringResource(R.string.surface_comm_call_answer), Icons.Filled.Call, CommGreen, onClick = { CallRegistry.answer(call.id) })
                }
                if (actions.hangUp) {
                    CommRoundButton(stringResource(R.string.surface_comm_call_hang_up), Icons.Filled.CallEnd, CommRed, onClick = { CallRegistry.hangUp(call.id) })
                }
            }
            if (keypad && actions.keypad) {
                CommToggle(stringResource(R.string.surface_comm_call_keypad_close), checked = false) { keypad = false }
            }
            Text(
                stringResource(R.string.surface_comm_call_back_hint),
                color = style.secondaryTextColor,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CallStatusLine(call: CallSnapshot) {
    val style = LauncherTheme.style
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase, call.connectTimeMillis) {
        while (call.phase == CallPhase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val text = when (call.phase) {
        CallPhase.RINGING -> stringResource(R.string.surface_comm_call_incoming)
        CallPhase.CONNECTING -> stringResource(R.string.surface_comm_call_connecting)
        CallPhase.DIALING -> stringResource(R.string.surface_comm_call_dialing)
        CallPhase.ACTIVE -> if (call.connectTimeMillis > 0) CallRules.formatDuration(now - call.connectTimeMillis)
        else stringResource(R.string.surface_comm_call_ongoing)
        CallPhase.HOLDING -> stringResource(R.string.surface_comm_call_holding)
        CallPhase.DISCONNECTING, CallPhase.DISCONNECTED -> stringResource(R.string.surface_comm_call_ended)
    }
    Text(text, color = if (call.phase == CallPhase.ACTIVE) CommGreen else style.secondaryTextColor, fontSize = 13.sp)
}
