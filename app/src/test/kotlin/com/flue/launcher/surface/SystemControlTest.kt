package com.flue.launcher.surface

import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.system.CallState
import com.flue.launcher.surface.system.CallStateParser
import com.flue.launcher.surface.system.GuardConnection
import com.flue.launcher.surface.system.GuardProtocol
import com.flue.launcher.surface.system.HandoffTracker
import com.flue.launcher.surface.system.NavFacts
import com.flue.launcher.surface.system.NavGuardStatus
import com.flue.launcher.surface.system.NavPolicy
import com.flue.launcher.surface.system.NavRestriction
import com.flue.launcher.surface.system.NavigationGuard
import com.flue.launcher.surface.system.RecoveryBudget
import com.flue.launcher.surface.system.RecoveryRecord
import com.flue.launcher.surface.system.StatusBarFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5：R29 决策表、设备页放行、恢复预算、协议与读回判定、守护连接复用。 */
class SystemControlTest {

    private fun facts(mode: SurfaceMode, inCall: Boolean = false, handoff: Boolean = false) = NavFacts(mode, inCall, handoff)

    // ---- R29 决策表 ----

    @Test
    fun disguiseOwnPagesThirdPartyAndTempSettingsAreRestricted() {
        // 前三行都在伪装模式里：自有页面、普通第三方应用、临时完整设置结果相同。
        assertEquals(NavRestriction.DISGUISE, NavPolicy.decide(facts(SurfaceMode.DISGUISE)))
        assertTrue(NavRestriction.DISGUISE.blockShade && NavRestriction.DISGUISE.blockRecents)
    }

    @Test
    fun callAndDeviceHandoffReleaseTemporarily() {
        assertEquals(NavRestriction.NONE, NavPolicy.decide(facts(SurfaceMode.DISGUISE, inCall = true)))
        assertEquals(NavRestriction.NONE, NavPolicy.decide(facts(SurfaceMode.DISGUISE, handoff = true)))
    }

    @Test
    fun fullAndExternalAreNeverRestricted() {
        for (mode in listOf(SurfaceMode.FULL, SurfaceMode.EXTERNAL)) {
            assertEquals(NavRestriction.NONE, NavPolicy.decide(facts(mode)))
            assertFalse(NavPolicy.hideOwnStatusBar(mode))
        }
        assertTrue(NavPolicy.hideOwnStatusBar(SurfaceMode.DISGUISE))
    }

    @Test
    fun flagsNeverTouchHomeOrBack() {
        val (d1, d2) = StatusBarFlags.of(NavRestriction.DISGUISE)
        assertEquals(0x01010000, d1)
        assertEquals(0x5, d2)
        // DISABLE_HOME 0x00200000、DISABLE_BACK 0x00400000 不在其中。
        assertEquals(0, d1 and 0x00200000)
        assertEquals(0, d1 and 0x00400000)
        assertEquals(0 to 0, StatusBarFlags.of(NavRestriction.NONE))
    }

    @Test
    fun readBackDecidesWhetherItIsActive() {
        val dump = "  mDisabled1=0x1010000  mDisabled2=0x5\n"
        val actual = StatusBarFlags.parseDumpsys(dump)
        assertEquals(0x1010000 to 0x5, actual)
        assertTrue(StatusBarFlags.covers(actual!!, StatusBarFlags.of(NavRestriction.DISGUISE)))
        // 别的调用方多禁用的位不影响判断；少了我们要的位就不算生效。
        assertTrue(StatusBarFlags.covers(0x1210000 to 0x7, StatusBarFlags.of(NavRestriction.DISGUISE)))
        assertFalse(StatusBarFlags.covers(0x10000 to 0x5, StatusBarFlags.of(NavRestriction.DISGUISE)))
        assertNull(StatusBarFlags.parseDumpsys("no such fields"))
    }

    // ---- 设备页放行（R27）----

