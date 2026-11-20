package com.flue.launcher.surface

import android.content.Context
import android.content.res.Configuration
import com.flue.launcher.surface.display.DisplayAdaptation
import com.flue.launcher.surface.display.SafeAreaSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 设备级显示配置的持久化与密度应用（R23、R24）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w227dp-h227dp-xhdpi")
class DisplayAdaptationStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        context.getSharedPreferences("fflue_display", Context.MODE_PRIVATE).edit().clear().commit()
        DisplayAdaptation.resetCacheForTest()
    }

    @After
    fun reset() = DisplayAdaptation.resetCacheForTest()

    @Test
    fun unconfiguredDpiFollowsStableDensity() {
        assertNull(DisplayAdaptation.savedDpi(context))
        assertEquals(DisplayAdaptation.stableDpi(), DisplayAdaptation.effectiveDpi(context))
    }

    @Test
    fun savedDpiDrivesDensityConfiguration() {
        assertTrue(DisplayAdaptation.saveDpi(context, 160))
        assertEquals(160, DisplayAdaptation.effectiveDpi(context))
        val current = Configuration().apply {
            densityDpi = 320
            screenWidthDp = 227
            screenHeightDp = 227
            smallestScreenWidthDp = 227
            fontScale = 1.15f
        }
        val config = DisplayAdaptation.densityConfiguration(current, DisplayAdaptation.effectiveDpi(context))!!
        assertEquals(160, config.densityDpi)
        // 像素尺寸不变：320 dpi 下 227 dp = 160 dpi 下 454 dp。
        assertEquals(454, config.screenWidthDp)
        assertEquals(454, config.screenHeightDp)
        assertEquals(454, config.smallestScreenWidthDp)
        // 字体缩放等其他配置原样保留。
        assertEquals(1.15f, config.fontScale, 0f)
        assertNull(DisplayAdaptation.densityConfiguration(config, 160))
    }

    @Test
    fun savingStableDpiRestoresDefault() {
        assertTrue(DisplayAdaptation.saveDpi(context, 200))
        assertTrue(DisplayAdaptation.saveDpi(context, DisplayAdaptation.stableDpi()))
        assertNull(DisplayAdaptation.savedDpi(context))
    }

    @Test
    fun matchingDensityReturnsSameContext() {
        val current = context.resources.configuration.densityDpi
        assertTrue(DisplayAdaptation.saveDpi(context, current))
        assertEquals(current, DisplayAdaptation.effectiveDpi(context))
        assertSame(context, DisplayAdaptation.applyDensity(context))
    }

    @Test
    fun safeAreaRoundTripsAndNotifies() {
        val flow = DisplayAdaptation.safeArea(context)
        assertNull(flow.value)
        assertTrue(DisplayAdaptation.saveSafeArea(context, SafeAreaSpec(92.46f, 88f, 51f)))
        assertEquals(SafeAreaSpec(92.5f, 88f, 50f), flow.value)
        DisplayAdaptation.resetCacheForTest()
        assertEquals(SafeAreaSpec(92.5f, 88f, 50f), DisplayAdaptation.safeArea(context).value)
    }
}
