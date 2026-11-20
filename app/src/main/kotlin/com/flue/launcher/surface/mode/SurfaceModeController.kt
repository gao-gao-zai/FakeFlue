package com.flue.launcher.surface.mode

import com.flue.launcher.surface.mode.ModeSwitchResult.Blocked
import com.flue.launcher.surface.mode.ModeSwitchResult.Busy
import com.flue.launcher.surface.mode.ModeSwitchResult.Failed
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设备级模式状态的持久化（不随模式切换）。 */
interface SurfaceStateStorage {
    fun readMode(): SurfaceMode?
    /** 同步提交；返回 false 表示没有写成功。 */
    fun writeMode(mode: SurfaceMode): Boolean
    fun readExternalTarget(): String?
    fun writeExternalTarget(flattenedComponent: String): Boolean
}

/** 进入伪装模式前还缺什么（R07、R18、R22）。P4 接入专属配置与教程成绩。 */
enum class DisguiseEntryRequirement { BASE_CONFIGURATION, TUTORIAL }

fun interface DisguiseEntryGate {
    /** 返回 null 表示可以进入。 */
    fun missingRequirement(): DisguiseEntryRequirement?
}

/** 模式切换的系统副作用，便于单元测试替换。 */
interface ModeSwitchEffects {
    /** 已进入伪装模式：关闭不该继续显示的受限页面、停止禁止主动运行的任务（R07）。 */
    fun onEnteredRestrictedMode()

    /** 已离开伪装模式。P5 在这里解除系统限制。 */
    fun onLeftRestrictedMode()

    fun ownPackageName(): String

    /** 目标是否为当前仍可启动的 HOME 桌面。 */
    fun isLaunchableHome(flattenedComponent: String): Boolean

    /** 实际打开外部桌面；返回 false 表示没有打开。 */
    fun launchExternalHome(flattenedComponent: String): Boolean

    /** 系统当前的默认 HOME 包名；没有默认项时为 null。 */
    fun defaultHomePackage(): String?
}

sealed interface ModeSwitchResult {
    data object Success : ModeSwitchResult

    /**
     * 已退出到外部桌面，但系统默认 HOME 仍是本应用（R07）：需要用户在系统设置里选择默认桌面，
     * 界面应打开真实默认应用设置，不能把“打开了某个桌面”当成“已恢复默认桌面”。
     */
    data object ExitedButDefaultHomeIsSelf : ModeSwitchResult

    data object AlreadyInMode : ModeSwitchResult

    /** 另一次切换仍在进行，拒绝重复提交。 */
    data object Busy : ModeSwitchResult

    data class Blocked(val requirement: DisguiseEntryRequirement) : ModeSwitchResult

    data class Failed(val reason: FailureReason) : ModeSwitchResult

    enum class FailureReason {
        PERSIST_FAILED,
        EXTERNAL_TARGET_MISSING,
        EXTERNAL_TARGET_INVALID,
        EXTERNAL_LAUNCH_FAILED,
    }
}

/**
 * R07：所有入口共用的唯一模式切换入口。
 *
 * - 进行中不能重复提交；失败不记为成功，并回滚到原模式。
 * - 外部桌面目标不能被本应用、空值或无效结果覆盖。
 * - 重启后读回最后一次成功的模式。
 */