    @Test
    fun handoffEndsOnce() {
        val tracker = HandoffTracker()
        assertFalse(tracker.end())
        tracker.begin()
        assertTrue(tracker.active)
        assertTrue(tracker.end())
        assertFalse(tracker.active)
        assertFalse(tracker.end())
    }

    // ---- 恢复预算（R30）----

    @Test
    fun budgetAllowsThreeRestarts() {
        var budget = RecoveryBudget()
        repeat(3) {
            assertFalse(budget.exhausted)
            budget = budget.consume(1_000L * it)
        }
        assertTrue(budget.exhausted)
    }

    @Test
    fun budgetSurvivesDaemonHandover() {
        // 新守护从文件继承次数，不会因为恢复进程重建而重新开始。
        val handedOver = RecoveryBudget.parse(RecoveryBudget().consume(5_000).consume(20_000).serialize())
        assertEquals(2, handedOver.attempts)
        assertEquals(handedOver, handedOver.settle(20_000 + RecoveryBudget.STABLE_RESET_MS - 1))
        assertEquals(RecoveryBudget(), handedOver.settle(20_000 + RecoveryBudget.STABLE_RESET_MS))
        // 时间倒退说明机器重启过。
        assertEquals(RecoveryBudget(), handedOver.settle(1_000))
        assertEquals(RecoveryBudget(), RecoveryBudget.parse("garbage"))
        assertEquals(3, RecoveryBudget.parse("99 1").attempts)
    }

    @Test
    fun callStateUnknownIsNotIdle() {
        assertEquals(CallState.IDLE, CallStateParser.parse("mCallState=0\n  mCallState=0"))
        assertEquals(CallState.ACTIVE, CallStateParser.parse("mCallState=0\n  mCallState=2"))
        assertEquals(CallState.UNKNOWN, CallStateParser.parse(""))
    }

    @Test
    fun recoveryRecordsRoundTrip() {
        val records = listOf(
            RecoveryRecord(1_700_000_000_000, "budget-exhausted", 3, true),
            RecoveryRecord(1_700_000_100_000, "no-takeover", 1, false),
        )
        val text = records.joinToString("\n") { it.serialize() } + "\nbroken line\n"
        assertEquals(records, RecoveryRecord.parseAll(text))
        assertTrue(RecoveryRecord.parseAll(null).isEmpty())
    }

    @Test
    fun protocolRoundTrip() {
        val commands = listOf(
            GuardProtocol.Command.Hello(1),
            GuardProtocol.Command.Set(0x1010000, 5),
            GuardProtocol.Command.Fallback("com.example/.Home"),
            GuardProtocol.Command.Fallback(null),
            GuardProtocol.Command.Quit,
            GuardProtocol.Command.Ping,
        )
        commands.forEach { assertEquals(it, GuardProtocol.parse(GuardProtocol.format(it))) }
        val replies = listOf(
            GuardProtocol.Reply.Ready(1, 4242),
            GuardProtocol.Reply.Applied(16842752, 5),
            GuardProtocol.Reply.Unverified(-1, -1),
            GuardProtocol.Reply.Failed("SecurityException"),
            GuardProtocol.Reply.Pong,
        )
        replies.forEach { assertEquals(it, GuardProtocol.parseReply(GuardProtocol.formatReply(it))) }
        assertNull(GuardProtocol.parse("SET x"))
        assertNull(GuardProtocol.parseReply("Magisk: granted"))
    }

    // ---- 守护连接 ----

    private class FakeConnection(
        var openError: String? = null,
        var setReply: (GuardProtocol.Command.Set) -> GuardProtocol.Reply? = { GuardProtocol.Reply.Applied(it.d1, it.d2) },
    ) : GuardConnection {
        override var alive = false
        val sent = mutableListOf<GuardProtocol.Command>()
        var quits = 0

        override fun open(): String? {
            alive = openError == null
            return openError
        }

        override fun send(command: GuardProtocol.Command): GuardProtocol.Reply? {
            sent += command
            return when (command) {
                is GuardProtocol.Command.Set -> setReply(command).also { if (it == null) alive = false }
                else -> GuardProtocol.Reply.Pong
            }
        }

        override fun quit() {
            quits++
            alive = false
        }
    }

