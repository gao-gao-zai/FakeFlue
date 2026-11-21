package com.flue.launcher.surface.comm

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.theme.LauncherTheme

/** 短信页面的位置（可保存，旋转／重建后回到原处）。 */
private sealed interface MsgPage {
    data object List : MsgPage
    data class Thread(val threadId: Long, val address: String) : MsgPage
    data class Compose(val address: String, val body: String) : MsgPage
}

/**
 * 内置“短信”（R32）：会话列表、阅读、编辑、发送。文字用系统现有输入法，不另做键盘。
 * 也处理 SENDTO `sms:`／`smsto:`／`mms:`／`mmsto:`（成为默认短信应用的必要入口）。
 */
class MessagesActivity : CommActivity() {

    private val start = mutableStateOf<MsgPage>(MsgPage.List)
    private val startRevision = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start.value = pageFrom(intent)
        setCommContent { MessagesScreen(start.value, startRevision.intValue, onExit = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start.value = pageFrom(intent)
        startRevision.intValue++
    }

    private fun pageFrom(intent: Intent?): MsgPage {
        val thread = intent?.getLongExtra(EXTRA_THREAD, -1L) ?: -1L
        val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        if (thread >= 0) return MsgPage.Thread(thread, address)
        val data = intent?.data
        if (data != null && data.scheme in setOf("sms", "smsto", "mms", "mmsto")) {
            val to = SmsRules.recipients(data.schemeSpecificPart).joinToString(",")
            val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            return MsgPage.Compose(to, body)
        }
        return MsgPage.List
    }

    companion object {
        private const val EXTRA_THREAD = "com.flue.launcher.extra.SMS_THREAD"
        private const val EXTRA_ADDRESS = "com.flue.launcher.extra.SMS_ADDRESS"

        fun threadIntent(context: Context, threadId: Long, address: String): Intent =
            Intent(context, MessagesActivity::class.java)
                .putExtra(EXTRA_THREAD, threadId)
                .putExtra(EXTRA_ADDRESS, address)
    }
}

/** 短信库任何变化（含系统写入）时加一。 */
@Composable
private fun rememberSmsVersion(): Int {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val events by SmsEvents.version.collectAsStateWithLifecycle()
    val sends by SmsSender.states.collectAsStateWithLifecycle()
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                version++
            }
        }
        runCatching { context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    return version + events + sends.size * 1_000 + sends.values.count { it != SmsSendState.SENDING }
}

@Composable
private fun MessagesScreen(start: MsgPage, revision: Int, onExit: () -> Unit) {
    var page by remember(start, revision) { mutableStateOf(start) }
    when (val p = page) {
        MsgPage.List -> ThreadListPage(
            onOpen = { page = MsgPage.Thread(it.threadId, it.address) },
            onCompose = { page = MsgPage.Compose("", "") },
            onBack = onExit,
        )
        is MsgPage.Thread -> {
            BackHandler { page = MsgPage.List }
            ThreadPage(p.threadId, p.address, onBack = { page = MsgPage.List })
        }
        is MsgPage.Compose -> {
            BackHandler { page = MsgPage.List }
            ComposePage(
                initialAddress = p.address,
                initialBody = p.body,
                onSent = { threadId, address ->
                    page = if (threadId != null) MsgPage.Thread(threadId, address) else MsgPage.List
                },
                onBack = { page = MsgPage.List },
            )
        }
    }
}

