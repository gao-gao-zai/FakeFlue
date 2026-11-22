package com.flue.launcher.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import com.flue.launcher.applist.background.AppListBackgroundConfig
import com.flue.launcher.applist.background.AppListBackgroundJson
import com.flue.launcher.applist.background.AppListBackgroundMode
import com.flue.launcher.surface.config.ConfigScopePin
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.config.ModeScopedDataStore
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import com.flue.launcher.watchface.InternalWatchFaceStorage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class MediaSettingsFilesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val viewModels = ViewModelStore()
    private val photoKey = LauncherViewModel.KEY_BUILTIN_PHOTO_PATH
    private val fontKey = LauncherViewModel.KEY_WATCHFACE_FONT_PATH

    @Before
    fun resetPreferences() {
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @After
    fun releaseViewModels() {
        viewModels.clear()
    }

    private fun mediaFile(folder: String, name: String, content: String): File =
        File(context.filesDir, "internal_watchfaces/$folder/$name").apply {
            parentFile!!.mkdirs()
            writeText(content)
        }

    @Test
    fun `failed settings commit removes the new media and preserves the old reference`() {
        val old = mediaFile("photo", "old.png", "old")
        val created = File(old.parentFile, "new.png")
        runBlocking { context.dataStore.edit { it[photoKey] = old.absolutePath } }

        assertThrows(IOException::class.java) {
            runBlocking {
                MediaSettingsFiles.replace(context, writeNew = {
                    created.writeText("new")
                    created.absolutePath
                }) { throw IOException("settings write failed") }
            }
        }

        assertTrue(old.isFile)
        assertFalse(created.exists())
        assertEquals(old.absolutePath, runBlocking { context.dataStore.data.first()[photoKey] })
        runBlocking {
            MediaSettingsFiles.replace(context, writeNew = {
                created.writeText("retry")
                created.absolutePath
            }) { path -> context.dataStore.edit { it[photoKey] = path } }
        }
        assertFalse(old.exists())
        assertEquals("retry", created.readText())
        assertEquals(created.absolutePath, runBlocking { context.dataStore.data.first()[photoKey] })
    }

    @Test
    fun `cancellation after staging recycles the new file before configuration changes`() = runBlocking {
        val old = mediaFile("photo", "old.png", "old")
        val created = File(old.parentFile, "new.png")
        context.dataStore.edit { it[photoKey] = old.absolutePath }
        val job = launch {
            val operation = currentCoroutineContext()[Job]!!
            MediaSettingsFiles.replace(context, writeNew = {
                created.writeText("new")
                operation.cancel()
                created.absolutePath
            }) { path -> context.dataStore.edit { it[photoKey] = path } }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(old.isFile)
        assertFalse(created.exists())
        assertEquals(old.absolutePath, context.dataStore.data.first()[photoKey])
    }

    @Test
    fun `cancellation after configuration commit finishes cleanup and keeps the adopted file`() = runBlocking {
        val old = mediaFile("photo", "old.png", "old")
        val created = File(old.parentFile, "new.png")
        context.dataStore.edit { it[photoKey] = old.absolutePath }
        val settingsSaved = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val job = launch {
            MediaSettingsFiles.replace(context, writeNew = {
                created.writeText("new")
                created.absolutePath
            }) { path ->
                context.dataStore.edit { it[photoKey] = path }
                settingsSaved.complete(Unit)
                finishCommit.await()
            }
        }
        withTimeout(5_000) { settingsSaved.await() }
        job.cancel()
        finishCommit.complete(Unit)
        job.join()

        assertTrue(created.isFile)
        assertFalse(old.exists())
        assertEquals(created.absolutePath, context.dataStore.data.first()[photoKey])
    }

    @Test
    fun `font replacement and reset preserve files referenced by the other mode`() = runBlocking {
        val old = mediaFile("font", "old.ttf", "shared")
        val created = File(old.parentFile, "new.ttf")
        val disguiseFont = stringPreferencesKey(LauncherSettingsScopes.physicalName(fontKey.name, ConfigScope.DISGUISE))
        context.dataStore.edit {
            it[fontKey] = old.absolutePath
            it[disguiseFont] = old.absolutePath
        }

        MediaSettingsFiles.replace(context, writeNew = {
            created.writeText("new")
            created.absolutePath
        }) { path -> context.dataStore.edit { it[fontKey] = path } }

        assertTrue(old.isFile)
        assertTrue(created.isFile)
        MediaSettingsFiles.edit(context) { context.dataStore.edit { it.remove(disguiseFont) } }
        assertFalse(old.exists())
        assertTrue(created.isFile)
        assertEquals(created.absolutePath, context.dataStore.data.first()[fontKey])
    }

    @Test
    fun `failed clear keeps the referenced file`() = runBlocking {
        val old = mediaFile("photo", "old.png", "old")
        context.dataStore.edit { it[photoKey] = old.absolutePath }
        try {
            MediaSettingsFiles.edit(context) { throw IOException("settings write failed") }
            throw AssertionError("Expected settings failure")
        } catch (_: IOException) {
            assertTrue(old.isFile)
            assertEquals(old.absolutePath, context.dataStore.data.first()[photoKey])
        }
    }

    @Test
    fun `switching modes during staging uses the original directory and configuration scope`() = runBlocking {
        val mode = MutableStateFlow(ConfigScope.FULL)
        val scoped = ModeScopedDataStore(context.dataStore, mode)
        val root = MediaSettingsFiles.mediaRoot(context, mode.value)
        val source = File(context.cacheDir, "source.mp4").apply { writeText("new video") }
        val videoKey = LauncherViewModel.KEY_BUILTIN_VIDEO_PATH
        val disguiseVideo = stringPreferencesKey(LauncherSettingsScopes.physicalName(videoKey.name, ConfigScope.DISGUISE))
        context.dataStore.edit { it[disguiseVideo] = "keep-disguise.mp4" }

        val path = withContext(ConfigScopePin(ConfigScope.FULL)) {
            MediaSettingsFiles.replace(context, writeNew = {
                mode.value = ConfigScope.DISGUISE
                InternalWatchFaceStorage.copyVideo(context, Uri.fromFile(source), root)
            }) { saved -> scoped.edit { it[videoKey] = saved } }
        }

        val preferences = context.dataStore.data.first()
        assertEquals(path, preferences[videoKey])
        assertEquals("keep-disguise.mp4", preferences[disguiseVideo])
        assertEquals(File(context.filesDir, "internal_watchfaces/video"), File(path).parentFile)
        assertTrue(File(path).isFile)
    }

    @Test
    fun `rapid mode and blur changes both persist while retaining a newly committed image`() = runBlocking {
        val application = context.applicationContext as Application
        SurfaceRuntime.get(context).controller.switchTo(SurfaceMode.FULL)
        val viewModel = LauncherViewModel(application)
        viewModels.put("media", viewModel)
        val image = mediaFile("app_list_background", "new.png", "new background")

        MediaSettingsFiles.withTransaction {
            viewModel.setAppListBackgroundMode(AppListBackgroundMode.SOLID_CUSTOM)
            viewModel.setAppListBackgroundBlurPercent(20)
            context.dataStore.edit {
                it[LauncherViewModel.KEY_APP_LIST_BACKGROUND] = AppListBackgroundJson.encode(
                    AppListBackgroundConfig.Default.copy(imagePath = image.absolutePath),
                )
            }
        }
        val preferences = withTimeout(10_000) {
            context.dataStore.data.first {
                val background = AppListBackgroundJson.decodeOrDefault(it[LauncherViewModel.KEY_APP_LIST_BACKGROUND])
                background.mode == AppListBackgroundMode.SOLID_CUSTOM && background.blurPercent == 20
            }
        }
        val background = AppListBackgroundJson.decodeOrDefault(preferences[LauncherViewModel.KEY_APP_LIST_BACKGROUND])
        assertEquals(image.absolutePath, background.imagePath)
        assertTrue(image.isFile)
    }
}