    private class Harness(var root: Boolean = true, val factory: () -> FakeConnection = { FakeConnection() }) {
        val connections = mutableListOf<FakeConnection>()
        val guard = NavigationGuard(
            connect = { factory().also { connections += it } },
            rootConfirmed = { root },
            fallbackHome = { "com.example/.Home" },
        )
    }

    @Test
    fun connectionIsReusedAcrossFactChanges() {
        val h = Harness()
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(NavGuardStatus.Active, h.guard.status.value)
        h.guard.update(facts(SurfaceMode.DISGUISE, handoff = true))
        assertEquals(NavGuardStatus.Released, h.guard.status.value)
        h.guard.update(facts(SurfaceMode.DISGUISE))
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(1, h.guard.opens)
        val sets = h.connections.single().sent.filterIsInstance<GuardProtocol.Command.Set>()
        // 相同事实不重复下发。
        assertEquals(listOf(0x1010000 to 5, 0 to 0, 0x1010000 to 5), sets.map { it.d1 to it.d2 })
        assertEquals(GuardProtocol.Command.Fallback("com.example/.Home"), h.connections.single().sent.first())
    }

    @Test
    fun leavingDisguiseQuitsSoTheDaemonDoesNotRecover() {
        val h = Harness()
        h.guard.update(facts(SurfaceMode.DISGUISE))
        h.guard.update(facts(SurfaceMode.FULL))
        assertEquals(1, h.connections.single().quits)
        assertEquals(NavGuardStatus.NotNeeded, h.guard.status.value)
    }

    @Test
    fun withoutConfirmedRootNothingIsRequested() {
        val h = Harness(root = false)
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(0, h.guard.opens)
        assertEquals(NavGuardStatus.Unavailable(NavGuardStatus.Reason.NO_ROOT), h.guard.status.value)
    }

    @Test
    fun releasedStateDoesNotStartTheDaemon() {
        val h = Harness()
        h.guard.update(facts(SurfaceMode.DISGUISE, inCall = true))
        assertEquals(0, h.guard.opens)
        assertEquals(NavGuardStatus.Released, h.guard.status.value)
    }

    @Test
    fun startFailureIsNotRetriedUntilUserAsks() {
        val h = Harness(factory = { FakeConnection(openError = "denied") })
        h.guard.update(facts(SurfaceMode.DISGUISE))
        h.guard.update(facts(SurfaceMode.DISGUISE, handoff = true))
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(1, h.guard.opens)
        assertEquals(NavGuardStatus.Unavailable(NavGuardStatus.Reason.START_FAILED, "denied"), h.guard.status.value)
        h.guard.retry()
        assertEquals(2, h.guard.opens)
        // 重新进入伪装也可以再试一次。
        h.guard.update(facts(SurfaceMode.FULL))
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(3, h.guard.opens)
    }

    @Test
    fun unverifiedIsNotReportedAsActive() {
        val h = Harness(factory = { FakeConnection(setReply = { GuardProtocol.Reply.Unverified(0, 0) }) })
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(NavGuardStatus.Unverified, h.guard.status.value)
    }

    @Test
    fun lostConnectionReconnectsOnceThenStops() {
        val h = Harness(factory = { FakeConnection(setReply = { null }) })
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(NavGuardStatus.Unavailable(NavGuardStatus.Reason.CONNECTION_LOST), h.guard.status.value)
        h.guard.update(facts(SurfaceMode.DISGUISE, handoff = true))
        h.guard.update(facts(SurfaceMode.DISGUISE))
        h.guard.update(facts(SurfaceMode.DISGUISE, handoff = true))
        h.guard.update(facts(SurfaceMode.DISGUISE))
        assertEquals(2, h.guard.opens)
    }
}
