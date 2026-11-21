package com.flue.launcher.surface

import com.flue.launcher.surface.access.FeatureAccessPolicy
import com.flue.launcher.surface.access.AccessDecision
import com.flue.launcher.surface.access.SurfaceActivityGuard
import com.flue.launcher.surface.apps.SelfModules
import com.flue.launcher.surface.comm.CallPhase
import com.flue.launcher.surface.comm.CallRules
import com.flue.launcher.surface.comm.CommBlock
import com.flue.launcher.surface.comm.CommCapability
import com.flue.launcher.surface.comm.CommEnvironment
import com.flue.launcher.surface.comm.CommLockExemption
import com.flue.launcher.surface.comm.CommPermission
import com.flue.launcher.surface.comm.CommRole
import com.flue.launcher.surface.comm.DialRoute
import com.flue.launcher.surface.comm.DialRules
import com.flue.launcher.surface.comm.RootRoleCommands
import com.flue.launcher.surface.comm.SmsFailure
import com.flue.launcher.surface.comm.SmsRules
import com.flue.launcher.surface.comm.SmsSendState
import com.flue.launcher.surface.comm.SmsSendTracker
import com.flue.launcher.surface.mode.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R32 电话、短信的纯规则：能力降级、拨号路由、通话阶段、短信分段与回执、Root 命令。 */
class CommRulesTest {

    private val all = CommPermission.entries.toSet()
    private val full = CommEnvironment(
        hasTelephony = true, simReady = true, dialerRoleAvailable = true, smsRoleAvailable = true,
        isDefaultDialer = true, isDefaultSms = true, granted = all,
    )

    // ---- 能力降级 ----

    @Test
    fun everythingAvailableWithFullEnvironment() {
        assertNull(CommCapability.dial(full))
        assertNull(CommCapability.inCallUi(full))
        assertNull(CommCapability.callLog(full))
        assertNull(CommCapability.smsRead(full))
        assertNull(CommCapability.smsSend(full))
        assertNull(CommCapability.smsReceive(full))
        assertTrue(CommCapability.writesSmsProvider(full))
    }

    @Test
    fun missingHardwareSimRoleOrPermissionDisablesOwnFeature() {
        val noHw = full.copy(hasTelephony = false)
        listOf(CommCapability::dial, CommCapability::inCallUi, CommCapability::callLog, CommCapability::smsRead, CommCapability::smsSend, CommCapability::smsReceive)
            .forEach { assertEquals(CommBlock.NO_HARDWARE, it(noHw)) }

        val noSim = full.copy(simReady = false)
        assertEquals(CommBlock.NO_SIM, CommCapability.dial(noSim))
        assertEquals(CommBlock.NO_SIM, CommCapability.smsSend(noSim))
        // 无 SIM 也能看已有短信和通话记录。
        assertNull(CommCapability.smsRead(noSim))
        assertNull(CommCapability.callLog(noSim))

        assertEquals(CommBlock.ROLE_UNAVAILABLE, CommCapability.inCallUi(full.copy(dialerRoleAvailable = false)))
        assertEquals(CommBlock.NOT_DEFAULT, CommCapability.inCallUi(full.copy(isDefaultDialer = false)))
        assertEquals(CommBlock.NOT_DEFAULT, CommCapability.smsReceive(full.copy(isDefaultSms = false)))
        assertFalse(CommCapability.writesSmsProvider(full.copy(isDefaultSms = false)))

        assertEquals(CommBlock.PERMISSION_MISSING, CommCapability.dial(full.copy(granted = all - CommPermission.CALL_PHONE)))
        assertEquals(CommBlock.PERMISSION_MISSING, CommCapability.callLog(full.copy(granted = all - CommPermission.READ_CALL_LOG)))
        assertEquals(CommBlock.PERMISSION_MISSING, CommCapability.smsRead(full.copy(granted = all - CommPermission.READ_SMS)))
        assertEquals(CommBlock.PERMISSION_MISSING, CommCapability.smsSend(full.copy(granted = all - CommPermission.SEND_SMS)))
    }

    // ---- 拨号 ----

    @Test
    fun normalizeAndValidateNumbers() {
        assertEquals("+8613800138000", DialRules.normalize("+86 138-0013 (8000)"))
        assertTrue(DialRules.isDialable("10086,1#"))
        assertTrue(DialRules.isDialable("*#06#"))
        assertFalse(DialRules.isDialable(""))
        assertFalse(DialRules.isDialable("abc"))
        assertFalse(DialRules.isDialable("1+1"))
    }

    @Test
    fun emergencyNumbersAlwaysReachSystem() {
        val noSim = full.copy(simReady = false)
        assertEquals(DialRoute.PLACE_CALL, DialRules.route("112", isEmergency = true, env = noSim))
        assertEquals(DialRoute.SYSTEM_DIALER, DialRules.route("112", isEmergency = true, env = noSim.copy(isDefaultDialer = false)))
        assertEquals(DialRoute.SYSTEM_DIALER, DialRules.route("911", isEmergency = true, env = full.copy(granted = emptySet())))
        assertEquals(DialRoute.SYSTEM_DIALER, DialRules.route("911", isEmergency = true, env = full.copy(hasTelephony = false, isDefaultDialer = false)))
    }

