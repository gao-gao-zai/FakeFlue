package com.flue.launcher.surface

import android.content.Context
import android.content.Intent
import com.flue.launcher.surface.entry.KeyPattern
import com.flue.launcher.surface.entry.KeyPatternDetector
import com.flue.launcher.surface.entry.QuickEntryActivity
import com.flue.launcher.surface.lock.DesktopLock
import com.flue.launcher.surface.lock.DesktopLockStore
import com.flue.launcher.surface.lock.DesktopPinRules
import com.flue.launcher.surface.lock.LockoutState
import com.flue.launcher.surface.lock.PinEntry
import com.flue.launcher.surface.lock.PinRead
import com.flue.launcher.surface.lock.PinSetup
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.secret.SecretCipher
import com.flue.launcher.surface.setup.DisguiseSetupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P6：R33 桌面进入密码、R34 快速进入。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LockQuickEntryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = encrypt(blob)
    }

    private object BrokenCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(blob: ByteArray): ByteArray = error("key gone")
    }

    private fun store(cipher: SecretCipher = XorCipher) =
        DesktopLockStore(context.getSharedPreferences("lock_test", Context.MODE_PRIVATE), cipher)

    private val pin = listOf(0, 1, 2, 9, 8, 7)

    // ---- 规则 ----

    @Test
    fun pinIsSixDigitsZeroToNine() {
        assertTrue(DesktopPinRules.isValid(pin))
        assertFalse(DesktopPinRules.isValid(listOf(1, 2, 3, 4, 5)))
        assertFalse(DesktopPinRules.isValid(listOf(1, 2, 3, 4, 5, 10)))
        assertEquals(pin, DesktopPinRules.decode(DesktopPinRules.encode(pin)))
    }

    @Test
    fun fiveWrongInARowCoolsDownThirtySeconds() {
        var state = LockoutState()
        repeat(4) { state = state.fail(1_000) }
        assertEquals(1, state.triesLeft)
        assertFalse(state.lockedOut(1_000))
        state = state.fail(1_000)
        assertTrue(state.lockedOut(1_000))
        assertEquals(30_000, state.remainingMs(1_000))
        // 冷却期间不计数。
        assertEquals(state, state.fail(20_000))
        assertFalse(state.lockedOut(31_000))
        assertEquals(5, state.triesLeft)
        // 剩余超过 30 秒说明机器重启过。
        assertFalse(state.normalize(0).lockedOut(0))
    }

    @Test
    fun entryComparesOnlyWhenFull() {
        val entry = PinEntry()
        pin.dropLast(1).forEach { assertNull(entry.press(it)) }
        entry.delete()
        assertEquals(4, entry.entered)
        assertNull(entry.press(8))
        assertEquals(listOf(0, 1, 2, 9, 8, 7), entry.press(7))
        assertEquals(0, entry.entered)
    }

    @Test
    fun setupNeedsTwoMatchingEntries() {
        val setup = PinSetup()
        assertEquals(PinSetup.Step.Confirm, setup.submit(pin))
        assertEquals(PinSetup.Step.Mismatch, setup.submit(listOf(1, 1, 1, 1, 1, 1)))
        assertEquals(PinSetup.Step.Confirm, setup.submit(pin))
        assertEquals(PinSetup.Step.Done(pin), setup.submit(pin))
    }

    // ---- 存储 ----

    @Test
    fun storeSavesSeparatelyAndDisables() {
        val s = store()
        assertEquals(PinRead.NotSet, s.read())
        assertTrue(s.save(pin))
        assertEquals(PinRead.Available(pin), s.read())
        assertFalse(s.save(listOf(1, 2)))
        assertEquals(PinRead.Available(pin), s.read())
        assertTrue(s.disable())
        assertEquals(PinRead.NotSet, s.read())
        // 和五区域隐藏密码不是同一个文件。
        assertFalse(DesktopLockStore.FILE_NAME == com.flue.launcher.surface.secret.SecretStore.FILE_NAME)
    }

    @Test
    fun brokenKeyIsUnreadableNotUnset() {
        assertTrue(store().save(pin))
        assertEquals(PinRead.Unreadable, store(BrokenCipher).read())
        assertTrue(store(BrokenCipher).enabled)
    }

    // ---- 锁状态 ----

    private class Clock(var now: Long = 100_000)

    private fun lock(mode: MutableStateFlow<SurfaceMode>, s: DesktopLockStore, clock: Clock = Clock()) =
        DesktopLock({ s }, mode, CoroutineScope(Dispatchers.Unconfined), clock = { clock.now })

    @Test
    fun locksOnlyInDisguiseWhenEnabled() {
        val s = store().apply { save(pin) }
        val mode = MutableStateFlow(SurfaceMode.FULL)
        val lock = lock(mode, s)
        assertFalse(lock.locked.value)
        mode.value = SurfaceMode.DISGUISE
        assertTrue(lock.locked.value)
        assertEquals(DesktopLock.Result.Unlocked, lock.tryUnlock(pin))
        assertFalse(lock.locked.value)
        lock.onScreenOff()
        assertTrue(lock.locked.value)
        mode.value = SurfaceMode.FULL
        assertFalse(lock.locked.value)
        lock.onScreenOff()
        assertFalse(lock.locked.value)
    }

    @Test
    fun processRestartInDisguiseStartsLocked() {
        val s = store().apply { save(pin) }
        assertTrue(lock(MutableStateFlow(SurfaceMode.DISGUISE), s).locked.value)
        s.disable()
        assertFalse(lock(MutableStateFlow(SurfaceMode.DISGUISE), s).locked.value)
    }

    @Test
    fun wrongEntriesStayLockedAndCoolDownSurvivesRestart() {
        val s = store().apply { save(pin) }
        val clock = Clock()
        val mode = MutableStateFlow(SurfaceMode.DISGUISE)
        val lock = lock(mode, s, clock)
        val wrong = listOf(5, 5, 5, 5, 5, 5)
        repeat(4) { assertEquals(DesktopLock.Result.Wrong(4 - it), lock.tryUnlock(wrong)) }
        assertEquals(DesktopLock.Result.Cooling(30_000), lock.tryUnlock(wrong))
        assertTrue(lock.locked.value)
        // 冷却中正确密码也不放行；进程重建后冷却还在。
        clock.now += 10_000
        val rebuilt = lock(mode, s, clock)
        assertEquals(DesktopLock.Result.Cooling(20_000), rebuilt.tryUnlock(pin))
        clock.now += 20_000
        assertEquals(DesktopLock.Result.Unlocked, rebuilt.tryUnlock(pin))
    }

    @Test
    fun managementResetSetsNewPinAndUnlocks() {
        val s = store().apply { save(pin) }
        val mode = MutableStateFlow(SurfaceMode.DISGUISE)
        val broken = DesktopLock({ store(BrokenCipher) }, mode, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(DesktopLock.Result.Unreadable, broken.tryUnlock(pin))
        val lock = lock(mode, s)
        assertFalse(lock.resetWithManagement(listOf(1)))
        assertTrue(lock.locked.value)
        val fresh = listOf(4, 4, 3, 3, 2, 2)
        assertTrue(lock.resetWithManagement(fresh))
        assertFalse(lock.locked.value)
        assertEquals(PinRead.Available(fresh), s.read())
    }

    // ---- 实体按键识别（R34）----

    private fun detect(vararg presses: Pair<Long, Long>): List<KeyPattern> {
        val detector = KeyPatternDetector()
        val out = mutableListOf<KeyPattern>()
        val events = presses.flatMap { (down, up) -> listOf(down to true, up to false) }
        var t = 0L
        fun runTimers(until: Long) {
            while (true) {
                val due = detector.deadline ?: return
                if (due > until) return
                detector.onTimer(due)?.let(out::add)
            }
        }
        for ((at, isDown) in events) {
            runTimers(at - 1)
            t = at
            (if (isDown) detector.onDown(at) else detector.onUp(at))?.let(out::add)
        }
        runTimers(t + 10_000)
        return out
    }

    @Test
    fun recognisesAllSixPatterns() {
        assertEquals(listOf(KeyPattern.SINGLE), detect(0L to 80L))
        assertEquals(listOf(KeyPattern.DOUBLE), detect(0L to 80L, 200L to 280L))
        assertEquals(listOf(KeyPattern.TRIPLE), detect(0L to 80L, 200L to 280L, 400L to 480L))
        assertEquals(listOf(KeyPattern.LONG), detect(0L to 1_500L))
        assertEquals(listOf(KeyPattern.DOUBLE_THEN_LONG), detect(0L to 80L, 200L to 1_500L))
        assertEquals(listOf(KeyPattern.TRIPLE_THEN_LONG), detect(0L to 80L, 200L to 280L, 400L to 1_600L))
    }

    @Test
    fun slowTapsAndTooManyTapsDoNotCombine() {
        assertEquals(listOf(KeyPattern.SINGLE, KeyPattern.SINGLE), detect(0L to 80L, 1_000L to 1_080L))
        assertEquals(emptyList<KeyPattern>(), detect(0L to 50L, 150L to 200L, 300L to 350L, 450L to 500L))
    }

    // ---- 统一门槛（R34）----

    @Test
    fun externalIntentGoesThroughTheSameGate() {
        SurfaceRuntime.get(context).stateStore.welcomeCompleted = true
        val intent = Intent(QuickEntryActivity.ACTION_ENTER_DISGUISE)
            .setClassName(context, QuickEntryActivity::class.java.name)
            // 伪造的附加数据不起作用。
            .putExtra("com.flue.launcher.extra.DISGUISE_ENTRY_ORIGIN", "NONE")
            .putExtra("skip_tutorial", true)
        val activity = Robolectric.buildActivity(QuickEntryActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
        assertEquals(SurfaceMode.FULL, SurfaceRuntime.get(context).controller.mode.value)
        val next = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            ?: shadowOf(activity).nextStartedActivity
        assertEquals(DisguiseSetupActivity::class.java.name, next.component?.className)
    }

    @Test
    fun unknownActionDoesNothing() {
        val intent = Intent("other.action").setClassName(context, QuickEntryActivity::class.java.name)
        val activity = Robolectric.buildActivity(QuickEntryActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