class SurfaceModeController(
    private val storage: SurfaceStateStorage,
    private val gate: DisguiseEntryGate,
    private val effects: ModeSwitchEffects,
) {
    private val switching = AtomicBoolean(false)
    private val _mode = MutableStateFlow(storage.readMode() ?: SurfaceMode.FULL)
    private val _configScope = MutableStateFlow(_mode.value.configScope)

    val mode: StateFlow<SurfaceMode> = _mode.asStateFlow()
    val configScope: StateFlow<ConfigScope> = _configScope.asStateFlow()

    val externalTarget: String? get() = storage.readExternalTarget()

    /** 设置外部桌面目标。拒绝本应用、空值和当前不可启动的桌面。 */
    fun setExternalTarget(flattenedComponent: String?): Boolean {
        val target = flattenedComponent?.trim().orEmpty()
        if (target.isEmpty() || isOwnComponent(target) || !effects.isLaunchableHome(target)) return false
        return storage.writeExternalTarget(target)
    }

    fun switchTo(target: SurfaceMode): ModeSwitchResult {
        if (!switching.compareAndSet(false, true)) return Busy
        try {
            val previous = _mode.value
            // 已在外部模式时仍要真正打开外部桌面：用户可能从应用图标回到了 FFlue，或刚换了目标。
            if (previous == target && target == SurfaceMode.EXTERNAL) return relaunchExternal()
            if (previous == target) return ModeSwitchResult.AlreadyInMode
            return when (target) {
                SurfaceMode.FULL -> commit(previous, SurfaceMode.FULL)
                SurfaceMode.DISGUISE -> {
                    gate.missingRequirement()?.let { return Blocked(it) }
                    commit(previous, SurfaceMode.DISGUISE)
                }
                SurfaceMode.EXTERNAL -> exitToExternal(previous)
            }
        } finally {
            switching.set(false)
        }
    }

    private fun relaunchExternal(): ModeSwitchResult {
        val target = storage.readExternalTarget()?.takeIf { it.isNotBlank() && !isOwnComponent(it) }
            ?: return Failed(ModeSwitchResult.FailureReason.EXTERNAL_TARGET_MISSING)
        if (!effects.isLaunchableHome(target)) return Failed(ModeSwitchResult.FailureReason.EXTERNAL_TARGET_INVALID)
        if (!effects.launchExternalHome(target)) return Failed(ModeSwitchResult.FailureReason.EXTERNAL_LAUNCH_FAILED)
        return ModeSwitchResult.Success
    }

    private fun exitToExternal(previous: SurfaceMode): ModeSwitchResult {
        val target = storage.readExternalTarget()?.takeIf { it.isNotBlank() && !isOwnComponent(it) }
            ?: return Failed(ModeSwitchResult.FailureReason.EXTERNAL_TARGET_MISSING)
        // 先验证目标可用，再解除专属限制（R07）。
        if (!effects.isLaunchableHome(target)) return Failed(ModeSwitchResult.FailureReason.EXTERNAL_TARGET_INVALID)
        val committed = commit(previous, SurfaceMode.EXTERNAL)
        if (committed != ModeSwitchResult.Success) return committed
        if (!effects.launchExternalHome(target)) {
            // 目标没有真正打开：回到原模式，不能记为退出成功。
            rollbackTo(previous)
            return Failed(ModeSwitchResult.FailureReason.EXTERNAL_LAUNCH_FAILED)
        }
        return if (effects.defaultHomePackage() == effects.ownPackageName()) {
            ModeSwitchResult.ExitedButDefaultHomeIsSelf
        } else {
            ModeSwitchResult.Success
        }
    }

    private fun commit(previous: SurfaceMode, next: SurfaceMode): ModeSwitchResult {
        if (!storage.writeMode(next)) return Failed(ModeSwitchResult.FailureReason.PERSIST_FAILED)
        publish(next)
        when {
            next == SurfaceMode.DISGUISE -> effects.onEnteredRestrictedMode()
            previous == SurfaceMode.DISGUISE -> effects.onLeftRestrictedMode()
        }
        return ModeSwitchResult.Success
    }

    private fun rollbackTo(previous: SurfaceMode) {
        storage.writeMode(previous)
        publish(previous)
        if (previous == SurfaceMode.DISGUISE) effects.onEnteredRestrictedMode()
    }

    private fun publish(mode: SurfaceMode) {
        _mode.value = mode
        _configScope.value = mode.configScope
    }

    private fun isOwnComponent(flattened: String): Boolean =
        flattened.substringBefore('/') == effects.ownPackageName()
}