    @Test
    fun normalNumbersFallBackToSystemDialer() {
        assertEquals(DialRoute.PLACE_CALL, DialRules.route("10086", isEmergency = false, env = full))
        assertEquals(DialRoute.SYSTEM_DIALER, DialRules.route("10086", false, full.copy(simReady = false)))
        assertEquals(DialRoute.SYSTEM_DIALER, DialRules.route("10086", false, full.copy(granted = emptySet())))
        assertEquals(DialRoute.INVALID, DialRules.route("", false, full))
        assertEquals(DialRoute.INVALID, DialRules.route("hello", true, full))
    }

    // ---- 通话 ----

    @Test
    fun callStatesMapToPhasesAndUnknownIsLive() {
        assertEquals(CallPhase.RINGING, CallRules.phaseOf(CallRules.STATE_RINGING))
        assertEquals(CallPhase.RINGING, CallRules.phaseOf(CallRules.STATE_SIMULATED_RINGING))
        assertEquals(CallPhase.ACTIVE, CallRules.phaseOf(CallRules.STATE_ACTIVE))
        assertEquals(CallPhase.HOLDING, CallRules.phaseOf(CallRules.STATE_HOLDING))
        assertEquals(CallPhase.DIALING, CallRules.phaseOf(CallRules.STATE_DIALING))
        assertEquals(CallPhase.CONNECTING, CallRules.phaseOf(CallRules.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(CallPhase.DISCONNECTED, CallRules.phaseOf(CallRules.STATE_DISCONNECTED))
        assertEquals(CallPhase.CONNECTING, CallRules.phaseOf(99))
        assertTrue(CallRules.phaseOf(99).live)
        assertTrue(CallPhase.DISCONNECTING.live)
        assertFalse(CallPhase.DISCONNECTED.live)
    }

    @Test
    fun actionsFollowPhase() {
        val ringing = CallRules.actionsOf(CallPhase.RINGING, canHold = true)
        assertTrue(ringing.answer && ringing.reject)
        assertFalse(ringing.hangUp || ringing.hold)
        val active = CallRules.actionsOf(CallPhase.ACTIVE, canHold = false)
        assertTrue(active.hangUp && active.mute && active.speaker && active.keypad)
        assertFalse(active.hold || active.answer)
        assertTrue(CallRules.actionsOf(CallPhase.ACTIVE, canHold = true).hold)
        val holding = CallRules.actionsOf(CallPhase.HOLDING, canHold = true)
        assertTrue(holding.unhold && holding.hangUp)
        assertEquals(com.flue.launcher.surface.comm.CallActions(), CallRules.actionsOf(CallPhase.DISCONNECTED, true))
    }

    @Test
    fun ringingCallTakesPriorityAndDurationFormats() {
        assertEquals(1, CallRules.primaryIndex(listOf(CallPhase.ACTIVE, CallPhase.RINGING)))
        assertEquals(1, CallRules.primaryIndex(listOf(CallPhase.HOLDING, CallPhase.ACTIVE)))
        assertEquals(-1, CallRules.primaryIndex(emptyList()))
        assertEquals("00:00", CallRules.formatDuration(-5))
        assertEquals("01:05", CallRules.formatDuration(65_000))
        assertEquals("1:00:01", CallRules.formatDuration(3_601_000))
        assertTrue(CallRules.isDtmf('#'))
        assertFalse(CallRules.isDtmf('+'))
    }

    // ---- 短信 ----

    @Test
    fun segmentEstimateUsesGsmOrUcs2() {
        assertEquals(0, SmsRules.estimateSegments(""))
        assertEquals(1, SmsRules.estimateSegments("a".repeat(160)))
        assertEquals(2, SmsRules.estimateSegments("a".repeat(161)))
        assertEquals(3, SmsRules.estimateSegments("a".repeat(307)))
        // 扩展字符占两个位。
        assertEquals(2, SmsRules.estimateSegments("€".repeat(81)))
        assertEquals(1, SmsRules.estimateSegments("你".repeat(70)))
        assertEquals(2, SmsRules.estimateSegments("你".repeat(71)))
        assertEquals(3, SmsRules.estimateSegments("你".repeat(135)))
    }

    @Test
    fun recipientsFromSmsUri() {
        assertEquals(listOf("10086", "+8613800138000"), SmsRules.recipients("10086;+86 138 0013 8000,10086?body=hi"))
        assertEquals(emptyList<String>(), SmsRules.recipients(null))
        assertEquals(emptyList<String>(), SmsRules.recipients("?body=hi"))
    }

    @Test
    fun multipartIncomingIsCombinedPerSender() {
        val merged = SmsRules.combine(listOf("10086" to "Hel", "10010" to "Yo", "10086" to "lo", null to "x", "10010" to null))
        assertEquals(listOf("10086" to "Hello", "10010" to "Yo"), merged)
    }

    @Test
    fun multipartSendIsSentOnlyWhenEveryPartSucceeds() {
        val tracker = SmsSendTracker(3)
        assertEquals(SmsSendState.SENDING, tracker.report(0, SmsRules.RESULT_OK))
        assertEquals(SmsSendState.SENDING, tracker.report(2, SmsRules.RESULT_OK))
        assertEquals(SmsSendState.SENT, tracker.report(1, SmsRules.RESULT_OK))

        val failing = SmsSendTracker(2)
        assertEquals(SmsSendState.FAILED, failing.report(1, 4))
        // 同一段重复回执以第一次为准。
        assertEquals(SmsSendState.FAILED, failing.report(1, SmsRules.RESULT_OK))
        assertEquals(SmsSendState.FAILED, failing.report(0, SmsRules.RESULT_OK))
        assertEquals(SmsFailure.NO_SERVICE, failing.failure)
        // 越界段号忽略。
        assertEquals(SmsSendState.SENDING, SmsSendTracker(1).report(5, SmsRules.RESULT_OK))
    }

    @Test
    fun sendTrackerSurvivesSerialization() {
        val tracker = SmsSendTracker(3)
        tracker.report(0, SmsRules.RESULT_OK)
        tracker.report(2, 2)
        val raw = tracker.serialize()
        assertEquals("3|-1,_,2", raw)
        val parsed = SmsSendTracker.parse(raw)!!
        assertEquals(3, parsed.parts)
        assertEquals(SmsSendState.FAILED, parsed.state)
        assertEquals(SmsFailure.RADIO_OFF, parsed.failure)
        assertNull(SmsSendTracker.parse(null))
        assertNull(SmsSendTracker.parse("0|"))
        assertNull(SmsSendTracker.parse("x|1"))
        assertEquals(SmsSendState.SENDING, SmsSendTracker.parse("2|_,_")!!.state)
        assertNull(SmsRules.failureOf(SmsRules.RESULT_OK))
        assertEquals(SmsFailure.OTHER, SmsRules.failureOf(42))
    }

    // ---- Root 恢复命令 ----

    @Test
    fun rootCommandsOnlyForSafePackageNames() {
        assertEquals(
            listOf("cmd role add-role-holder --user 0 android.app.role.DIALER com.android.dialer"),
            RootRoleCommands.commands(CommRole.DIALER, "com.android.dialer", 30),
        )
        assertEquals(listOf("telecom set-default-dialer com.android.dialer"), RootRoleCommands.commands(CommRole.DIALER, "com.android.dialer", 28))
        assertEquals(
            listOf("settings put secure sms_default_application com.android.mms"),
            RootRoleCommands.commands(CommRole.SMS, "com.android.mms", 28),
        )
        for (bad in listOf("", "com", "com.a; reboot", "com.a && rm -rf /", "com.a\nid", "1com.a", "com.a b", "\$(id).x")) {
            assertFalse(bad, RootRoleCommands.isSafePackage(bad))
            assertTrue(bad, RootRoleCommands.commands(CommRole.SMS, bad, 30).isEmpty())
        }
    }

    // ---- 桌面锁豁免、访问规则、内置模块 ----

    @Test
    fun onlyInCallScreenIsExemptFromDesktopLock() {
        assertTrue(CommLockExemption.isExempt("com.flue.launcher.surface.comm.InCallActivity"))
        assertFalse(CommLockExemption.isExempt("com.flue.launcher.surface.comm.PhoneActivity"))
        assertFalse(CommLockExemption.isExempt("com.flue.launcher.surface.comm.MessagesActivity"))
        for (name in CommLockExemption.EXEMPT_ACTIVITIES) Class.forName(name, false, javaClass.classLoader)
    }

    @Test
    fun phoneAndMessagesOpenInBothModes() {
        for (name in listOf("com.flue.launcher.surface.comm.PhoneActivity", "com.flue.launcher.surface.comm.MessagesActivity")) {
            val feature = SurfaceActivityGuard.featureOf(name)!!
            for (mode in SurfaceMode.entries) {
                assertEquals("$name/$mode", AccessDecision.ALLOWED, FeatureAccessPolicy.decide(mode, feature))
            }
        }
        // 通话界面不受功能守卫约束：任何模式都不能拦来电。
        assertNull(SurfaceActivityGuard.featureOf("com.flue.launcher.surface.comm.InCallActivity"))
    }

    @Test
    fun ownLauncherActivitiesExceptHostAreModules() {
        val own = "com.gaozai.surface"
        assertTrue(SelfModules.isModule(own, "com.flue.launcher.surface.comm.PhoneActivity", own))
        assertTrue(SelfModules.isModule(own, "com.flue.launcher.surface.comm.MessagesActivity", own))
        assertFalse(SelfModules.isModule(own, "com.flue.launcher.LauncherActivity", own))
        assertFalse(SelfModules.isModule(own, "com.flue.launcher.SettingsActivity", own))
        assertFalse(SelfModules.isModule(own, "", own))
        assertFalse(SelfModules.isModule("com.other", "com.flue.launcher.surface.comm.PhoneActivity", own))
    }
}
