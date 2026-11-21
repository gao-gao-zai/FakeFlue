package com.flue.launcher.surface

import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.DisguiseEntryGate
import com.flue.launcher.surface.mode.DisguiseEntryRequirement
import com.flue.launcher.surface.mode.ModeSwitchEffects
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.ModeSwitchResult.FailureReason
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.mode.SurfaceModeController
import com.flue.launcher.surface.mode.SurfaceStateStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R07 模式状态机。 */
class SurfaceModeControllerTest {

    private class FakeStorage(var mode: SurfaceMode? = null, var target: String? = null) : SurfaceStateStorage {
        var failWrites = false
        override fun readMode() = mode
        override fun writeMode(mode: SurfaceMode): Boolean {
            if (failWrites) return false
            this.mode = mode
            return true
        }
        override fun readExternalTarget() = target
        override fun writeExternalTarget(flattenedComponent: String): Boolean {
            target = flattenedComponent
            return true
        }
    }

    private class FakeEffects : ModeSwitchEffects {
        val homes = mutableSetOf("com.other.home/.Home")
        var launchSucceeds = true
        var defaultHome: String? = "com.other.home"
        val events = mutableListOf<String>()
        var onLaunch: (() -> Unit)? = null
        override fun onEnteredRestrictedMode() { events += "enter" }
        override fun onLeftRestrictedMode() { events += "leave" }
        override fun ownPackageName() = "com.gaozai.surface"
        override fun isLaunchableHome(flattenedComponent: String) = flattenedComponent in homes
        override fun launchExternalHome(flattenedComponent: String): Boolean {
            onLaunch?.invoke()
            events += "launch:$flattenedComponent"
            return launchSucceeds
        }
        override fun defaultHomePackage() = defaultHome
    }

    private val storage = FakeStorage()
    private val effects = FakeEffects()
    private var gateResult: DisguiseEntryRequirement? = null
    private val controller by lazy { SurfaceModeController(storage, DisguiseEntryGate { gateResult }, effects) }

    @Test
    fun defaultsToFullAndRestoresLastMode() {
        assertEquals(SurfaceMode.FULL, controller.mode.value)
        val restored = SurfaceModeController(FakeStorage(SurfaceMode.DISGUISE), DisguiseEntryGate { null }, effects)
        assertEquals(SurfaceMode.DISGUISE, restored.mode.value)
        assertEquals(ConfigScope.DISGUISE, restored.configScope.value)
    }

    @Test
    fun fullToDisguiseAndBack() {
        assertEquals(ModeSwitchResult.Success, controller.switchTo(SurfaceMode.DISGUISE))
        assertEquals(SurfaceMode.DISGUISE, storage.mode)
        assertEquals(ConfigScope.DISGUISE, controller.configScope.value)
        assertEquals(ModeSwitchResult.Success, controller.switchTo(SurfaceMode.FULL))
        assertEquals(SurfaceMode.FULL, storage.mode)
        assertEquals(ConfigScope.FULL, controller.configScope.value)
        assertEquals(listOf("enter", "leave"), effects.events)
    }

    @Test
    fun sameModeIsNoOp() {
        assertEquals(ModeSwitchResult.AlreadyInMode, controller.switchTo(SurfaceMode.FULL))
        assertTrue(effects.events.isEmpty())
    }

    @Test
    fun gateBlocksDisguiseWithoutChangingState() {
        gateResult = DisguiseEntryRequirement.TUTORIAL
        assertEquals(ModeSwitchResult.Blocked(DisguiseEntryRequirement.TUTORIAL), controller.switchTo(SurfaceMode.DISGUISE))
        assertEquals(SurfaceMode.FULL, controller.mode.value)
        assertEquals(null, storage.mode)
    }

    @Test
    fun persistFailureIsNotSuccess() {
        storage.failWrites = true
        assertEquals(ModeSwitchResult.Failed(FailureReason.PERSIST_FAILED), controller.switchTo(SurfaceMode.DISGUISE))
        assertEquals(SurfaceMode.FULL, controller.mode.value)
        assertTrue(effects.events.isEmpty())
    }

    @Test
    fun concurrentSwitchIsRejected() {
        storage.target = "com.other.home/.Home"
        var nested: ModeSwitchResult? = null
        effects.onLaunch = { nested = controller.switchTo(SurfaceMode.FULL) }
        controller.switchTo(SurfaceMode.EXTERNAL)
        assertEquals(ModeSwitchResult.Busy, nested)
        // 进行中的标记在结束后释放。
        assertEquals(ModeSwitchResult.Success, controller.switchTo(SurfaceMode.FULL))
    }

    @Test
    fun externalRequiresTarget() {
        assertEquals(ModeSwitchResult.Failed(FailureReason.EXTERNAL_TARGET_MISSING), controller.switchTo(SurfaceMode.EXTERNAL))
        assertEquals(SurfaceMode.FULL, controller.mode.value)
    }

