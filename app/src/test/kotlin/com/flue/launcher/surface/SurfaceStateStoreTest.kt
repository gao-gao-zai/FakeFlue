package com.flue.launcher.surface

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.DisguiseEntryGate
import com.flue.launcher.surface.mode.ModeSwitchEffects
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.mode.SurfaceModeController
import com.flue.launcher.surface.mode.SurfaceStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class SurfaceStateStoreTest {
    private lateinit var preferences: SharedPreferences

    private val effects = object : ModeSwitchEffects {
        override fun onEnteredRestrictedMode() = Unit
        override fun onLeftRestrictedMode() = Unit
        override fun ownPackageName() = "com.gaozai.surface"
        override fun isLaunchableHome(flattenedComponent: String) = false
        override fun launchExternalHome(flattenedComponent: String) = false
        override fun defaultHomePackage(): String? = null
    }

    @Before
    fun createFreshStateStore() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
            "surface_state_store_test", Context.MODE_PRIVATE,
        )
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun missingModeRestoresFullModeAndConfiguration() {
        assertRestoredMode(SurfaceMode.FULL)
    }

    @Test
    fun unknownModeRestoresFullModeAndConfiguration() {
        assertTrue(preferences.edit().putString(SurfaceStateStore.KEY_MODE, "UNKNOWN_MODE").commit())
        assertRestoredMode(SurfaceMode.FULL)
    }

    @Test
    fun integerModeRestoresFullModeAndConfiguration() {
        assertTrue(preferences.edit().putInt(SurfaceStateStore.KEY_MODE, 1).commit())
        assertRestoredMode(SurfaceMode.FULL)
    }

    @Test
    fun booleanModeRestoresFullModeAndConfiguration() {
        assertTrue(preferences.edit().putBoolean(SurfaceStateStore.KEY_MODE, true).commit())
        assertRestoredMode(SurfaceMode.FULL)
    }

    @Test
    fun validModesRestoreTheirSavedModeAndConfiguration() {
        val store = SurfaceStateStore(preferences)
        for (mode in SurfaceMode.entries) {
            assertTrue(store.writeMode(mode))
            assertRestoredMode(mode)
        }
    }

    private fun assertRestoredMode(expected: SurfaceMode) {
        val controller = SurfaceModeController(
            storage = SurfaceStateStore(preferences),
            gate = DisguiseEntryGate { null },
            effects = effects,
        )
        assertEquals(expected, controller.mode.value)
        assertEquals(
            if (expected == SurfaceMode.DISGUISE) ConfigScope.DISGUISE else ConfigScope.FULL,
            controller.configScope.value,
        )
    }
}
