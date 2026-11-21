package com.flue.launcher.surface.comm

/**
 * R32 通信角色：默认电话（拨号）应用与默认短信应用，分别记录、分别恢复。
 */
enum class CommRole(val roleName: String) {
    DIALER("android.app.role.DIALER"),
    SMS("android.app.role.SMS"),
}

/**
 * 一个角色的接管记录。[recorded] 为 true 时 [original] 就是首次接管前系统里的真实持有者；
 * 持有者为空也是原值（R30），记为 null。
 */
data class RoleRecord(val recorded: Boolean = false, val original: String? = null) {
    companion object {
        val NONE = RoleRecord()
    }
}

/** 记录的持久化；实现用同步提交，接管前先落盘。 */
interface RoleLedgerStorage {
    fun read(role: CommRole): RoleRecord
    fun write(role: CommRole, record: RoleRecord): Boolean
}

/**
 * 首次接管前记录原电话／短信应用（R32）。
 *
 * - 已经记录过就不再覆盖：完整／伪装反复切换、多次申请都保持第一次的原值。
 * - 本应用自己不能被记为原应用：如果申请时本应用已经是持有者（例如用户自己在系统里选的），
 *   原值未知，保持未记录，恢复时让用户从候选中选。
 * - 恢复读回确认后清除记录，下次接管重新记录当时的原值。
 */
class RoleLedger(private val storage: RoleLedgerStorage, private val ownPackage: String) {

    fun record(role: CommRole): RoleRecord = storage.read(role)

    /** @return true 表示这次写下了原值。 */
    fun recordBeforeTakeover(role: CommRole, currentHolder: String?): Boolean {
        if (storage.read(role).recorded) return false
        val holder = currentHolder?.takeIf { it.isNotBlank() }
        if (holder == ownPackage) return false
        return storage.write(role, RoleRecord(recorded = true, original = holder))
    }

    /**
     * 申请结果读回后调用。本应用确实成为持有者才算接管；没接管（取消、系统拒绝）时撤掉这次
     * 新写的记录，免得以后用户换了默认应用，再接管时还拿着过期的原值。
     * @return 是否已接管。
     */
    fun afterTakeover(role: CommRole, recordedNow: Boolean, holderNow: String?): Boolean {
        if (holderNow == ownPackage) return true
        if (recordedNow) clear(role)
        return false
    }

    fun clear(role: CommRole) {
        storage.write(role, RoleRecord.NONE)
    }
}

/** 恢复后读回的判定。只有 [RESTORED] 算成功。 */
enum class RestoreOutcome {
    /** 读回就是目标应用。 */
    RESTORED,

    /** 仍是本应用：系统选择被取消或命令没有生效。 */
    STILL_SELF,

    /** 角色为空：只撤销了本应用，没有恢复原应用。 */
    EMPTY,

    /** 是另一个应用，不是目标。 */
    OTHER,
}

object RoleReadback {
    fun evaluate(target: String, holder: String?, ownPackage: String): RestoreOutcome {
        val actual = holder?.takeIf { it.isNotBlank() }
        return when (actual) {
            null -> RestoreOutcome.EMPTY
            target -> RestoreOutcome.RESTORED
            ownPackage -> RestoreOutcome.STILL_SELF
            else -> RestoreOutcome.OTHER
        }
    }
}

/** 为什么不能直接恢复到记录的原应用。 */
enum class TargetProblem {
    /** 接管前本应用已是持有者，没有原值。 */
    NOT_RECORDED,

    /** 接管前角色为空。 */
    WAS_EMPTY,

    /** 原应用已卸载或不再能担任该角色。 */
    UNAVAILABLE,
}

sealed interface RoleTarget {
    data class Ready(val packageName: String, val isOriginal: Boolean) : RoleTarget
    data class NeedsChoice(val problem: TargetProblem, val candidates: List<String>) : RoleTarget
}

object RoleTargets {
    /** 可选的恢复目标：去掉本应用、去重；原应用（若可用）排第一。 */
    fun candidates(qualified: List<String>, ownPackage: String, original: String?): List<String> {
        val list = qualified.filter { it.isNotBlank() && it != ownPackage }.distinct()
        return if (original != null && original in list) listOf(original) + (list - original) else list
    }

    /**
     * @param chosen 用户在候选中另选的目标；只有仍可担任该角色时才采用。
     */
    fun resolve(record: RoleRecord, qualified: List<String>, ownPackage: String, chosen: String?): RoleTarget {
        val candidates = candidates(qualified, ownPackage, record.original)
        if (chosen != null && chosen in candidates) {
            return RoleTarget.Ready(chosen, isOriginal = chosen == record.original)
        }
        val problem = when {
            !record.recorded -> TargetProblem.NOT_RECORDED
            record.original == null -> TargetProblem.WAS_EMPTY
            record.original !in candidates -> TargetProblem.UNAVAILABLE
            else -> return RoleTarget.Ready(record.original, isOriginal = true)
        }
        return RoleTarget.NeedsChoice(problem, candidates)
    }
}