@Composable
private fun ThreadListPage(onOpen: (SmsThread) -> Unit, onCompose: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val resume = rememberResumeTick()
    val version = rememberSmsVersion()
    val disguise = remember(resume) { CommBlockText.disguise(context) }
    val env = remember(resume) { CommSystem.environment(context) }
    val threads = remember(resume, version) { SmsStore.threads(context) }
    FfluePageScaffold(stateKey = "comm_sms_list", title = stringResource(R.string.surface_comm_sms_title), onBack = onBack) {
        item(key = "compose") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.surface_comm_sms_new),
                    onClick = onCompose,
                    last = true,
                )
            }
        }
        val notes = listOfNotNull(
            CommCapability.smsReceive(env)?.let { CommBlockText.forSmsReceive(it, disguise) },
            CommCapability.smsSend(env)?.let { CommBlockText.forSmsSend(it, disguise) },
        ).distinct()
        if (notes.isNotEmpty()) {
            item(key = "notes") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    notes.forEach { Text(stringResource(it), color = style.secondaryTextColor, fontSize = 11.sp) }
                }
            }
        }
        item(key = "threads") {
            AgentSettingsGroup(stringResource(R.string.surface_comm_sms_threads)) {
                val block = CommCapability.smsRead(env)
                when {
                    block != null -> AgentSettingsEntry(title = stringResource(CommBlockText.forSmsRead(block, disguise)), last = true)
                    threads.isEmpty() -> AgentSettingsEntry(title = stringResource(R.string.surface_comm_sms_empty), last = true)
                    else -> threads.forEachIndexed { index, t ->
                        val time = DateUtils.getRelativeTimeSpanString(t.date).toString()
                        AgentSettingsEntry(
                            title = if (t.unread > 0) {
                                stringResource(R.string.surface_comm_sms_unread_fmt, t.address, t.unread)
                            } else {
                                t.address.ifEmpty { stringResource(R.string.surface_comm_unknown_number) }
                            },
                            subtitle = "$time · ${t.snippet.take(40)}",
                            onClick = { onOpen(t) },
                            last = index == threads.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadPage(threadId: Long, address: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val resume = rememberResumeTick()
    val version = rememberSmsVersion()
    val env = remember(resume) { CommSystem.environment(context) }
    val rows = remember(threadId, resume, version) { SmsStore.thread(context, threadId) }
    val title = address.ifEmpty { rows.firstOrNull { it.address.isNotBlank() }?.address.orEmpty() }
    var draft by rememberSaveable(threadId) { mutableStateOf("") }
    var scrollTarget by remember(threadId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(threadId, rows.size) {
        // 打开会话即视为已读（只有默认短信应用能写）。
        if (CommCapability.writesSmsProvider(env)) SmsStore.markThreadRead(context, threadId)
        CommNotifications.cancelThread(context, threadId)
        scrollTarget = rows.size + 1
    }
    FfluePageScaffold(
        stateKey = "comm_sms_thread_$threadId",
        title = title.ifEmpty { stringResource(R.string.surface_comm_unknown_number) },
        onBack = onBack,
        scrollTo = scrollTarget,
        onScrolled = { scrollTarget = null },
    ) {
        if (rows.isEmpty()) {
            item(key = "empty") { Text(stringResource(R.string.surface_comm_sms_thread_empty), color = style.secondaryTextColor, fontSize = 12.sp) }
        }
        rows.forEach { row -> item(key = "m${row.id}") { Bubble(row) } }
        item(key = "input") {
            SendBox(
                to = title,
                draft = draft,
                onDraft = { draft = it },
                env = env,
                onSubmitted = { draft = "" },
            )
        }
    }
}

@Composable
private fun Bubble(row: SmsRow) {
    val style = LauncherTheme.style
    val status = when (row.type) {
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> R.string.surface_comm_sms_sending
        Telephony.Sms.MESSAGE_TYPE_FAILED -> R.string.surface_comm_sms_failed
        Telephony.Sms.MESSAGE_TYPE_SENT -> R.string.surface_comm_sms_sent
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> R.string.surface_comm_sms_draft
        else -> null
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (row.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 200.dp)
                .clip(style.compactShape)
                .background(if (row.outgoing) style.accentColor.copy(alpha = 0.28f) else style.cardColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(row.body, color = style.titleColor, fontSize = 13.sp)
            val time = DateUtils.getRelativeTimeSpanString(row.date).toString()
            Text(
                if (status != null) "$time · ${stringResource(status)}" else time,
                color = if (row.type == Telephony.Sms.MESSAGE_TYPE_FAILED) CommRed else style.secondaryTextColor,
                fontSize = 9.sp,
            )
        }
    }
}

/** 输入与发送。[to] 可以是逗号分隔的多个号码。 */
@Composable
private fun SendBox(
    to: String,
    draft: String,
    onDraft: (String) -> Unit,
    env: CommEnvironment,
    onSubmitted: (SmsSubmit.Submitted) -> Unit,
) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val disguise = remember { CommBlockText.disguise(context) }
    val sends by SmsSender.states.collectAsStateWithLifecycle()
    var lastKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    val block = CommCapability.smsSend(env)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlueTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 5,
            placeholder = stringResource(R.string.surface_comm_sms_input_hint),
            enabled = block == null,
            supportingText = SmsRules.estimateSegments(draft).takeIf { it > 1 }?.let {
                stringResource(R.string.surface_comm_sms_segments_fmt, it)
            },
        )
        // 本次发出的结果如实显示：只有每段回执都成功才是“已发送”。
        val states = lastKeys.mapNotNull { sends[it] }
        if (states.isNotEmpty()) {
            val text = when {
                states.any { it == SmsSendState.FAILED } -> R.string.surface_comm_sms_failed
                states.all { it == SmsSendState.SENT } -> R.string.surface_comm_sms_sent
                else -> R.string.surface_comm_sms_sending
            }
            Text(stringResource(text), color = if (text == R.string.surface_comm_sms_failed) CommRed else style.secondaryTextColor, fontSize = 11.sp)
        }
        if (block != null) {
            Text(stringResource(CommBlockText.forSmsSend(block, disguise)), color = style.secondaryTextColor, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CommRoundButton(
                label = stringResource(R.string.surface_comm_sms_send),
                icon = Icons.AutoMirrored.Filled.Send,
                color = style.accentColor,
                size = 40.dp,
                enabled = block == null && draft.isNotBlank() && to.isNotBlank(),
                onClick = {
                    val recipients = SmsRules.recipients(to)
                    if (recipients.isEmpty()) {
                        Toast.makeText(context, R.string.surface_comm_sms_no_recipient, Toast.LENGTH_SHORT).show()
                        return@CommRoundButton
                    }
                    val results = recipients.map { SmsSender.send(context, it, draft) }
                    val submitted = results.filterIsInstance<SmsSubmit.Submitted>()
                    lastKeys = submitted.map { it.key }
                    if (submitted.size == results.size) {
                        onSubmitted(submitted.first())
                    } else {
                        Toast.makeText(context, R.string.surface_comm_sms_submit_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

@Composable
private fun ComposePage(
    initialAddress: String,
    initialBody: String,
    onSent: (threadId: Long?, address: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val env = remember(resume) { CommSystem.environment(context) }
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var body by rememberSaveable { mutableStateOf(initialBody) }
    FfluePageScaffold(stateKey = "comm_sms_compose", title = stringResource(R.string.surface_comm_sms_new), onBack = onBack) {
        item(key = "to") {
            FlueTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.surface_comm_sms_to),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        }
        item(key = "body") {
            SendBox(
                to = address,
                draft = body,
                onDraft = { body = it },
                env = env,
                onSubmitted = {
                    body = ""
                    val first = SmsRules.recipients(address).firstOrNull().orEmpty()
                    onSent(SmsStore.threadIdFor(context, first), first)
                },
            )
        }
    }
}
