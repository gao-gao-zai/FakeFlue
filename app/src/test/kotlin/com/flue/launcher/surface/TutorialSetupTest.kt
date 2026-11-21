package com.flue.launcher.surface

import android.content.Context
import android.content.Intent
import com.flue.launcher.surface.entry.AdbTutorialSkip
import com.flue.launcher.surface.entry.AdbTutorialSkipReceiver
import com.flue.launcher.surface.entry.EntryOrigin
import com.flue.launcher.surface.mode.DisguiseEntryRequirement
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.mode.SurfaceStateStore
import com.flue.launcher.surface.setup.DisguiseSetupRules
import com.flue.launcher.surface.setup.DisguiseSetupStep
import com.flue.launcher.surface.setup.PermissionGroup
import com.flue.launcher.surface.setup.PermissionItem
import com.flue.launcher.surface.setup.PermissionStatus
import com.flue.launcher.surface.setup.PermissionSummary
import com.flue.launcher.surface.tutorial.PracticeSession
import com.flue.launcher.surface.tutorial.PracticeTask
import com.flue.launcher.surface.tutorial.TeachLevel
import com.flue.launcher.surface.tutorial.TeachStep
import com.flue.launcher.surface.tutorial.TeachTrack
import com.flue.launcher.surface.tutorial.TutorialRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P4：配置步骤、授权统计、教学状态机、最终练习计时与计数、成绩版本、ADB 跳过（R18–R22、R28）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TutorialSetupTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // ---- 配置步骤（R18、R19）----

    @Test
    fun setupResumesAtFirstPendingStep() {
        assertEquals(DisguiseSetupStep.SAFE_AREA, DisguiseSetupStep.firstPending(emptySet()))
        val done = setOf(DisguiseSetupStep.SAFE_AREA, DisguiseSetupStep.DPI, DisguiseSetupStep.WATCH_FACE)
        assertEquals(DisguiseSetupStep.PERMISSIONS, DisguiseSetupStep.firstPending(done))
        assertNull(DisguiseSetupStep.firstPending(DisguiseSetupStep.entries.toSet()))
        assertEquals(done, DisguiseSetupStep.parse(setOf("SAFE_AREA", "DPI", "WATCH_FACE", "UNKNOWN")))
    }

    @Test
    fun baseConfigNeedsEveryStepAndReadableSecret() {
        val all = DisguiseSetupStep.entries.toSet()
        assertTrue(DisguiseSetupRules.baseConfigured(all, secretReadable = true))
        assertFalse(DisguiseSetupRules.baseConfigured(all, secretReadable = false))
        assertFalse(DisguiseSetupRules.baseConfigured(all - DisguiseSetupStep.EXIT_TARGET, secretReadable = true))
    }

    @Test
    fun gateAsksForConfigurationFirst() {
        val runtime = SurfaceRuntime.get(context)
        runtime.stateStore.welcomeCompleted = true
        // 教程成绩有了也不能跳过基础配置。
        runtime.recordTutorialPassed()
        assertEquals(
            ModeSwitchResult.Blocked(DisguiseEntryRequirement.BASE_CONFIGURATION),
            runtime.controller.switchTo(SurfaceMode.DISGUISE),
        )
    }

    @Test
    fun setupStepsPersist() {
        val runtime = SurfaceRuntime.get(context)
        runtime.markDisguiseSetupStep(DisguiseSetupStep.DPI)
        runtime.markDisguiseSetupStep(DisguiseSetupStep.SECRET)
        assertEquals(setOf(DisguiseSetupStep.DPI, DisguiseSetupStep.SECRET), runtime.disguiseSetupDone())
        assertEquals(setOf("DPI", "SECRET"), SurfaceStateStore(context).disguiseSetupSteps)
    }

    @Test
    fun entryOriginRoundTrip() {
        val intent = Intent().putExtra(EntryOrigin.EXTRA, EntryOrigin.ONBOARDING.name)
        assertEquals(EntryOrigin.ONBOARDING, EntryOrigin.from(intent))
        assertEquals(EntryOrigin.NONE, EntryOrigin.from(Intent().putExtra(EntryOrigin.EXTRA, "bogus")))
        assertEquals(EntryOrigin.NONE, EntryOrigin.from(null))
    }

    // ---- 授权统计（R19、R28）----

    @Test
    fun permissionSummaryCountsOnlyApplicableItems() {
        val statuses = PermissionItem.entries.associateWith { PermissionStatus.DENIED }.toMutableMap()
        statuses[PermissionItem.ROOT] = PermissionStatus.UNCHECKED
        statuses[PermissionItem.OVERLAY] = PermissionStatus.GRANTED
        statuses[PermissionItem.ACTIVITY] = PermissionStatus.NOT_APPLICABLE
        statuses[PermissionItem.CALENDAR] = PermissionStatus.GRANTED
        val byGroup = PermissionSummary.of(statuses).associateBy { it.group }
        val system = byGroup.getValue(PermissionGroup.SYSTEM_CONTROL)
        assertEquals(1, system.configured)
        assertEquals(5, system.total)
        assertEquals(listOf(PermissionItem.OVERLAY), system.configuredItems)
        val face = byGroup.getValue(PermissionGroup.FACE_DATA)
        assertEquals(1, face.configured)
        assertEquals(2, face.total)
        assertEquals(0, byGroup.getValue(PermissionGroup.PHONE_SMS).total)
    }

    // ---- 教学状态机（R20）----

    @Test
    fun teachTrackAdvancesOnlyInOrder() {
        val track = TeachTrack(TeachLevel.HIDDEN_DRAWER)
        assertFalse(track.report(TeachStep.HOLD_BATTERY))
        assertTrue(track.report(TeachStep.OPEN_CONTROL_CENTER))
        assertTrue(track.report(TeachStep.HOLD_BATTERY))
        // 密码失败：回到按住电量。
        track.rewindTo(TeachStep.HOLD_BATTERY)
        assertEquals(TeachStep.HOLD_BATTERY, track.current)
        // 往前“回退”不会跳步。
        track.rewindTo(TeachStep.DRAWER_BACK_TO_FACE)
        assertEquals(TeachStep.HOLD_BATTERY, track.current)
        listOf(TeachStep.HOLD_BATTERY, TeachStep.DRAWER_SECRET, TeachStep.DRAWER_OPENED, TeachStep.DRAWER_BACK_TO_FACE)
            .forEach { assertTrue(track.report(it)) }
        assertTrue(track.completed)
    }

    @Test
    fun managementLessonEndsByOpeningFullSettings() {
        val track = TeachTrack(TeachLevel.MANAGEMENT)
        assertEquals(TeachStep.OPEN_FULL_SETTINGS, track.steps.last())
        assertFalse(TeachStep.DRAG_DOWN in track.steps)
    }

    @Test
    fun resetLessonUsesTwentyTapsButRealUseIsHundred() {
        assertEquals(20, TutorialRules.TEACH_RESET_TAPS)
        assertEquals(100, com.flue.launcher.surface.secret.SecretRules.RESET_TAPS)
        val counter = com.flue.launcher.surface.secret.ResetTapCounter(TutorialRules.TEACH_RESET_TAPS)
        repeat(19) { assertFalse(counter.tap()) }
        assertTrue(counter.tap())
        assertEquals(TeachStep.SET_PRACTICE_SECRET, TeachTrack(TeachLevel.RESET).steps.last())
    }

    // ---- 最终练习（R21）----

    private val minute = 60_000L

    @Test
    fun practicePassesWhenBothTasksDoneInOneRound() {
        val session = PracticeSession(0)
        assertEquals(PracticeSession.State.Running, session.complete(PracticeTask.FULL_SETTINGS, minute))
        assertEquals(PracticeSession.State.Running, session.complete(PracticeTask.FULL_SETTINGS, minute + 1))
        assertEquals(PracticeSession.State.Passed, session.complete(PracticeTask.HIDDEN_DRAWER, 2 * minute))
    }

    @Test
    fun practiceTimesOutAfterThreeMinutesPerTask() {
        val session = PracticeSession(0)
        assertEquals(PracticeSession.State.Running, session.tick(3 * minute - 1))
        assertEquals(PracticeSession.State.AskRelearn(PracticeSession.Reason.TIMEOUT), session.tick(3 * minute))
        // 超时后再完成也不算通过。
        assertEquals(
            PracticeSession.State.AskRelearn(PracticeSession.Reason.TIMEOUT),
            session.complete(PracticeTask.HIDDEN_DRAWER, 3 * minute + 1),
        )
    }

    @Test
    fun completingATaskRestartsTheTimer() {
        val session = PracticeSession(0)
        session.complete(PracticeTask.HIDDEN_DRAWER, 2 * minute)
        assertEquals(PracticeSession.State.Running, session.tick(4 * minute))
        assertEquals(PracticeSession.State.AskRelearn(PracticeSession.Reason.TIMEOUT), session.tick(5 * minute))
    }

    @Test
    fun pauseStopsTheClock() {
        val session = PracticeSession(0)
        session.pause(2 * minute)
        assertEquals(PracticeSession.State.Paused, session.tick(10 * minute))
        session.resume(10 * minute)
        assertEquals(PracticeSession.State.Running, session.tick(10 * minute + minute - 1))
        assertEquals(PracticeSession.State.AskRelearn(PracticeSession.Reason.TIMEOUT), session.tick(11 * minute))
    }

    @Test
    fun threeMissesAskToRelearn() {
        val session = PracticeSession(0)
        assertEquals(PracticeSession.State.Running, session.miss(1))
        assertEquals(PracticeSession.State.Running, session.miss(2))
        assertEquals(PracticeSession.State.AskRelearn(PracticeSession.Reason.MISSES), session.miss(3))
    }

    @Test
    fun cancelAsksImmediately() {
        val session = PracticeSession(0)
        assertEquals(PracticeSession.State.AskRelearn(PracticeSession.Reason.CANCELLED), session.cancel())
        val passed = PracticeSession(0)
        passed.complete(PracticeTask.HIDDEN_DRAWER, 1)
        passed.complete(PracticeTask.FULL_SETTINGS, 2)
        assertEquals(PracticeSession.State.Passed, passed.cancel())
    }

    // ---- 成绩版本与 ADB 跳过（R22）----

    @Test
    fun scoreIsVersioned() {
        assertTrue(TutorialRules.passes(TutorialRules.VERSION))
        assertFalse(TutorialRules.passes(0))
        assertFalse(TutorialRules.passes(TutorialRules.VERSION - 1))
        assertFalse(TutorialRules.passes(TutorialRules.VERSION + 1))
    }

    @Test
    fun adbCallerCheck() {
        assertTrue(AdbTutorialSkip.callerAllowed(-1))
        assertTrue(AdbTutorialSkip.callerAllowed(0))
        assertTrue(AdbTutorialSkip.callerAllowed(2000))
        assertFalse(AdbTutorialSkip.callerAllowed(10_123))
        assertFalse(AdbTutorialSkip.callerAllowed(1000))
    }

    @Test
    fun adbSkipOnlyRecordsTutorial() {
        val runtime = SurfaceRuntime.get(context)
        assertEquals(0, runtime.tutorialVersion.value)
        AdbTutorialSkipReceiver().onReceive(context, Intent("other.action"))
        assertEquals(0, runtime.tutorialVersion.value)
        AdbTutorialSkipReceiver().onReceive(context, Intent(AdbTutorialSkip.ACTION))
        assertEquals(TutorialRules.VERSION, runtime.tutorialVersion.value)
        assertEquals(TutorialRules.VERSION, SurfaceStateStore(context).tutorialVersion)
        // 不设置密码、不补基础配置。
        assertTrue(runtime.disguiseSetupDone().isEmpty())
        assertFalse(runtime.baseConfigured())
    }
}
