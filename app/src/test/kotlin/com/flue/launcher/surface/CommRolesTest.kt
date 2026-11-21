package com.flue.launcher.surface

import com.flue.launcher.surface.comm.CommRole
import com.flue.launcher.surface.comm.RestoreFacts
import com.flue.launcher.surface.comm.RestoreOutcome
import com.flue.launcher.surface.comm.RestoreProgress
import com.flue.launcher.surface.comm.RestoreProgressStorage
import com.flue.launcher.surface.comm.RestoreStep
import com.flue.launcher.surface.comm.RoleFacts
import com.flue.launcher.surface.comm.RoleLedger
import com.flue.launcher.surface.comm.RoleLedgerStorage
import com.flue.launcher.surface.comm.RoleReadback
import com.flue.launcher.surface.comm.RoleRecord
import com.flue.launcher.surface.comm.RoleRestoreFlow
import com.flue.launcher.surface.comm.RoleTarget
import com.flue.launcher.surface.comm.RoleTargets
import com.flue.launcher.surface.comm.TargetProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R32 通信角色：接管前记录原值、退出外部前恢复并读回确认。 */
class CommRolesTest {

    private val own = "com.gaozai.surface"
    private val dialer = "com.android.dialer"
    private val messaging = "com.google.android.apps.messaging"

    private class MemLedger : RoleLedgerStorage {
        val records = mutableMapOf<CommRole, RoleRecord>()
        override fun read(role: CommRole) = records[role] ?: RoleRecord.NONE
        override fun write(role: CommRole, record: RoleRecord): Boolean {
            records[role] = record
            return true
        }
    }

    private class MemProgress : RestoreProgressStorage {
        var value = RestoreProgress.IDLE
        override fun readProgress() = value
        override fun writeProgress(progress: RestoreProgress): Boolean {
            value = progress
            return true
        }
    }

    private val ledgerStorage = MemLedger()
    private val progressStorage = MemProgress()
    private val ledger = RoleLedger(ledgerStorage, own)
    private val flow = RoleRestoreFlow(progressStorage, ledger, own)

    private fun facts(
        dialerHolder: String? = own,
        smsHolder: String? = own,
        inCall: Boolean = false,
        dialers: List<String> = listOf(dialer, own),
        smsApps: List<String> = listOf(messaging, own),
    ) = RestoreFacts(
        roles = mapOf(
            CommRole.DIALER to RoleFacts(dialerHolder, dialers),
            CommRole.SMS to RoleFacts(smsHolder, smsApps),
        ),
        inCall = inCall,
    )

    // ---- 记录 ----

    @Test
    fun recordsOriginalOnlyOnceAndNeverSelf() {
        assertTrue(ledger.recordBeforeTakeover(CommRole.DIALER, dialer))
        assertEquals(RoleRecord(true, dialer), ledger.record(CommRole.DIALER))
        // 已记录：不覆盖。
        assertFalse(ledger.recordBeforeTakeover(CommRole.DIALER, "com.other.dialer"))
        assertEquals(dialer, ledger.record(CommRole.DIALER).original)
        // 本应用不能当原应用。
        assertFalse(ledger.recordBeforeTakeover(CommRole.SMS, own))
        assertFalse(ledger.record(CommRole.SMS).recorded)
    }

    @Test
    fun emptyHolderIsRecordedAsOriginalValue() {
        assertTrue(ledger.recordBeforeTakeover(CommRole.SMS, null))
        assertEquals(RoleRecord(recorded = true, original = null), ledger.record(CommRole.SMS))
        assertTrue(ledger.recordBeforeTakeover(CommRole.DIALER, " "))
        assertEquals(null, ledger.record(CommRole.DIALER).original)
    }

    @Test
    fun failedTakeoverDropsRecordMadeInThisAttemptOnly() {
        val now = ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        assertFalse(ledger.afterTakeover(CommRole.DIALER, now, dialer))
        assertFalse(ledger.record(CommRole.DIALER).recorded)

        // 早先记录过的原值不因这次取消而丢失。
        ledger.recordBeforeTakeover(CommRole.SMS, messaging)
        val again = ledger.recordBeforeTakeover(CommRole.SMS, "com.other.sms")
        assertFalse(ledger.afterTakeover(CommRole.SMS, again, "com.other.sms"))
        assertEquals(messaging, ledger.record(CommRole.SMS).original)

        val ok = ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        assertTrue(ledger.afterTakeover(CommRole.DIALER, ok, own))
        assertEquals(dialer, ledger.record(CommRole.DIALER).original)
    }

    // ---- 读回与目标 ----

