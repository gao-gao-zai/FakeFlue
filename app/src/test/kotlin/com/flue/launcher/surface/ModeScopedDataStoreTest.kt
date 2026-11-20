package com.flue.launcher.surface

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flue.launcher.surface.config.LauncherSettingsScopes.DISGUISE_PREFIX
import com.flue.launcher.surface.config.ConfigScopePin
import com.flue.launcher.surface.config.ModeScopedDataStore
import com.flue.launcher.surface.mode.ConfigScope
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 真实 DataStore 文件上的模式隔离（R06）。 */
class ModeScopedDataStoreTest {

    private val dir: File = Files.createTempDirectory("fflue_ds").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val physical = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "launcher_settings.preferences_pb") }
    private val mode = MutableStateFlow(ConfigScope.FULL)
    private val store = ModeScopedDataStore(physical, mode)

    private val face = stringPreferencesKey("selected_watchface_id")
    private val scale = intPreferencesKey("global_ui_scale_percent")

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun modesKeepSeparateValuesInOnePhysicalStore() = runBlocking {
        store.edit { it[face] = "full_face"; it[scale] = 95 }
        mode.value = ConfigScope.DISGUISE
        assertNull(store.data.first()[face])
        assertEquals(95, store.data.first()[scale])
        store.edit { it[face] = "plain_face" }

        mode.value = ConfigScope.FULL
        assertEquals("full_face", store.data.first()[face])

        val raw = physical.data.first()
        assertEquals("full_face", raw[face])
        assertEquals("plain_face", raw[stringPreferencesKey(DISGUISE_PREFIX + "selected_watchface_id")])
    }

    @Test
    fun pinnedWriteLandsInSubmittingModeAfterSwitch() = runBlocking {
        // 完整模式下提交、切到伪装后才执行的排队写入，仍写回完整模式。
        val pin = ConfigScopePin(ConfigScope.FULL)
        mode.value = ConfigScope.DISGUISE
        withContext(pin) { store.edit { it[face] = "queued_full_face" } }
        assertNull(store.data.first()[face])
        mode.value = ConfigScope.FULL
        assertEquals("queued_full_face", store.data.first()[face])
    }

    @Test
    fun dataFollowsScopeChanges() = runBlocking {
        store.edit { it[face] = "full_face" }
        mode.value = ConfigScope.DISGUISE
        store.edit { it[face] = "plain_face" }
        assertEquals("plain_face", store.data.first()[face])
        mode.value = ConfigScope.FULL
        assertEquals("full_face", store.data.first()[face])
    }
}
