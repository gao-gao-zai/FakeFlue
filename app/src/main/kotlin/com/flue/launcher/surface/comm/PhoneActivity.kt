package com.flue.launcher.surface.comm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * 内置“电话”（R32）：拨号盘、通话记录、回到通话。完整和伪装模式都可用；
 * 也处理系统的 DIAL／`tel:`（成为默认电话应用的必要入口）。
 */
class PhoneActivity : CommActivity() {

    private val initialNumber = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) initialNumber.value = numberFrom(intent)
        setCommContent { PhoneScreen(initialNumber.value, onBack = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        numberFrom(intent).takeIf { it.isNotEmpty() }?.let { initialNumber.value = it }
    }

    private fun numberFrom(intent: Intent?): String {
        val data = intent?.data ?: return ""
        if (data.scheme != "tel") return ""
        return DialRules.normalize(data.schemeSpecificPart.orEmpty())
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, PhoneActivity::class.java)
    }
}

@Composable
private fun PhoneScreen(initialNumber: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val resumeTick = rememberResumeTick()
    val disguise = remember(resumeTick) { CommBlockText.disguise(context) }
    val env = remember(resumeTick) { CommSystem.environment(context) }
    val calls by CallRegistry.snapshots.collectAsStateWithLifecycle()
    val liveCall = calls.any { it.phase.live }
    var number by rememberSaveable(initialNumber) { mutableStateOf(initialNumber) }
    val log = remember(resumeTick) { CallLogReader.recent(context) }

    fun dial() {
        val message = when (CommDial.dial(context, number)) {
            DialOutcome.PLACED -> null
            DialOutcome.HANDED_TO_SYSTEM -> R.string.surface_comm_dial_handed
            DialOutcome.INVALID -> R.string.surface_comm_dial_invalid
            DialOutcome.FAILED -> R.string.surface_comm_dial_failed
        }
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    FfluePageScaffold(
        stateKey = "comm_phone",
        title = stringResource(R.string.surface_comm_phone_title),
        onBack = onBack,
    ) {
        if (liveCall) {
            item(key = "return_call") {
                AgentSettingsGroup {
                    AgentSettingsEntry(
                        title = stringResource(R.string.surface_comm_return_to_call),
                        onClick = { context.startActivity(InCallActivity.intent(context)) },
                        last = true,
                    )
                }
            }
        }
        val notes = listOfNotNull(
            CommCapability.dial(env)?.let { CommBlockText.forDial(it, disguise) },
            CommCapability.inCallUi(env)?.let { CommBlockText.forInCall(it, disguise) },
        ).distinct()
        if (notes.isNotEmpty()) {
            item(key = "notes") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    notes.forEach { Text(stringResource(it), color = style.secondaryTextColor, fontSize = 11.sp) }
                }
            }
        }
        item(key = "number") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    number.ifEmpty { stringResource(R.string.surface_comm_dial_hint) },
                    color = if (number.isEmpty()) style.secondaryTextColor else style.titleColor,
                    fontSize = if (number.length > 11) 16.sp else 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (number.isNotEmpty()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = stringResource(R.string.surface_comm_dial_delete),
                        tint = style.secondaryTextColor,
                        modifier = Modifier
                            .size(28.dp)
                            .combinedClickable(onClick = { number = number.dropLast(1) }, onLongClick = { number = "" })
                            .padding(3.dp),
                    )
                }
            }
        }
        item(key = "pad") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CommDialPad(onKey = { if (number.length < 32) number += it }, onLongZero = { if (number.length < 32) number += "+" })
                CommRoundButton(
                    label = stringResource(R.string.surface_comm_dial_call),
                    icon = Icons.Filled.Call,
                    color = CommGreen,
                    onClick = ::dial,
                    enabled = number.isNotEmpty(),
                    size = 48.dp,
                )
            }
        }
        item(key = "log") {
            AgentSettingsGroup(stringResource(R.string.surface_comm_log_title)) {
                val block = CommCapability.callLog(env)
                when {
                    block != null -> AgentSettingsEntry(title = stringResource(CommBlockText.forCallLog(block, disguise)), last = true)
                    log.isEmpty() -> AgentSettingsEntry(title = stringResource(R.string.surface_comm_log_empty), last = true)
                    else -> log.forEachIndexed { index, entry ->
                        val type = stringResource(CallLogReader.typeLabel(entry.type))
                        val time = DateUtils.getRelativeTimeSpanString(entry.date).toString()
                        AgentSettingsEntry(
                            title = entry.name ?: entry.number.ifEmpty { stringResource(R.string.surface_comm_unknown_number) },
                            subtitle = "$type · $time",
                            onClick = if (entry.number.isNotEmpty()) {
                                { number = DialRules.normalize(entry.number) }
                            } else {
                                null
                            },
                            last = index == log.lastIndex,
                        )
                    }
                }
            }
        }
    }
}
