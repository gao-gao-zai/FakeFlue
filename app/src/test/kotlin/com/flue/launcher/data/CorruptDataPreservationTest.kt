package com.flue.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.flue.launcher.surface.apps.PublicAppOverride
import com.flue.launcher.surface.apps.PublicAppOverrides
import com.flue.launcher.surface.tools.ToolStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val CORRUPT_STORE_NAME = "r37_corruption_test"

private val Context.corruptTestStore by CorruptionPreservingPreferencesDelegate(CORRUPT_STORE_NAME)

/** R37：读不出的数据不再静默当成初始状态而被覆盖，原始内容先保留一份。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CorruptDataPreservationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `corrupt settings file is copied aside and the store starts empty`() {
        val file = context.preferencesDataStoreFile(CORRUPT_STORE_NAME)
        file.parentFile!!.mkdirs()
        val garbage = ByteArray(64) { 0xFF.toByte() }
        file.writeBytes(garbage)

        val prefs = runBlocking { context.corruptTestStore.data.first() }

        assertTrue(prefs.asMap().isEmpty())
        val copies = file.parentFile!!.listFiles()!!.filter { it.name.startsWith("${file.name}.corrupt-") }
        assertEquals(1, copies.size)
        assertArrayEquals(garbage, copies.single().readBytes())
        // 恢复后仍可正常写入。
        runBlocking { context.corruptTestStore.edit { it[stringPreferencesKey("k")] = "v" } }
        assertEquals("v", runBlocking { context.corruptTestStore.data.first()[stringPreferencesKey("k")] })
    }

    @Test
    fun `unreadable alarms are preserved before the next save overwrites them`() {
        val prefs = context.getSharedPreferences(ToolStore.PREFS, Context.MODE_PRIVATE)
        val raw = "[{\"id\":1,\"hour\":7,\"minute\":30,\"days\":[1],\"enabled\":true}, {broken"
        prefs.edit().clear().putString("alarms", raw).commit()
        val store = ToolStore(context)

        assertTrue(store.loadAlarms().isEmpty())
        store.saveAlarms(emptyList())
        store.loadAlarms()

        assertEquals(listOf(raw), store.unreadableAlarmBackups())
    }

    @Test
    fun `partially unreadable alarms keep readable ones and preserve the raw value`() {
        val prefs = context.getSharedPreferences(ToolStore.PREFS, Context.MODE_PRIVATE)
        val raw = "[{\"id\":1,\"hour\":7,\"minute\":30,\"days\":[1],\"enabled\":true},{\"id\":\"x\"}]"
        prefs.edit().clear().putString("alarms", raw).commit()
        val store = ToolStore(context)

        assertEquals(1, store.loadAlarms().size)
        assertEquals(listOf(raw), store.unreadableAlarmBackups())
    }

    @Test
    fun `icons referenced only by an unreadable overrides value are not orphans`() {
        val raw = "{\"a/A\":{\"icon\":\"icon_a.png\"}, broken"
        assertTrue(PublicAppOverrides.isUnreadable(raw))
        assertFalse(PublicAppOverrides.isUnreadable(null))
        assertFalse(PublicAppOverrides.isUnreadable("{}"))

        val current = mapOf("b/B" to PublicAppOverride(iconFile = "icon_b.png"))
        val files = listOf("icon_a.png", "icon_b.png", "icon_c.png")

        assertEquals(setOf("icon_a.png", "icon_c.png"), PublicAppOverrides.orphanIcons(current, files))
        assertEquals(setOf("icon_c.png"), PublicAppOverrides.orphanIcons(current, files, listOf(raw)))
    }
}
