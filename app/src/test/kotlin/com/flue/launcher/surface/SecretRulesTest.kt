package com.flue.launcher.surface

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.flue.launcher.surface.secret.BackAreaDrag
import com.flue.launcher.surface.secret.BackDragResult
import com.flue.launcher.surface.secret.HoldGesture
import com.flue.launcher.surface.secret.HoldOutcome
import com.flue.launcher.surface.secret.ResetTapCounter
import com.flue.launcher.surface.secret.SafeBounds
import com.flue.launcher.surface.secret.SecretAttempt
import com.flue.launcher.surface.secret.SecretCipher
import com.flue.launcher.surface.secret.SecretEntry
import com.flue.launcher.surface.secret.SecretRead
import com.flue.launcher.surface.secret.SecretRules
import com.flue.launcher.surface.secret.SecretStore
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.DisguiseEntryRequirement
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P3：五区域密码、手势几何、重置计数、设置流程与存储（R12–R17）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SecretRulesTest {

    // ---- 分区（R12）----

    @Test
    fun zonesOnSquareScreen() {
        val w = 400f
        val h = 400f
        // 中心圆半径 = 400 × 0.26 = 104。
        assertEquals(5, SecretRules.zoneOf(200f, 200f, w, h))
        assertEquals(5, SecretRules.zoneOf(200f + 104f, 200f, w, h))
        assertEquals(2, SecretRules.zoneOf(200f + 105f, 199f, w, h))
        assertEquals(4, SecretRules.zoneOf(200f + 105f, 200f, w, h))
        assertEquals(1, SecretRules.zoneOf(0f, 0f, w, h))
        assertEquals(2, SecretRules.zoneOf(399f, 0f, w, h))
        assertEquals(3, SecretRules.zoneOf(0f, 399f, w, h))
        assertEquals(4, SecretRules.zoneOf(399f, 399f, w, h))
    }

    @Test
    fun centerWinsOverQuadrantsAndUsesShortSide() {
        // 竖长屏：半径按短边 300 × 0.26 = 78。
        val w = 300f
        val h = 500f
        assertEquals(5, SecretRules.zoneOf(150f - 50f, 250f - 50f, w, h))
        assertEquals(5, SecretRules.zoneOf(150f, 250f - 78f, w, h))
        assertEquals(1, SecretRules.zoneOf(149f, 250f - 79f, w, h))
        assertEquals(3, SecretRules.zoneOf(149f, 250f + 79f, w, h))
        // 中线：x == cx 算右侧，y == cy 算下侧。
        assertEquals(2, SecretRules.zoneOf(150f, 10f, w, h))
        assertEquals(3, SecretRules.zoneOf(10f, 250f, w, h))
    }

    @Test
    fun lengthAndDigitRules() {
        assertFalse(SecretRules.isValidLength(5))
        assertTrue(SecretRules.isValidLength(6))
        assertTrue(SecretRules.isValidLength(16))
        assertFalse(SecretRules.isValidLength(17))
        assertFalse(SecretRules.isValid(listOf(1, 2, 3, 4, 5, 6)))
        assertFalse(SecretRules.isValid(listOf(0, 1, 2, 3, 4, 5)))
        assertEquals(listOf(1, 2, 3, 4, 5, 5), SecretRules.decode(SecretRules.encode(listOf(1, 2, 3, 4, 5, 5))))
        assertNull(SecretRules.decode("12345"))
        assertNull(SecretRules.decode("1234a6"))
        assertNull(SecretRules.decode(null))
    }

    // ---- 逐位比较（R12）----

    @Test
    fun attemptFailsOnFirstWrongDigit() {
        val secret = listOf(1, 2, 3, 4, 5, 1)
        for (wrongAt in secret.indices) {
            val attempt = SecretAttempt(secret)
            repeat(wrongAt) { i -> assertEquals(SecretAttempt.Step.Continue(i + 1), attempt.input(secret[i])) }
            val wrong = if (secret[wrongAt] == 5) 1 else secret[wrongAt] + 1
            assertEquals(SecretAttempt.Step.Failure, attempt.input(wrong))
            // 结束后再输入也不会变成成功。
            assertEquals(SecretAttempt.Step.Failure, attempt.input(secret[wrongAt]))
        }
    }

    @Test
    fun attemptSucceedsImmediatelyOnLastDigit() {
        val secret = listOf(5, 5, 5, 5, 5, 5, 1)
        val attempt = SecretAttempt(secret)
        secret.dropLast(1).forEach { assertTrue(attempt.input(it) is SecretAttempt.Step.Continue) }
        assertEquals(SecretAttempt.Step.Success, attempt.input(1))
        assertEquals(SecretAttempt.Step.Failure, attempt.input(1))
    }

    @Test
    fun missingSecretNeverSucceeds() {
        (1..5).forEach { assertEquals(SecretAttempt.Step.Failure, SecretAttempt(emptyList()).input(it)) }
    }

    // ---- 按住手势（R14、R15）----

    @Test
    fun holdClassification() {
        assertEquals(HoldOutcome.TAP, HoldGesture.classify(1_999, movedBeforeHold = false))
        assertEquals(HoldOutcome.HELD, HoldGesture.classify(2_000, movedBeforeHold = false))
        assertEquals(HoldOutcome.MOVED, HoldGesture.classify(3_000, movedBeforeHold = true))
        assertEquals(HoldOutcome.MOVED, HoldGesture.classify(100, movedBeforeHold = true))
    }

    // ---- 返回区域拖动（R15、R17）----

    private val safe = SafeBounds(left = 40f, top = 40f, width = 320f, height = 320f)

    @Test
    fun rightDragThresholdUsesSafeAreaEnd() {
        // 阈值 = 40 + 320 × 0.75 = 280。
        assertEquals(BackDragResult.MANAGEMENT, BackAreaDrag.classify(60f, 60f, 280f, 60f, safe, 8f))
        assertEquals(BackDragResult.NONE, BackAreaDrag.classify(60f, 60f, 279f, 60f, safe, 8f))
    }

    @Test
    fun rightDragToleranceIsRelativeToDown() {
        // 容差 = 320 × 0.15 = 48。
        assertEquals(BackDragResult.MANAGEMENT, BackAreaDrag.classify(60f, 60f, 300f, 108f, safe, 8f))
        assertEquals(BackDragResult.NONE, BackAreaDrag.classify(60f, 60f, 300f, 109f, safe, 8f))
        assertEquals(BackDragResult.MANAGEMENT, BackAreaDrag.classify(60f, 100f, 300f, 52f, safe, 8f))
    }

    @Test
    fun downDragForReset() {
        assertEquals(BackDragResult.RESET, BackAreaDrag.classify(60f, 60f, 60f, 280f, safe, 8f))
        assertEquals(BackDragResult.NONE, BackAreaDrag.classify(60f, 60f, 60f, 279f, safe, 8f))
        assertEquals(BackDragResult.RESET, BackAreaDrag.classify(60f, 60f, 108f, 300f, safe, 8f))
        assertEquals(BackDragResult.NONE, BackAreaDrag.classify(60f, 60f, 109f, 300f, safe, 8f))
    }

    @Test
    fun diagonalDragIsNeither() {
        assertEquals(BackDragResult.NONE, BackAreaDrag.classify(60f, 60f, 320f, 320f, safe, 8f))
    }

    @Test
    fun toleranceNeverBelowTouchSlop() {
        val tiny = SafeBounds(0f, 0f, 100f, 100f)
        // 短边 15% = 15，但系统容差 20 更大。
        assertEquals(BackDragResult.MANAGEMENT, BackAreaDrag.classify(5f, 5f, 80f, 25f, tiny, 20f))
        assertEquals(BackDragResult.NONE, BackAreaDrag.classify(5f, 5f, 80f, 26f, tiny, 20f))
    }

    // ---- 重置计数（R17）----

    @Test
    fun resetNeedsExactlyHundredTaps() {
        val counter = ResetTapCounter()
        repeat(99) { assertFalse(counter.tap()) }
        assertEquals(99, counter.count)
        assertTrue(counter.tap())
        assertFalse(counter.tap())
    }

    @Test
    fun resetClearStartsOver() {
        val counter = ResetTapCounter()
        repeat(99) { counter.tap() }
        counter.clear()
        repeat(99) { assertFalse(counter.tap()) }
        assertTrue(counter.tap())
    }

    // ---- 设置流程（R13）----

    @Test
    fun entryConfirmsAndMatches() {
        val entry = SecretEntry(6)
        val digits = listOf(1, 2, 3, 4, 5, 1)
        digits.dropLast(1).forEachIndexed { i, d ->
            assertEquals(SecretEntry.Result.Progress(SecretEntry.Stage.FIRST, i + 1), entry.input(d))
        }
        assertEquals(SecretEntry.Result.StartConfirm, entry.input(digits.last()))
        assertEquals(SecretEntry.Stage.CONFIRM, entry.stage)
        digits.dropLast(1).forEach { entry.input(it) }
        assertEquals(SecretEntry.Result.Matched(digits), entry.input(digits.last()))
    }

    @Test
    fun entryMismatchStartsOver() {
        val entry = SecretEntry(6)
        repeat(6) { entry.input(1) }
        repeat(5) { entry.input(1) }
        assertEquals(SecretEntry.Result.Mismatch, entry.input(2))
        assertEquals(SecretEntry.Stage.FIRST, entry.stage)
        assertEquals(0, entry.entered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun entryRejectsShortLength() {
        SecretEntry(5)
    }

    // ---- 存储（R12）----

    private class FakeCipher(var broken: Boolean = false, var failEncrypt: Boolean = false) : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            check(!failEncrypt)
            return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            check(!broken)
            return blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store(cipher: SecretCipher, name: String = "secret_test") =
        SecretStore(context.getSharedPreferences(name, Context.MODE_PRIVATE), cipher)

    @Test
    fun storeRoundTripAndNotPlainText() {
        val cipher = FakeCipher()
        val store = store(cipher, "secret_round")
        assertEquals(SecretRead.NotSet, store.read())
        assertTrue(store.save(listOf(1, 2, 3, 4, 5, 1)))
        assertEquals(SecretRead.Available(listOf(1, 2, 3, 4, 5, 1)), store.read())
        val raw = context.getSharedPreferences("secret_round", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("123451"))
    }

    @Test
    fun unreadableIsNotSuccessAndNotCleared() {
        val cipher = FakeCipher()
        val store = store(cipher, "secret_broken")
        assertTrue(store.save(listOf(2, 2, 2, 2, 2, 2)))
        cipher.broken = true
        assertEquals(SecretRead.Unreadable, store.read())
        cipher.broken = false
        assertEquals(SecretRead.Available(listOf(2, 2, 2, 2, 2, 2)), store.read())
    }

    @Test
    fun failedSaveKeepsOldSecret() {
        val cipher = FakeCipher()
        val store = store(cipher, "secret_keep")
        assertTrue(store.save(listOf(3, 3, 3, 3, 3, 3)))
        cipher.failEncrypt = true
        assertFalse(store.save(listOf(4, 4, 4, 4, 4, 4)))
        assertFalse(store.save(listOf(1, 2, 3)))
        cipher.failEncrypt = false
        assertEquals(SecretRead.Available(listOf(3, 3, 3, 3, 3, 3)), store.read())
    }

    // ---- 会话与授权（R14、R15、R17）----

    @Test
    fun disguiseNeedsReadableSecret() {
        val runtime = SurfaceRuntime.get(context)
        runtime.stateStore.welcomeCompleted = true
        assertEquals(SecretRead.NotSet, runtime.secretStore.read())
        val result = runtime.controller.switchTo(SurfaceMode.DISGUISE)
        assertEquals(ModeSwitchResult.Blocked(DisguiseEntryRequirement.BASE_CONFIGURATION), result)
        assertEquals(ConfigScope.FULL, runtime.controller.configScope.value)
    }

    @Test
    fun grantsAreOneShotAndInterruptionsRevoke() {
        val runtime = SurfaceRuntime.get(context)
        runtime.grantSecretReset()
        assertTrue(runtime.consumeSecretResetGrant())
        assertFalse(runtime.consumeSecretResetGrant())

        runtime.beginManagementSession()
        runtime.grantHiddenDrawer()
        runtime.grantSecretReset()
        runtime.onInterrupted()
        assertFalse(runtime.managementSession.value)
        assertFalse(runtime.consumeSecretResetGrant())
    }

    @Test
    fun hiddenDrawerChallengeOnlyInDisguise() {
        val runtime = SurfaceRuntime.get(context)
        runtime.requestHiddenDrawerChallenge()
        assertFalse(runtime.hiddenDrawerChallenge.value)
    }
}
