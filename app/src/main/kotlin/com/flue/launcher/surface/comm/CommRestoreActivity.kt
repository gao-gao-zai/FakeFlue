package com.flue.launcher.surface.comm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.surface.ui.reportModeSwitch
import com.flue.launcher.surface.ui.rememberResumeTick
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

/**
 * 退出到外部桌面前把本应用接管的电话、短信角色交还给原应用（R07、R32）。
 *
 * 所有退出外部的入口（完整设置的桌面模式页、伪装管理菜单）在本应用仍持有角色或有未完成的进度时，
 * 先打开这里；全部角色处理完才调用唯一的模式切换入口切到外部桌面。
 *
 * - 恢复结果靠读回系统实际持有者判定；取消、角色为空、只撤销了本应用都不算恢复成功。
 * - 通话中不切换角色，由用户在通话结束后继续或取消，不自动挂断。
 * - 进度持久保存：系统角色变化结束进程后重建，只读回、不自动再次申请；
 *   伪装模式下重建不带旧管理资格，本页直接关闭，进度留到下次经管理菜单退出时继续。
 */
class CommRestoreActivity : CommActivity() {

    private fun allowed(): Boolean {
        val runtime = SurfaceRuntime.get(this)
        return runtime.controller.mode.value != SurfaceMode.DISGUISE || runtime.managementSession.value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!allowed()) {
            finish()
            return
        }
        setCommContent {
            RestoreScreen(
                onExited = {
                    setResult(Activity.RESULT_OK)
                    finish()
                },
                onCancel = {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 管理会话在熄屏、来电、模式变化时结束（R15、R16）：不能凭旧资格继续退出。
        if (!allowed()) finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, CommRestoreActivity::class.java)
    }
}

/** 退出外部的公共判断，供各入口调用。 */
object CommExit {
    /**
     * 退出外部前是否要先打开 [CommRestoreActivity]。外部桌面目标本身不可用时返回 false，
     * 让模式控制器直接报告目标问题（先验证目标，再动通信角色，R07）。
     */
    fun needsRestoreFirst(context: Context): Boolean {
        val runtime = SurfaceRuntime.get(context)
        if (runtime.controller.externalTargetProblem() != null) return false
        return CommSystem.restoreNeeded(context)
    }
}

internal fun CommRole.labelRes(): Int = when (this) {
    CommRole.DIALER -> R.string.surface_comm_role_dialer
    CommRole.SMS -> R.string.surface_comm_role_sms
}

@Composable
private fun RestoreScreen(onExited: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val runtime = remember { SurfaceRuntime.get(context) }
    val flow = remember { CommSystem.restoreFlow(context) }
    val ledger = remember { CommSystem.ledger(context) }
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<CommRole?>(null) }
    val facts = remember(resumeTick, revision) { CommSystem.restoreFacts(context) }
    remember { flow.begin(CommSystem.restoreFacts(context)) }
    val step = remember(facts) { flow.step(facts) }
    val root = remember(resumeTick) { CommSystem.rootConfirmed(context) }
    val own = context.packageName

    fun refresh() {
        revision++
    }

    fun cancel() {
        flow.cancel()
        Toast.makeText(context, R.string.surface_comm_restore_cancelled, Toast.LENGTH_SHORT).show()
        onCancel()
    }