    @Test
    fun onlyReadingBackTheTargetCountsAsRestored() {
        assertEquals(RestoreOutcome.RESTORED, RoleReadback.evaluate(dialer, dialer, own))
        assertEquals(RestoreOutcome.STILL_SELF, RoleReadback.evaluate(dialer, own, own))
        assertEquals(RestoreOutcome.EMPTY, RoleReadback.evaluate(dialer, null, own))
        assertEquals(RestoreOutcome.EMPTY, RoleReadback.evaluate(dialer, "", own))
        assertEquals(RestoreOutcome.OTHER, RoleReadback.evaluate(dialer, "com.other", own))
    }

    @Test
    fun candidatesExcludeSelfAndPutOriginalFirst() {
        assertEquals(
            listOf(dialer, "com.a", "com.b"),
            RoleTargets.candidates(listOf("com.a", own, dialer, "com.b", "com.a", ""), own, dialer),
        )
        assertEquals(listOf("com.a"), RoleTargets.candidates(listOf("com.a", own), own, dialer))
    }

    @Test
    fun resolveNeedsChoiceWhenOriginalUnusable() {
        val qualified = listOf(dialer, own, "com.b")
        assertEquals(RoleTarget.Ready(dialer, true), RoleTargets.resolve(RoleRecord(true, dialer), qualified, own, null))
        assertEquals(
            RoleTarget.NeedsChoice(TargetProblem.NOT_RECORDED, listOf(dialer, "com.b")),
            RoleTargets.resolve(RoleRecord.NONE, qualified, own, null),
        )
        assertEquals(
            RoleTarget.NeedsChoice(TargetProblem.WAS_EMPTY, listOf(dialer, "com.b")),
            RoleTargets.resolve(RoleRecord(true, null), qualified, own, null),
        )
        assertEquals(
            RoleTarget.NeedsChoice(TargetProblem.UNAVAILABLE, listOf("com.b")),
            RoleTargets.resolve(RoleRecord(true, "com.gone"), listOf(own, "com.b"), own, null),
        )
        // 另选：只能选候选里的，不能选本应用。
        assertEquals(RoleTarget.Ready("com.b", false), RoleTargets.resolve(RoleRecord(true, "com.gone"), qualified, own, "com.b"))
        assertTrue(RoleTargets.resolve(RoleRecord(true, "com.gone"), qualified, own, own) is RoleTarget.NeedsChoice)
    }

    // ---- 恢复流程 ----

    @Test
    fun notNeededWhenNotHoldingAnyRole() {
        assertFalse(flow.needed(facts(dialerHolder = dialer, smsHolder = messaging)))
        assertTrue(flow.needed(facts(dialerHolder = dialer, smsHolder = own)))
    }

    @Test
    fun restoresBothRolesWithReadback() {
        ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        ledger.recordBeforeTakeover(CommRole.SMS, messaging)
        assertEquals(setOf(CommRole.DIALER, CommRole.SMS), flow.begin(facts()).roles)

        assertEquals(RestoreStep.Ready(CommRole.DIALER, dialer, true), flow.step(facts()))
        flow.launched(CommRole.DIALER, dialer)
        assertEquals(RestoreStep.Ready(CommRole.SMS, messaging, true), flow.step(facts(dialerHolder = dialer)))
        assertFalse(ledgerStorage.read(CommRole.DIALER).recorded)

        flow.launched(CommRole.SMS, messaging)
        val finished = flow.step(facts(dialerHolder = dialer, smsHolder = messaging))
        assertEquals(RestoreStep.Finished(setOf(CommRole.DIALER, CommRole.SMS), emptySet()), finished)
        flow.complete()
        assertFalse(progressStorage.value.active)
    }

    @Test
    fun waitsDuringCallWithoutSwitching() {
        ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        flow.begin(facts(smsHolder = messaging))
        assertEquals(RestoreStep.WaitForCall(CommRole.DIALER), flow.step(facts(smsHolder = messaging, inCall = true)))
        assertEquals(null, progressStorage.value.awaiting)
        // 通话结束后继续。
        assertEquals(RestoreStep.Ready(CommRole.DIALER, dialer, true), flow.step(facts(smsHolder = messaging)))
    }

