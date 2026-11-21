package com.flue.launcher.surface.comm

/**
 * 一次“退出到外部桌面前恢复通信角色”的进度（R32）。持久保存：系统角色变化可能结束本进程，
 * 重建后从这里继续——只读回结果，不自动再次弹出系统申请，也不带旧的管理资格。
 */
data class RestoreProgress(
    val active: Boolean = false,
    /** 本次要处理的角色：开始时由本应用持有的角色。 */
    val roles: Set<CommRole> = emptySet(),
    /** 读回确认已恢复，或本应用已不再持有且用户另有选择。 */
    val done: Set<CommRole> = emptySet(),
    /** 用户明确选择“不恢复，继续退出”。 */
    val skipped: Set<CommRole> = emptySet(),
    /** 原应用失效时用户另选的目标。 */
    val chosen: Map<CommRole, String> = emptyMap(),
    /** 上一次尝试读回后没有成功的角色及结果；需要用户决定重试、另选、保留或取消。 */
    val failed: Map<CommRole, RestoreOutcome> = emptyMap(),
    /** 每个角色最近一次尝试恢复到的目标。 */
    val attempted: Map<CommRole, String> = emptyMap(),
    /** 已经打开系统选择或执行了命令、等待读回的角色。 */
    val awaiting: CommRole? = null,
) {
    val pending: List<CommRole> get() = CommRole.entries.filter { it in roles && it !in done && it !in skipped }

    companion object {
        val IDLE = RestoreProgress()
    }
}

interface RestoreProgressStorage {
    fun readProgress(): RestoreProgress
    fun writeProgress(progress: RestoreProgress): Boolean
}

/** 某个角色此刻的系统事实。 */
data class RoleFacts(
    /** 系统读回的当前持有者；空为 null。 */
    val holder: String?,
    /** 本机可以担任该角色的应用（含本应用也无妨，会被排除）。 */
    val qualified: List<String>,
)

data class RestoreFacts(val roles: Map<CommRole, RoleFacts>, val inCall: Boolean)

sealed interface RestoreStep {
    /** 本次涉及的角色都已处理，可以继续退出。 */
    data class Finished(val done: Set<CommRole>, val skipped: Set<CommRole>) : RestoreStep

    /** 正在通话：不切换角色，等用户在通话结束后继续或取消（不自动挂断）。 */
    data class WaitForCall(val role: CommRole) : RestoreStep

    /** 可以恢复到 [target]。 */
    data class Ready(val role: CommRole, val target: String, val isOriginal: Boolean) : RestoreStep

    /** 原应用不能用：从候选中选。候选可能为空。 */
    data class Choose(val role: CommRole, val problem: TargetProblem, val candidates: List<String>) : RestoreStep

    /** 尝试后读回不是目标。 */
    data class Failed(
        val role: CommRole,
        val outcome: RestoreOutcome,
        val holder: String?,
        val target: String?,
    ) : RestoreStep
}

/**
 * 角色恢复状态机。不调用系统：事实由调用方读回后传入，动作结果也靠下一次读回确认。
 */