    fun restore(role: CommRole, target: String) {
        if (CommSystem.inCall(context)) {
            refresh()
            return
        }
        // 先落盘再动系统：进程可能因角色变化被结束。
        flow.launched(role, target)
        if (root) {
            busy = true
            scope.launch {
                CommSystem.restoreWithRoot(context, role, target)
                busy = false
                refresh()
            }
        } else {
            runtime.systemControl.beginHandoff()
            if (!CommSystem.openRestore(context, role, target)) {
                Toast.makeText(context, R.string.surface_comm_restore_open_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    LaunchedEffect(step) {
        val finished = step as? RestoreStep.Finished ?: return@LaunchedEffect
        val result = runtime.controller.switchTo(SurfaceMode.EXTERNAL)
        context.reportModeSwitch(result, SurfaceMode.EXTERNAL)
        flow.complete()
        if (finished.skipped.isNotEmpty()) {
            Toast.makeText(context, R.string.surface_comm_restore_kept_notice, Toast.LENGTH_LONG).show()
        }
        if (result == ModeSwitchResult.Success || result == ModeSwitchResult.ExitedButDefaultHomeIsSelf ||
            result == ModeSwitchResult.AlreadyInMode
        ) {
            onExited()
        } else {
            onCancel()
        }
    }

    val roleName = { role: CommRole -> context.getString(role.labelRes()) }
    val label = { pkg: String? -> CommSystem.appLabel(context, pkg) }

    FfluePageScaffold(
        stateKey = "comm_restore",
        title = stringResource(R.string.surface_comm_restore_title),
        subtitle = stringResource(R.string.surface_comm_restore_subtitle),
        onBack = ::cancel,
    ) {
        item(key = "summary") {
            val p = flow.progress
            AgentSettingsGroup(stringResource(R.string.surface_comm_restore_roles)) {
                val roles = CommRole.entries.filter { it in p.roles }
                if (roles.isEmpty()) AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_none), last = true)
                roles.forEachIndexed { index, role ->
                    val holder = facts.roles[role]?.holder
                    AgentSettingsEntry(
                        title = roleName(role),
                        subtitle = stringResource(
                            when {
                                role in p.done -> R.string.surface_comm_restore_state_done
                                role in p.skipped -> R.string.surface_comm_restore_state_skipped
                                else -> R.string.surface_comm_restore_state_pending
                            },
                        ) + " · " + stringResource(R.string.surface_comm_restore_current_fmt, holder?.let(label) ?: stringResource(R.string.surface_comm_restore_holder_none)),
                        last = index == roles.lastIndex,
                    )
                }
            }
        }

        val choosing = picking
        if (busy) {
            item(key = "busy") { Text(stringResource(R.string.surface_comm_restore_busy), color = style.secondaryTextColor, fontSize = 12.sp) }
        } else if (choosing != null && step !is RestoreStep.Finished) {
            item(key = "pick") {
                val candidates = RoleTargets.candidates(facts.roles[choosing]?.qualified.orEmpty(), own, ledger.record(choosing).original)
                CandidateGroup(roleName(choosing), candidates, label) { pkg ->
                    flow.choose(choosing, pkg)
                    picking = null
                    refresh()
                }
            }
            item(key = "pick_back") {
                AgentSettingsGroup {
                    AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_back), onClick = { picking = null }, last = true)
                }
            }
        } else {
            when (step) {
                is RestoreStep.Finished -> item(key = "finishing") {
                    Text(stringResource(R.string.surface_comm_restore_finishing), color = style.secondaryTextColor, fontSize = 12.sp)
                }

                is RestoreStep.WaitForCall -> item(key = "call") {
                    AgentSettingsGroup(stringResource(R.string.surface_comm_restore_in_call_title)) {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_comm_restore_in_call_body, roleName(step.role)),
                        )
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_continue), onClick = ::refresh)
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_cancel), onClick = ::cancel, last = true)
                    }
                }

                is RestoreStep.Ready -> item(key = "ready") {
                    AgentSettingsGroup(roleName(step.role)) {
                        AgentSettingsEntry(
                            title = stringResource(
                                if (step.isOriginal) R.string.surface_comm_restore_ready_original_fmt else R.string.surface_comm_restore_ready_chosen_fmt,
                                roleName(step.role),
                                label(step.target),
                            ),
                            subtitle = stringResource(
                                when {
                                    root -> R.string.surface_comm_restore_via_root
                                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q -> R.string.surface_comm_restore_via_settings
                                    else -> R.string.surface_comm_restore_via_dialog
                                },
                                label(step.target),
                            ),
                            onClick = { restore(step.role, step.target) },
                        )
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_pick_other), onClick = { picking = step.role })
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_cancel), onClick = ::cancel, last = true)
                    }
                }

                is RestoreStep.Choose -> item(key = "choose") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(
                            when (step.problem) {
                                TargetProblem.NOT_RECORDED -> R.string.surface_comm_restore_problem_unrecorded
                                TargetProblem.WAS_EMPTY -> R.string.surface_comm_restore_problem_empty
                                TargetProblem.UNAVAILABLE -> R.string.surface_comm_restore_problem_unavailable
                            },
                            roleName(step.role),
                        ),
                        color = style.secondaryTextColor,
                        fontSize = 12.sp,
                    )
                    CandidateGroup(roleName(step.role), step.candidates, label) { pkg ->
                        flow.choose(step.role, pkg)
                        refresh()
                    }
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_comm_restore_skip),
                            subtitle = stringResource(R.string.surface_comm_restore_skip_sub, roleName(step.role)),
                            onClick = {
                                flow.skip(step.role)
                                refresh()
                            },
                        )
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_cancel), onClick = ::cancel, last = true)
                    }
                    }
                }

                is RestoreStep.Failed -> item(key = "failed") {
                    AgentSettingsGroup(roleName(step.role)) {
                        AgentSettingsEntry(
                            title = stringResource(
                                when (step.outcome) {
                                    RestoreOutcome.STILL_SELF -> R.string.surface_comm_restore_failed_self
                                    RestoreOutcome.EMPTY -> R.string.surface_comm_restore_failed_empty
                                    RestoreOutcome.OTHER -> R.string.surface_comm_restore_failed_other
                                    RestoreOutcome.RESTORED -> R.string.surface_comm_restore_state_done
                                },
                                roleName(step.role),
                                label(step.target),
                                label(step.holder),
                            ),
                        )
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_comm_restore_retry),
                            onClick = {
                                flow.retry(step.role)
                                refresh()
                            },
                        )
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_pick_other), onClick = {
                            flow.retry(step.role)
                            picking = step.role
                        })
                        if (step.outcome == RestoreOutcome.OTHER && step.holder != null) {
                            AgentSettingsEntry(
                                title = stringResource(R.string.surface_comm_restore_accept_fmt, label(step.holder)),
                                onClick = {
                                    flow.acceptCurrent(step.role)
                                    refresh()
                                },
                            )
                        }
                        AgentSettingsEntry(
                            title = stringResource(R.string.surface_comm_restore_skip),
                            subtitle = stringResource(R.string.surface_comm_restore_skip_sub, roleName(step.role)),
                            onClick = {
                                flow.skip(step.role)
                                refresh()
                            },
                        )
                        AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_cancel), onClick = ::cancel, last = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateGroup(role: String, candidates: List<String>, label: (String?) -> String, onPick: (String) -> Unit) {
    AgentSettingsGroup(stringResource(R.string.surface_comm_restore_candidates_fmt, role)) {
        if (candidates.isEmpty()) {
            AgentSettingsEntry(title = stringResource(R.string.surface_comm_restore_no_candidates), last = true)
        }
        candidates.forEachIndexed { index, pkg ->
            AgentSettingsEntry(
                title = label(pkg),
                subtitle = pkg,
                onClick = { onPick(pkg) },
                last = index == candidates.lastIndex,
            )
        }
    }
}
