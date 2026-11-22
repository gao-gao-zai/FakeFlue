package com.flue.launcher.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.viewmodel.LauncherViewModel
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R37：备份设置只按已登记的键与类型写回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupPreferenceSchemaTest {

    private fun launcherKeys(): Map<String, Class<*>> {
        val result = LinkedHashMap<String, Class<*>>()
        LauncherViewModel::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Preferences.Key::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                val key = field.get(null) as Preferences.Key<*>
                val argument = (field.genericType as ParameterizedType).actualTypeArguments.single() as Class<*>
                result[key.name] = argument
            }
        return result
    }

    @Test
    fun `schema matches every LauncherViewModel key and its declared type`() {
        val keys = launcherKeys()
        assertTrue("反射没有拿到设置键", keys.size > 100)
        keys.forEach { (name, type) ->
            val expected = when (type) {
                java.lang.Boolean::class.java -> BackupPreferenceSchema.Type.BOOLEAN
                java.lang.Integer::class.java -> BackupPreferenceSchema.Type.INT
                java.lang.String::class.java -> BackupPreferenceSchema.Type.STRING
                else -> error("unexpected key type $type for $name")
            }
            assertEquals("schema type of $name", expected, BackupPreferenceSchema.typeOfLogical(name))
        }
        assertEquals(keys.keys, BackupPreferenceSchema.knownLogicalNames)
    }

    @Test
    fun `every scoped settings key is known to the backup schema`() {
        (LauncherSettingsScopes.modeScopedKeys + LauncherSettingsScopes.deviceGlobalKeys).forEach { name ->
            assertTrue("$name missing from BackupPreferenceSchema", BackupPreferenceSchema.typeOfLogical(name) != null)
        }
    }

    private fun entry(type: String, value: Any?) = JSONObject().put("type", type).put("value", value ?: JSONObject.NULL)

    @Test
    fun `unknown keys and mismatched types are skipped and counted`() {
        val json = JSONObject()
            .put("blur_enabled", entry("string", "yes")) // 声明类型错
            .put("honeycomb_cols", entry("int", "4")) // 值类型错
            .put("splash_delay", entry("int", 1.5)) // 非整数
            .put("totally_unknown", entry("string", "x")) // 未知键
            .put("${LauncherSettingsScopes.DISGUISE_PREFIX}app_language", entry("string", "en")) // 设备级键不该有伪装副本
            .put("edge_blur_enabled", entry("boolean", true))
            .put("honeycomb_top_blur", entry("int", 12))
            .put("theme_mode", entry("string", "DARK"))
            .put("${LauncherSettingsScopes.DISGUISE_PREFIX}blur_enabled", entry("boolean", false))
        val prefs = mutablePreferencesOf()

        val skipped = with(FlueBackupManager) { prefs.applyPreferenceJson(json) }

        assertEquals(5, skipped)
        assertEquals(true, prefs[booleanPreferencesKey("edge_blur_enabled")])
        assertEquals(12, prefs[intPreferencesKey("honeycomb_top_blur")])
        assertEquals("DARK", prefs[stringPreferencesKey("theme_mode")])
        assertEquals(false, prefs[booleanPreferencesKey("${LauncherSettingsScopes.DISGUISE_PREFIX}blur_enabled")])
        val names = prefs.asMap().keys.map { it.name }.toSet()
        assertTrue("blur_enabled" !in names)
        assertTrue("honeycomb_cols" !in names)
        assertTrue("splash_delay" !in names)
        assertTrue("totally_unknown" !in names)
        assertTrue("${LauncherSettingsScopes.DISGUISE_PREFIX}app_language" !in names)
        // 写进去的每个值都能按登记类型读出，设置收集端不会 ClassCastException。
        prefs.asMap().forEach { (key, value) ->
            val expected = BackupPreferenceSchema.typeOfPhysical(key.name)
            when (expected) {
                BackupPreferenceSchema.Type.BOOLEAN -> assertTrue(value is Boolean)
                BackupPreferenceSchema.Type.INT -> assertTrue(value is Int)
                BackupPreferenceSchema.Type.STRING -> assertTrue(value is String)
                null -> error("unexpected key ${key.name}")
            }
        }
    }

    @Test
    fun `unknown app list keeps hidden apps app order and shortcuts`() {
        val json = JSONObject()
            .put("hidden_apps", entry("string", "a/A,b/B"))
            .put("app_order", entry("string", "b/B,c/C"))
            .put("side_screen_shortcuts", entry("string", "a/A||c/C"))
        val prefs = mutablePreferencesOf()

        with(FlueBackupManager) { prefs.applyPreferenceJson(json, availableAppKeys = null) }

        assertEquals("a/A,b/B", prefs[stringPreferencesKey("hidden_apps")])
        assertEquals("b/B,c/C", prefs[stringPreferencesKey("app_order")])
        assertEquals("a/A||c/C", prefs[stringPreferencesKey("side_screen_shortcuts")])
    }

    @Test
    fun `known app list still filters apps that are not installed`() {
        val json = JSONObject().put("hidden_apps", entry("string", "a/A,b/B"))
        val prefs = mutablePreferencesOf()

        with(FlueBackupManager) { prefs.applyPreferenceJson(json, availableAppKeys = setOf("b/B")) }

        assertEquals("b/B", prefs[stringPreferencesKey("hidden_apps")])
        assertNull(prefs[stringPreferencesKey("app_order")])
    }
}