    @Test
    fun afterProcessDeathOnlyReadsBackAndNeverRelaunches() {
        ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        flow.begin(facts(smsHolder = messaging))
        flow.launched(CommRole.DIALER, dialer)

        // 进程被结束：新的流程对象读同一份持久进度。
        val rebuilt = RoleRestoreFlow(progressStorage, RoleLedger(ledgerStorage, own), own)
        assertTrue(rebuilt.needed(facts(dialerHolder = dialer, smsHolder = messaging)))
        assertEquals(setOf(CommRole.DIALER), rebuilt.begin(facts()).roles)
        // 用户在系统里取消了：读回仍是本应用 → 失败，等用户决定，不自动再申请。
        val step = rebuilt.step(facts(smsHolder = messaging))
        assertEquals(RestoreStep.Failed(CommRole.DIALER, RestoreOutcome.STILL_SELF, own, dialer), step)
        assertEquals(step, rebuilt.step(facts(smsHolder = messaging)))
        assertEquals(null, progressStorage.value.awaiting)
    }

    @Test
    fun emptyOrOtherReadbackIsNotSuccess() {
        ledger.recordBeforeTakeover(CommRole.SMS, messaging)
        flow.begin(facts(dialerHolder = dialer))
        flow.launched(CommRole.SMS, messaging)
        assertEquals(
            RestoreStep.Failed(CommRole.SMS, RestoreOutcome.EMPTY, null, messaging),
            flow.step(facts(dialerHolder = dialer, smsHolder = null)),
        )
        assertTrue(ledgerStorage.read(CommRole.SMS).recorded)

        flow.retry(CommRole.SMS)
        assertEquals(RestoreStep.Ready(CommRole.SMS, messaging, true), flow.step(facts(dialerHolder = dialer, smsHolder = null)))
        flow.launched(CommRole.SMS, messaging)
        assertEquals(
            RestoreStep.Failed(CommRole.SMS, RestoreOutcome.OTHER, "com.other.sms", messaging),
            flow.step(facts(dialerHolder = dialer, smsHolder = "com.other.sms")),
        )
        // 用户接受此刻的持有者。
        flow.acceptCurrent(CommRole.SMS)
        assertEquals(RestoreStep.Finished(setOf(CommRole.SMS), emptySet()), flow.step(facts(dialerHolder = dialer, smsHolder = "com.other.sms")))
        assertFalse(ledgerStorage.read(CommRole.SMS).recorded)
    }

    @Test
    fun failedThenFixedManuallyCountsAsRestored() {
        ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        flow.begin(facts(smsHolder = messaging))
        flow.launched(CommRole.DIALER, dialer)
        assertTrue(flow.step(facts(smsHolder = messaging)) is RestoreStep.Failed)
        // 用户自己去系统设置改好了。
        assertEquals(
            RestoreStep.Finished(setOf(CommRole.DIALER), emptySet()),
            flow.step(facts(dialerHolder = dialer, smsHolder = messaging)),
        )
    }

    @Test
    fun choosesCandidateWhenOriginalMissing() {
        // 接管前本应用已经是默认：没有记录。
        assertFalse(ledger.recordBeforeTakeover(CommRole.SMS, own))
        flow.begin(facts(dialerHolder = dialer))
        val step = flow.step(facts(dialerHolder = dialer))
        assertEquals(RestoreStep.Choose(CommRole.SMS, TargetProblem.NOT_RECORDED, listOf(messaging)), step)
        flow.choose(CommRole.SMS, messaging)
        assertEquals(RestoreStep.Ready(CommRole.SMS, messaging, false), flow.step(facts(dialerHolder = dialer)))
    }

    @Test
    fun skipKeepsRecordAndCancelClearsProgress() {
        ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        ledger.recordBeforeTakeover(CommRole.SMS, messaging)
        flow.begin(facts())
        flow.skip(CommRole.DIALER)
        assertEquals(RestoreStep.Ready(CommRole.SMS, messaging, true), flow.step(facts()))
        flow.skip(CommRole.SMS)
        assertEquals(RestoreStep.Finished(emptySet(), setOf(CommRole.DIALER, CommRole.SMS)), flow.step(facts()))
        assertTrue(ledgerStorage.read(CommRole.DIALER).recorded)

        flow.cancel()
        assertEquals(RestoreProgress.IDLE, progressStorage.value)
        // 取消后仍持有：下次退出还会进入恢复。
        assertTrue(flow.needed(facts()))
    }

    @Test
    fun userChangeBeforeAttemptIsNotOverwritten() {
        ledger.recordBeforeTakeover(CommRole.DIALER, dialer)
        ledger.recordBeforeTakeover(CommRole.SMS, messaging)
        flow.begin(facts())
        // 开始后用户自己把电话换成了别的应用：不按旧记录覆盖。
        assertEquals(RestoreStep.Ready(CommRole.SMS, messaging, true), flow.step(facts(dialerHolder = "com.user.choice")))
        assertTrue(CommRole.DIALER in progressStorage.value.done)
    }
}
