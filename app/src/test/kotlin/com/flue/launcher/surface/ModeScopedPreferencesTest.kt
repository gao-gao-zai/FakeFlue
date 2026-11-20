package com.flue.launcher.surface

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.flue.launcher.surface.config.LauncherSettingsScopes.DISGUISE_PREFIX
import com.flue.launcher.surface.config.ModeScopedPreferences
import com.flue.launcher.surface.mode.ConfigScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModeScopedPreferencesTest {

    private val face = stringPreferencesKey("selected_watchface_id")
    private val hidden = stringSetPreferencesKey("hidden_apps")
    private val scale = intPreferencesKey("global_ui_scale_percent")
    private val blur = booleanPreferencesKey("blur_enabled")
    private val disguiseFace = stringPreferencesKey(DISGUISE_PREFIX + "selected_watchface_id")

    private val physical = preferencesOf(
        face to "full_face",
        hidden to setOf("a"),
        scale to 90,
        disguiseFace to "plain_face",
    )

    @Test
    fun fullViewIsOriginalDataWithoutDisguiseCopies() {
        val view = ModeScopedPreferences.project(physical, ConfigScope.FULL)
        assertEquals("full_face", view[face])
        assertEquals(setOf("a"), view[hidden])
        assertEquals(90, view[scale])
        assertEquals(3, view.asMap().size)
    }

    @Test
    fun disguiseViewSharesGlobalKeysAndDefaultsTheRest() {
        val view = ModeScopedPreferences.project(physical, ConfigScope.DISGUISE)
        assertEquals("plain_face", view[face])
        assertNull("伪装模式没有自己的值时为未设置，由原版默认值兜底", view[hidden])
        assertEquals(90, view[scale])
    }

    @Test
    fun disguiseWritesDoNotTouchFullValues() {
        val before = ModeScopedPreferences.project(physical, ConfigScope.DISGUISE)
        val after = before.toMutablePreferences().apply {
            this[face] = "new_plain"
            this[hidden] = setOf("secret")
            this[blur] = false
        }.toPreferences()
        val result = ModeScopedPreferences.applyViewChange(physical, before, after, ConfigScope.DISGUISE)
        assertEquals("full_face", result[face])
        assertEquals(setOf("a"), result[hidden])
        assertEquals("new_plain", result[disguiseFace])
        assertEquals(setOf("secret"), result[stringSetPreferencesKey(DISGUISE_PREFIX + "hidden_apps")])
        assertEquals(false, result[booleanPreferencesKey(DISGUISE_PREFIX + "blur_enabled")])
        assertNull(result[blur])
    }

    @Test
    fun fullWritesDoNotTouchDisguiseValues() {
        val before = ModeScopedPreferences.project(physical, ConfigScope.FULL)
        val after = before.toMutablePreferences().apply {
            this[face] = "other_full"
            remove(hidden)
        }.toPreferences()
        val result = ModeScopedPreferences.applyViewChange(physical, before, after, ConfigScope.FULL)
        assertEquals("other_full", result[face])
        assertNull(result[hidden])
        assertEquals("plain_face", result[disguiseFace])
    }

    @Test
    fun globalKeyWrittenFromDisguiseIsSharedWithFull() {
        val before = ModeScopedPreferences.project(physical, ConfigScope.DISGUISE)
        val after = before.toMutablePreferences().apply { this[scale] = 110 }.toPreferences()
        val result = ModeScopedPreferences.applyViewChange(physical, before, after, ConfigScope.DISGUISE)
        assertEquals(110, result[scale])
        assertNull(result[intPreferencesKey(DISGUISE_PREFIX + "global_ui_scale_percent")])
        assertEquals(110, ModeScopedPreferences.project(result, ConfigScope.FULL)[scale])
    }

    @Test
    fun clearingDisguiseValueRemovesOnlyTheCopy() {
        val before = ModeScopedPreferences.project(physical, ConfigScope.DISGUISE)
        val after = before.toMutablePreferences().apply { remove(face) }.toPreferences()
        val result = ModeScopedPreferences.applyViewChange(physical, before, after, ConfigScope.DISGUISE)
        assertNull(result[disguiseFace])
        assertEquals("full_face", result[face])
    }

    @Test
    fun unchangedViewKeepsPhysicalInstance() {
        val view = ModeScopedPreferences.project(physical, ConfigScope.DISGUISE)
        assertSame(physical, ModeScopedPreferences.applyViewChange(physical, view, view, ConfigScope.DISGUISE))
    }

    @Test
    fun prefixedNamesInViewAreIgnored() {
        val before = ModeScopedPreferences.project(physical, ConfigScope.FULL)
        val after = before.toMutablePreferences().apply { this[disguiseFace] = "hijack" }.toPreferences()
        val result = ModeScopedPreferences.applyViewChange(physical, before, after, ConfigScope.FULL)
        assertEquals("plain_face", result[disguiseFace])
    }
}