class RoleRestoreFlow(
    private val storage: RestoreProgressStorage,
    private val ledger: RoleLedger,
    private val ownPackage: String,
) {
    val progress: RestoreProgress get() = storage.readProgress()

    /** 本应用此刻持有、退出外部前需要交还的角色。 */
    fun rolesHeld(facts: RestoreFacts): Set<CommRole> =
        CommRole.entries.filter { facts.roles[it]?.holder == ownPackage }.toSet()

    /** 退出外部前是否需要走恢复流程：有进行中的进度，或本应用仍持有任一角色。 */
    fun needed(facts: RestoreFacts): Boolean = progress.active || rolesHeld(facts).isNotEmpty()

    /** 开始或继续一次恢复。已有进度时沿用（进程重建后继续）。 */
    fun begin(facts: RestoreFacts): RestoreProgress {
        val current = storage.readProgress()
        if (current.active) return current
        val next = RestoreProgress(active = true, roles = rolesHeld(facts))
        storage.writeProgress(next)
        return next
    }

    fun step(facts: RestoreFacts): RestoreStep {
        var p = storage.readProgress()

        // 1. 上一次已经打开系统选择或执行命令：只读回，不再申请。
        val awaiting = p.awaiting
        if (awaiting != null) {
            val target = p.attempted[awaiting].orEmpty()
            val holder = facts.roles[awaiting]?.holder
            val outcome = RoleReadback.evaluate(target, holder, ownPackage)
            p = if (outcome == RestoreOutcome.RESTORED) {
                ledger.clear(awaiting)
                p.copy(awaiting = null, done = p.done + awaiting, failed = p.failed - awaiting)
            } else {
                p.copy(awaiting = null, failed = p.failed + (awaiting to outcome))
            }
            storage.writeProgress(p)
        }

        for (role in p.pending) {
            val f = facts.roles[role] ?: RoleFacts(holder = null, qualified = emptyList())
            val holder = f.holder?.takeIf { it.isNotBlank() }
            val failedOutcome = p.failed[role]
            if (failedOutcome != null) {
                // 失败后重新进来：如果此刻已经是目标（用户自己在系统里改好了），算恢复成功。
                val target = p.attempted[role]
                if (target != null && holder == target) {
                    ledger.clear(role)
                    p = p.copy(done = p.done + role, failed = p.failed - role)
                    storage.writeProgress(p)
                    continue
                }
                return RestoreStep.Failed(role, failedOutcome, holder, target)
            }
            if (holder != null && holder != ownPackage) {
                // 还没尝试，本应用已不再持有：用户自己改过默认应用，不按旧快照覆盖（R07）。
                p = p.copy(done = p.done + role)
                storage.writeProgress(p)
                continue
            }
            if (facts.inCall) return RestoreStep.WaitForCall(role)
            return when (val target = RoleTargets.resolve(ledger.record(role), f.qualified, ownPackage, p.chosen[role])) {
                is RoleTarget.Ready -> RestoreStep.Ready(role, target.packageName, target.isOriginal)
                is RoleTarget.NeedsChoice -> RestoreStep.Choose(role, target.problem, target.candidates)
            }
        }
        return RestoreStep.Finished(done = p.done, skipped = p.skipped)
    }

    /** 打开系统选择或执行命令之前调用：先落盘，进程被结束后也能读回。 */
    fun launched(role: CommRole, target: String) {
        val p = storage.readProgress()
        storage.writeProgress(p.copy(awaiting = role, attempted = p.attempted + (role to target), failed = p.failed - role))
    }

    fun choose(role: CommRole, packageName: String) {
        val p = storage.readProgress()
        storage.writeProgress(p.copy(chosen = p.chosen + (role to packageName), failed = p.failed - role))
    }

    /** 失败后重试：回到“可以恢复／需要选择”。 */
    fun retry(role: CommRole) {
        val p = storage.readProgress()
        storage.writeProgress(p.copy(failed = p.failed - role))
    }

    /** 用户接受此刻的其他持有者（不是原应用），视为该角色已交还。 */
    fun acceptCurrent(role: CommRole) {
        val p = storage.readProgress()
        ledger.clear(role)
        storage.writeProgress(p.copy(done = p.done + role, failed = p.failed - role))
    }

    /** 用户明确选择不恢复该角色、继续退出；记录保留，下次仍可恢复。 */
    fun skip(role: CommRole) {
        val p = storage.readProgress()
        storage.writeProgress(p.copy(skipped = p.skipped + role, failed = p.failed - role))
    }

    /** 取消本次退出：清除进度，已恢复的角色保持恢复，记录保留。 */
    fun cancel() {
        storage.writeProgress(RestoreProgress.IDLE)
    }

    /** 退出完成后清除进度。 */
    fun complete() {
        storage.writeProgress(RestoreProgress.IDLE)
    }
}
