package com.flue.launcher.surface

import android.content.Context
import com.flue.launcher.surface.publicsettings.AboutField
import com.flue.launcher.surface.publicsettings.AboutFormat
import com.flue.launcher.surface.publicsettings.AboutGroup
import com.flue.launcher.surface.publicsettings.AboutOverrides
import com.flue.launcher.surface.publicsettings.DevicePage
import com.flue.launcher.surface.publicsettings.DeviceInfoReader
import com.flue.launcher.surface.publicsettings.DeviceRouteChoice
import com.flue.launcher.surface.publicsettings.DeviceRouter
import com.flue.launcher.surface.publicsettings.PublicSettingsItem
import com.flue.launcher.surface.publicsettings.RepeatTapDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R26 公开设置与关于、R27 设备页路由。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PublicSettingsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        context.getSharedPreferences("fflue_about_overrides", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun publicItemsAreOrdinaryWatchSettingsOnly() {
        assertEquals(
            listOf("NETWORK", "BLUETOOTH", "DISPLAY", "SOUND", "NOTIFICATIONS", "BATTERY", "APPS", "SECURITY", "SYSTEM", "ABOUT"),
            PublicSettingsItem.entries.map { it.name },
        )
        assertEquals(DevicePage.entries.toSet(), PublicSettingsItem.entries.mapNotNull { it.devicePage }.toSet())
        assertTrue(PublicSettingsItem.ABOUT.systemActions.isEmpty())
        PublicSettingsItem.entries.filter { it != PublicSettingsItem.ABOUT }.forEach {
            assertTrue("${it.name} 必须能打开系统页", it.systemActions.isNotEmpty())
        }
    }

    @Test
    fun deviceRouteDefaultsToSystemAndNeedsKnownBuiltIn() {
        assertEquals(DeviceRouteChoice.SYSTEM, DeviceRouter.route(null, builtInAvailable = true))
        assertEquals(DeviceRouteChoice.SYSTEM, DeviceRouter.route(DeviceRouteChoice.SYSTEM, builtInAvailable = true))
        assertEquals(DeviceRouteChoice.SYSTEM, DeviceRouter.route(DeviceRouteChoice.FFLUE, builtInAvailable = false))
        assertEquals(DeviceRouteChoice.FFLUE, DeviceRouter.route(DeviceRouteChoice.FFLUE, builtInAvailable = true))
        DevicePage.entries.forEach { assertFalse(DeviceRouter.builtInAvailable(it)) }
    }

    @Test
    fun formattingNeverInventsValues() {
        assertEquals("7.8 GB", AboutFormat.bytes(7_812_000_000))
        assertEquals("512 MB", AboutFormat.bytes(512_000_000))
        assertNull(AboutFormat.bytes(null))
        assertNull(AboutFormat.bytes(-1))
        assertEquals("57%", AboutFormat.batteryLevel(57, 100))
        assertNull(AboutFormat.batteryLevel(-1, 100))
        assertNull(AboutFormat.batteryLevel(50, 0))
        assertEquals("31.2 °C", AboutFormat.temperature(312))
        assertNull(AboutFormat.temperature(null))
    }

    @Test
    fun overrideOnlyChangesDisplayAndBlankRestoresReal() {
        assertEquals("override", AboutFormat.display("real", " override "))
        assertEquals("real", AboutFormat.display("real", "  "))
        assertEquals("real", AboutFormat.display("real", null))
        assertNull(AboutFormat.display(null, ""))
    }

    @Test
    fun groupsAndBuildNumberFirst() {
        assertEquals(5, AboutGroup.entries.size)
        assertEquals(11, AboutField.entries.size)
        assertEquals(AboutField.BUILD_NUMBER, AboutField.entries.first { it.group == AboutGroup.SYSTEM })
    }

    @Test
    fun overridesPersistAndBlankRemoves() {
        assertTrue(AboutOverrides.save(context, mapOf(AboutField.MODEL to "Watch X", AboutField.STORAGE_TOTAL to "32 GB")))
        assertEquals(mapOf(AboutField.MODEL to "Watch X", AboutField.STORAGE_TOTAL to "32 GB"), AboutOverrides.read(context))
        assertTrue(AboutOverrides.save(context, mapOf(AboutField.MODEL to " ", AboutField.STORAGE_TOTAL to "32 GB")))
        assertEquals(mapOf(AboutField.STORAGE_TOTAL to "32 GB"), AboutOverrides.read(context))
    }

    @Test
    fun readerReturnsRealValuesOrNull() {
        val values = DeviceInfoReader.read(context)
        assertTrue(values[AboutField.ANDROID_VERSION]!!.startsWith("Android "))
        values.values.filterNotNull().forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun temporaryGestureNeedsSevenQuickTaps() {
        val d = RepeatTapDetector(required = 7, gapMs = 1_000)
        repeat(6) { assertFalse(d.tap(it * 300L)) }
        assertTrue(d.tap(1_800))
        assertFalse(d.tap(2_000))
        val slow = RepeatTapDetector(required = 3, gapMs = 1_000)
        assertFalse(slow.tap(0))
        assertFalse(slow.tap(500))
        assertFalse(slow.tap(2_000))
        assertFalse(slow.tap(2_500))
        assertTrue(slow.tap(3_000))
    }
}
