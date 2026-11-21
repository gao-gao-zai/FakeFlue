package com.flue.launcher.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.config.DisguiseBackup
import com.flue.launcher.surface.DISGUISE_MEDIA_DIR
import com.flue.launcher.surface.apps.PublicAppOverrides
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.viewmodel.dataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R37：导入结果如实报告、失败不删旧资源。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FlueBackupImportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun resetSettings() {
        // DataStore 是进程级单例，跨测试会保留内存里的值；每个用例从空配置开始。
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    private val validWatchXml =
        "<Watch name=\"Backup\" shape=\"R\"><Layer type=\"text\" text=\"12:34\" /></Watch>".toByteArray()

    private fun innerWatch(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("watch.xml"))
            zip.write(validWatchXml)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun backup(manifest: JSONObject, vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun baseManifest() = JSONObject().put("schemaVersion", 1).put("appVersion", "test")

    private fun runImport(bytes: ByteArray, options: FlueBackupOptions = FlueBackupOptions()) = runBlocking {
        FlueBackupManager.importBackupArchive(
            context = context,
            openInput = { ByteArrayInputStream(bytes) },
            options = options,
            availableAppKeys = null,
            validWidgetKeys = emptySet(),
        )
    }

    private fun entry(type: String, value: Any) = JSONObject().put("type", type).put("value", value)

    @Test
    fun `failed watch face is reported as a partial failure`() {
        val manifest = baseManifest()
            .put("settings", JSONObject().put("blur_enabled", entry("boolean", false)))
            .put("watchFiles", JSONObject().put("archives", JSONArray().put("watchfaces/watch/good.watch").put("watchfaces/watch/bad.watch")))
        val result = runImport(
            backup(
                manifest,
                "watchfaces/watch/good.watch" to innerWatch(),
                "watchfaces/watch/bad.watch" to "not a zip".toByteArray(),
            ),
        )

        assertFalse(result.isCompleteSuccess)
        assertFalse(result.nothingRestored)
        assertEquals(1, result.restoredWatchFaces)
        assertEquals(1, result.failedWatchFaces)
        assertTrue(FlueBackupOptions.SECTION_SETTINGS in result.restoredSections)
        assertTrue(result.issues.any { it.section == FlueBackupOptions.SECTION_WATCH_FILES && it.item == "bad.watch" })
        assertEquals(false, runBlocking { context.dataStore.data.first()[booleanPreferencesKey("blur_enabled")] })
    }

    @Test
    fun `zero successful watch faces is reported as nothing restored`() {
        val manifest = baseManifest()
            .put("watchFiles", JSONObject().put("archives", JSONArray().put("watchfaces/watch/bad.watch").put("watchfaces/watch/missing.watch")))
        val result = runImport(backup(manifest, "watchfaces/watch/bad.watch" to "garbage".toByteArray()))

        assertTrue(result.nothingRestored)
        assertEquals(0, result.restoredWatchFaces)
        assertEquals(2, result.failedWatchFaces)
        assertEquals(listOf(FlueBackupOptions.SECTION_WATCH_FILES), result.failedSections)
    }

    @Test
    fun `missing photo media keeps the old photo file and preference`() {
        val photoDir = File(context.filesDir, "internal_watchfaces/photo").apply { mkdirs() }
        val oldPhoto = File(photoDir, "current_old.png").apply { writeText("old photo") }
        runBlocking {
            context.dataStore.edit {
                it[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH] = oldPhoto.absolutePath
                it[LauncherViewModel.KEY_PHOTO_CLOCK_SIZE] = 40
            }
        }
        val manifest = baseManifest().put(
            "photoWatchFace",
            JSONObject()
                .put("preferences", JSONObject().put("photo_clock_size", entry("int", 80)))
                .put("mediaEntry", "watchfaces/photo/media.png")
                .put("fontEntry", JSONObject.NULL),
        )

        val result = runImport(backup(manifest), FlueBackupOptions(photoWatchFace = true))

        assertTrue(result.nothingRestored)
        assertTrue(oldPhoto.isFile)
        assertEquals("old photo", oldPhoto.readText())
        val prefs = runBlocking { context.dataStore.data.first() }
        assertEquals(oldPhoto.absolutePath, prefs[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH])
        assertEquals("整个分区不写：本机原设置保持不变", 40, prefs[intPreferencesKey("photo_clock_size")])
    }

    @Test
    fun `restored photo media replaces the old file only after commit`() {
        val photoDir = File(context.filesDir, "internal_watchfaces/photo").apply { mkdirs() }
        val oldPhoto = File(photoDir, "current_old.png").apply { writeText("old photo") }
        val manifest = baseManifest().put(
            "photoWatchFace",
            JSONObject()
                .put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/photo/media.png")
                .put("fontEntry", JSONObject.NULL),
        )

        val result = runImport(backup(manifest, "watchfaces/photo/media.png" to "new photo".toByteArray()))

        assertTrue(result.isCompleteSuccess)
        val path = runBlocking { context.dataStore.data.first()[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH] }!!
        assertNotEquals(oldPhoto.absolutePath, path)
        assertEquals("new photo", File(path).readText())
        assertFalse(oldPhoto.exists())
    }

    @Test
    fun `newer backup format is refused without touching settings`() {
        runBlocking { context.dataStore.edit { it[booleanPreferencesKey("blur_enabled")] = true } }
        val manifest = JSONObject().put("schemaVersion", BACKUP_SCHEMA_VERSION + 1)
            .put("settings", JSONObject().put("blur_enabled", entry("boolean", false)))

        val error = assertThrows(FlueBackupFormatException::class.java) { runImport(backup(manifest)) }

        assertEquals(FlueBackupFormatException.Reason.NEWER_SCHEMA, error.reason)
        assertEquals(true, runBlocking { context.dataStore.data.first()[booleanPreferencesKey("blur_enabled")] })
    }

    @Test
    fun `type mismatched settings never reach the store`() {
        val manifest = baseManifest().put(
            "settings",
            JSONObject()
                .put("blur_enabled", entry("string", "true"))
                .put("not_a_setting", entry("int", 3))
                .put("honeycomb_cols", entry("int", 4)),
        )

        val result = runImport(backup(manifest), FlueBackupOptions(settings = true))

        assertEquals(2, result.skippedSettings)
        val prefs = runBlocking { context.dataStore.data.first() }
        assertEquals(4, prefs[intPreferencesKey("honeycomb_cols")])
        assertFalse(prefs.asMap().keys.any { it.name == "blur_enabled" || it.name == "not_a_setting" })
    }

    @Test
    fun `manifest reference outside the archive is not imported`() {
        File(context.filesDir, "outside.png").writeText("private data")
        val manifest = baseManifest().put(
            "photoWatchFace",
            JSONObject().put("preferences", JSONObject()).put("mediaEntry", "../../files/outside.png").put("fontEntry", JSONObject.NULL),
        )

        val result = runImport(backup(manifest), FlueBackupOptions(photoWatchFace = true))

        assertTrue(result.nothingRestored)
        assertEquals(null, runBlocking { context.dataStore.data.first()[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH] })
    }

    @Test
    fun `missing font discards the staged photo and preserves existing media`() {
        val photoDir = File(context.filesDir, "internal_watchfaces/photo").apply { mkdirs() }
        val oldPhoto = File(photoDir, "current_old.png").apply { writeText("old photo") }
        val filesBefore = photoDir.listFiles()!!.map { it.name }.toSet()
        runBlocking { context.dataStore.edit { it[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH] = oldPhoto.absolutePath } }
        val manifest = baseManifest().put(
            "photoWatchFace",
            JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/photo/media.png")
                .put("fontEntry", "watchfaces/font/missing.ttf"),
        )

        val result = runImport(backup(manifest, "watchfaces/photo/media.png" to "new photo".toByteArray()))

        assertTrue(result.nothingRestored)
        assertEquals(oldPhoto.absolutePath, runBlocking { context.dataStore.data.first()[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH] })
        assertEquals("old photo", oldPhoto.readText())
        assertEquals(filesBefore, photoDir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `missing photo discards its staged font and preserves the old font`() {
        val fontDir = File(context.filesDir, "internal_watchfaces/font").apply { mkdirs() }
        val oldFont = File(fontDir, "current_old.ttf").apply { writeText("old font") }
        val filesBefore = fontDir.listFiles()!!.map { it.name }.toSet()
        runBlocking { context.dataStore.edit { it[LauncherViewModel.KEY_WATCHFACE_FONT_PATH] = oldFont.absolutePath } }
        val manifest = baseManifest().put(
            "photoWatchFace",
            JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/photo/missing.png")
                .put("fontEntry", "watchfaces/font/current.ttf"),
        )

        val result = runImport(backup(manifest, "watchfaces/font/current.ttf" to "new font".toByteArray()))

        assertTrue(result.nothingRestored)
        assertEquals(oldFont.absolutePath, runBlocking { context.dataStore.data.first()[LauncherViewModel.KEY_WATCHFACE_FONT_PATH] })
        assertEquals("old font", oldFont.readText())
        assertEquals(filesBefore, fontDir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `successful photo leaves the font used by a failed video in place`() {
        val fontDir = File(context.filesDir, "internal_watchfaces/font").apply { mkdirs() }
        val oldFont = File(fontDir, "current_old.ttf").apply { writeText("old font") }
        runBlocking { context.dataStore.edit { it[LauncherViewModel.KEY_WATCHFACE_FONT_PATH] = oldFont.absolutePath } }
        val manifest = baseManifest()
            .put("photoWatchFace", JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/photo/media.png").put("fontEntry", JSONObject.NULL))
            .put("videoWatchFace", JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/video/missing.mp4").put("fontEntry", "watchfaces/font/unused.ttf"))

        val result = runImport(backup(manifest,
            "watchfaces/photo/media.png" to "new photo".toByteArray(),
            "watchfaces/font/unused.ttf" to "unused font".toByteArray()))

        assertEquals(listOf(FlueBackupOptions.SECTION_PHOTO_WATCH_FACE), result.restoredSections)
        val prefs = runBlocking { context.dataStore.data.first() }
        assertEquals("new photo", File(prefs[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH]!!).readText())
        assertEquals(oldFont.absolutePath, prefs[LauncherViewModel.KEY_WATCHFACE_FONT_PATH])
        assertEquals("old font", oldFont.readText())
        assertFalse(fontDir.listFiles()!!.any { it.readText() == "unused font" })
    }

    @Test
    fun `adopted font preserves the old file still referenced by the unselected disguise mode`() {
        val fontDir = File(context.filesDir, "internal_watchfaces/font").apply { mkdirs() }
        val oldFont = File(fontDir, "current_old.ttf").apply { writeText("shared old font") }
        val disguiseFontKey = stringPreferencesKey(LauncherSettingsScopes.physicalName(
            LauncherViewModel.KEY_WATCHFACE_FONT_PATH.name, ConfigScope.DISGUISE))
        runBlocking { context.dataStore.edit {
            it[LauncherViewModel.KEY_WATCHFACE_FONT_PATH] = oldFont.absolutePath
            it[disguiseFontKey] = oldFont.absolutePath
        } }
        val manifest = baseManifest()
            .put("photoWatchFace", JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/photo/media.png").put("fontEntry", "watchfaces/font/adopted.ttf"))
            .put("videoWatchFace", JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/video/missing.mp4").put("fontEntry", "watchfaces/font/unused.ttf"))

        val result = runImport(backup(manifest,
            "watchfaces/photo/media.png" to "new photo".toByteArray(),
            "watchfaces/font/adopted.ttf" to "adopted font".toByteArray(),
            "watchfaces/font/unused.ttf" to "unused font".toByteArray()))

        assertEquals(listOf(FlueBackupOptions.SECTION_PHOTO_WATCH_FACE), result.restoredSections)
        val prefs = runBlocking { context.dataStore.data.first() }
        assertEquals("adopted font", File(prefs[LauncherViewModel.KEY_WATCHFACE_FONT_PATH]!!).readText())
        assertEquals(oldFont.absolutePath, prefs[disguiseFontKey])
        assertEquals("shared old font", oldFont.readText())
        assertFalse(fontDir.listFiles()!!.any { it.readText() == "unused font" })
    }

    @Test
    fun `font staged for a failed photo can still be adopted by a successful video`() {
        val manifest = baseManifest()
            .put("photoWatchFace", JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/photo/missing.png").put("fontEntry", "watchfaces/font/shared.ttf"))
            .put("videoWatchFace", JSONObject().put("preferences", JSONObject())
                .put("mediaEntry", "watchfaces/video/media.mp4").put("fontEntry", "watchfaces/font/shared.ttf"))

        val result = runImport(backup(manifest,
            "watchfaces/video/media.mp4" to "new video".toByteArray(),
            "watchfaces/font/shared.ttf" to "shared font".toByteArray()))

        assertEquals(listOf(FlueBackupOptions.SECTION_VIDEO_WATCH_FACE), result.restoredSections)
        val prefs = runBlocking { context.dataStore.data.first() }
        assertEquals("new video", File(prefs[LauncherViewModel.KEY_BUILTIN_VIDEO_PATH]!!).readText())
        assertEquals("shared font", File(prefs[LauncherViewModel.KEY_WATCHFACE_FONT_PATH]!!).readText())
    }

    @Test
    fun `missing public icon preserves its old file while other settings restore`() {
        val iconName = "old.png"
        val iconDir = File(File(context.filesDir, DISGUISE_MEDIA_DIR), PublicAppOverrides.ICON_DIR).apply { mkdirs() }
        val oldIcon = File(iconDir, iconName).apply { writeText("old icon") }
        val overrideName = LauncherSettingsScopes.physicalName(PublicAppOverrides.KEY, ConfigScope.DISGUISE)
        val overrideValue = JSONObject().put("pkg/.Activity", JSONObject().put("icon", iconName)).toString()
        runBlocking { context.dataStore.edit { it[stringPreferencesKey(overrideName)] = overrideValue } }
        val manifest = baseManifest()
            .put("settings", JSONObject().put(overrideName, entry("string", overrideValue)))
            .put(DisguiseBackup.MANIFEST_KEY, JSONObject().put("publicIcons",
                JSONArray().put(DisguiseBackup.PUBLIC_ICONS_PREFIX + iconName)))

        val result = runImport(backup(manifest))

        assertTrue(FlueBackupOptions.SECTION_SETTINGS in result.restoredSections)
        assertTrue(result.issues.any { it.item == "publicIcon:$iconName" })
        assertEquals("old icon", oldIcon.readText())
        val prefs = runBlocking { context.dataStore.data.first() }
        assertEquals(iconName, PublicAppOverrides.parse(prefs[stringPreferencesKey(overrideName)])["pkg/.Activity"]?.iconFile)
    }
}
