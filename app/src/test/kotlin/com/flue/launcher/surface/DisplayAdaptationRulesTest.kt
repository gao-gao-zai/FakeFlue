package com.flue.launcher.surface

import com.flue.launcher.surface.display.AppDensity
import com.flue.launcher.surface.display.SafeAreaGeometry
import com.flue.launcher.surface.display.SafeAreaSpec
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R23 安全区几何与 R24 DPI 规则。 */
class DisplayAdaptationRulesTest {

    @Test
    fun specIsClampedAndRoundedToTenth() {
        val s = SafeAreaSpec(widthPercent = 40f, heightPercent = 120f, cornerPercent = 33.333f).normalized()
        assertEquals(55f, s.widthPercent)
        assertEquals(100f, s.heightPercent)
        assertEquals(33.3f, s.cornerPercent)
        assertEquals(0f, SafeAreaSpec(90f, 90f, -5f).normalized().cornerPercent)
        assertEquals(50f, SafeAreaSpec(90f, 90f, 80f).normalized().cornerPercent)
    }

    @Test
    fun defaultsFollowScreenShape() {
        assertEquals(SafeAreaSpec(100f, 100f, 50f), SafeAreaSpec.default(roundScreen = true))
        assertEquals(SafeAreaSpec(100f, 100f, 0f), SafeAreaSpec.default(roundScreen = false))
    }

    @Test
    fun frameIsCenteredAndCornerUsesShortSide() {
        val f = SafeAreaGeometry.frame(400f, 500f, SafeAreaSpec(90f, 80f, 25f))
        assertEquals(360f, f.width, 0.001f)
        assertEquals(400f, f.height, 0.001f)
        assertEquals(20f, f.left, 0.001f)
        assertEquals(50f, f.top, 0.001f)
        assertEquals(380f, f.right, 0.001f)
        assertEquals(450f, f.bottom, 0.001f)
        // 短边 360 的 25%。
        assertEquals(90f, f.cornerRadius, 0.001f)
    }

    @Test
    fun barInsetPutsBarCornerOnTheArc() {
        val r = 200f
        val d = SafeAreaGeometry.barInset(r)
        assertEquals(r * (1f - sqrt(2f) / 2f), d, 0.001f)
        // 固定栏角点 (d, d) 到弧心 (r, r) 的距离正好等于 r。
        val dist = sqrt(2f) * (r - d)
        assertEquals(r, dist, 0.01f)
        assertEquals(0f, SafeAreaGeometry.barInset(0f), 0f)
    }

    @Test
    fun squareCornersNeedNoBarInset() {
        val f = SafeAreaGeometry.frame(320f, 320f, SafeAreaSpec.default(roundScreen = false))
        assertEquals(0f, f.left, 0f)
        assertEquals(0f, f.top, 0f)
        assertEquals(0f, f.barInset, 0f)
    }

    @Test
    fun parsePercentAcceptsExactInputInRange() {
        val range = SafeAreaSpec.MIN_SIZE_PERCENT..SafeAreaSpec.MAX_SIZE_PERCENT
        assertEquals(92.5f, SafeAreaSpec.parsePercent(" 92.5% ", range))
        assertEquals(92.5f, SafeAreaSpec.parsePercent("92,5", range))
        assertEquals(60.1f, SafeAreaSpec.parsePercent("60.14", range))
        assertNull(SafeAreaSpec.parsePercent("54.9", range))
        assertNull(SafeAreaSpec.parsePercent("abc", range))
        assertNull(SafeAreaSpec.parsePercent("NaN", range))
    }

    @Test
    fun dpiDefaultsToStableAndClampsSavedValue() {
        assertEquals(320, AppDensity.effective(savedDpi = null, stableDpi = 320))
        assertEquals(240, AppDensity.effective(savedDpi = 240, stableDpi = 320))
        assertEquals(80, AppDensity.effective(savedDpi = 10, stableDpi = 320))
        assertEquals(640, AppDensity.effective(savedDpi = 9000, stableDpi = 320))
    }

    @Test
    fun savingStableDpiMeansUnconfigured() {
        assertNull(AppDensity.toStored(320, stableDpi = 320))
        assertEquals(280, AppDensity.toStored(280, stableDpi = 320))
        assertEquals(640, AppDensity.toStored(700, stableDpi = 320))
    }

    @Test
    fun dpiInputMustBeIntegerInRange() {
        assertEquals(80, AppDensity.parseInput("80"))
        assertEquals(640, AppDensity.parseInput(" 640 "))
        assertNull(AppDensity.parseInput("79"))
        assertNull(AppDensity.parseInput("641"))
        assertNull(AppDensity.parseInput("320.5"))
        assertNull(AppDensity.parseInput(""))
    }

    @Test
    fun dpSizeRescalesToKeepPixels() {
        // 360 px 宽：320 dpi 下 180 dp，160 dpi 下 360 dp。
        assertEquals(360, AppDensity.rescaleDp(180, fromDpi = 320, toDpi = 160))
        assertEquals(120, AppDensity.rescaleDp(180, fromDpi = 320, toDpi = 480))
        assertEquals(180, AppDensity.rescaleDp(180, fromDpi = 0, toDpi = 480))
    }
}