    @Test
    fun externalRejectsInvalidTargetBeforeLeavingDisguise() {
        controller.switchTo(SurfaceMode.DISGUISE)
        storage.target = "com.removed.home/.Home"
        assertEquals(ModeSwitchResult.Failed(FailureReason.EXTERNAL_TARGET_INVALID), controller.switchTo(SurfaceMode.EXTERNAL))
        assertEquals(SurfaceMode.DISGUISE, controller.mode.value)
        assertEquals(listOf("enter"), effects.events)
    }

    @Test
    fun externalLaunchFailureRollsBack() {
        controller.switchTo(SurfaceMode.DISGUISE)
        storage.target = "com.other.home/.Home"
        effects.launchSucceeds = false
        assertEquals(ModeSwitchResult.Failed(FailureReason.EXTERNAL_LAUNCH_FAILED), controller.switchTo(SurfaceMode.EXTERNAL))
        assertEquals(SurfaceMode.DISGUISE, controller.mode.value)
        assertEquals(SurfaceMode.DISGUISE, storage.mode)
        assertEquals(ConfigScope.DISGUISE, controller.configScope.value)
        assertEquals("enter", effects.events.last())
    }

    @Test
    fun externalSuccessAndDefaultHomeStillSelf() {
        storage.target = "com.other.home/.Home"
        assertEquals(ModeSwitchResult.Success, controller.switchTo(SurfaceMode.EXTERNAL))
        assertEquals(SurfaceMode.EXTERNAL, storage.mode)
        assertEquals(ConfigScope.FULL, controller.configScope.value)

        controller.switchTo(SurfaceMode.FULL)
        effects.defaultHome = "com.gaozai.surface"
        assertEquals(ModeSwitchResult.ExitedButDefaultHomeIsSelf, controller.switchTo(SurfaceMode.EXTERNAL))
    }

    @Test
    fun externalActionRelaunchesWhenAlreadyExternal() {
        storage.target = "com.other.home/.Home"
        assertEquals(ModeSwitchResult.Success, controller.switchTo(SurfaceMode.EXTERNAL))
        // 从应用图标回到 FFlue 后再点“返回外部桌面”，或刚换了目标：要真的再打开一次。
        effects.homes += "com.another.home/.Home"
        storage.target = "com.another.home/.Home"
        assertEquals(ModeSwitchResult.Success, controller.switchTo(SurfaceMode.EXTERNAL))
        assertEquals("launch:com.another.home/.Home", effects.events.last())
        assertEquals(SurfaceMode.EXTERNAL, controller.mode.value)

        effects.launchSucceeds = false
        assertEquals(ModeSwitchResult.Failed(FailureReason.EXTERNAL_LAUNCH_FAILED), controller.switchTo(SurfaceMode.EXTERNAL))
        assertEquals(SurfaceMode.EXTERNAL, controller.mode.value)
    }

    @Test
    fun externalTargetCannotBeSelfBlankOrUnlaunchable() {
        effects.homes += "com.gaozai.surface/com.flue.launcher.LauncherActivity"
        assertFalse(controller.setExternalTarget("com.gaozai.surface/com.flue.launcher.LauncherActivity"))
        assertFalse(controller.setExternalTarget(""))
        assertFalse(controller.setExternalTarget(null))
        assertFalse(controller.setExternalTarget("com.missing/.Home"))
        assertEquals(null, storage.target)
        assertTrue(controller.setExternalTarget("com.other.home/.Home"))
        assertEquals("com.other.home/.Home", controller.externalTarget)
        assertFalse(controller.setExternalTarget(""))
        assertEquals("com.other.home/.Home", controller.externalTarget)
    }

    @Test
    fun externalTargetProblemIsCheckedBeforeRestoringRoles() {
        // R32：退出外部前先确认目标，避免先交还电话／短信再因目标无效失败。
        assertEquals(FailureReason.EXTERNAL_TARGET_MISSING, controller.externalTargetProblem())
        storage.target = "com.gaozai.surface/com.flue.launcher.LauncherActivity"
        assertEquals(FailureReason.EXTERNAL_TARGET_MISSING, controller.externalTargetProblem())
        storage.target = "com.missing/.Home"
        assertEquals(FailureReason.EXTERNAL_TARGET_INVALID, controller.externalTargetProblem())
        storage.target = "com.other.home/.Home"
        assertEquals(null, controller.externalTargetProblem())
        // 只检查，不切换。
        assertEquals(SurfaceMode.FULL, controller.mode.value)
        assertTrue(effects.events.isEmpty())
    }

    @Test
    fun storedSelfTargetIsTreatedAsMissing() {
        storage.target = "com.gaozai.surface/com.flue.launcher.LauncherActivity"
        assertEquals(ModeSwitchResult.Failed(FailureReason.EXTERNAL_TARGET_MISSING), controller.switchTo(SurfaceMode.EXTERNAL))
    }
}
