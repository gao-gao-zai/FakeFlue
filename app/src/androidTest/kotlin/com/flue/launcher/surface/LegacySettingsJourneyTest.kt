package com.flue.launcher.surface

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.flue.launcher.EXTRA_SETTINGS_DESTINATION
import com.flue.launcher.InternalWatchFaceConfigActivity
import com.flue.launcher.R
import com.flue.launcher.SETTINGS_DESTINATION_TOOLS
import com.flue.launcher.SettingsActivity
import com.flue.launcher.surface.display.DisplayAdaptation
import com.flue.launcher.surface.display.SafeAreaSpec
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.ui.common.FlueScreenShape
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the original settings lists and editor, including their actual input handlers. */
@RunWith(AndroidJUnit4::class)
class LegacySettingsJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var originalScale: Int? = null
    private var originalShape: String? = null
    private var originalUiStyle: String? = null

    @Before
    fun setUp() {
        val runtime = SurfaceRuntime.get(context)
        runtime.stateStore.welcomeCompleted = true
        instrumentation.runOnMainSync { runtime.controller.switchTo(SurfaceMode.FULL) }
        runBlocking {
            val prefs = context.launcherSettings.data.first()
            originalScale = prefs[LauncherViewModel.KEY_GLOBAL_UI_SCALE_PERCENT]
            originalShape = prefs[LauncherViewModel.KEY_SCREEN_SHAPE_OVERRIDE]
            originalUiStyle = prefs[LauncherViewModel.KEY_UI_STYLE]
            context.launcherSettings.edit {
                it[LauncherViewModel.KEY_GLOBAL_UI_SCALE_PERCENT] = 100
                it[LauncherViewModel.KEY_SCREEN_SHAPE_OVERRIDE] = FlueScreenShape.SQUARE.name
            }
        }
        clearDisplayConfig()
    }

    @After
    fun tearDown() {
        // Destroy the pages before restoring preferences so a pending edit cannot overwrite them.
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }.distinct()
                .filter { it is SettingsActivity || it is InternalWatchFaceConfigActivity }
                .forEach(Activity::finish)
        }
        settle()
        runBlocking {
            context.launcherSettings.edit { prefs ->
                originalScale?.let { prefs[LauncherViewModel.KEY_GLOBAL_UI_SCALE_PERCENT] = it }
                    ?: prefs.remove(LauncherViewModel.KEY_GLOBAL_UI_SCALE_PERCENT)
                originalShape?.let { prefs[LauncherViewModel.KEY_SCREEN_SHAPE_OVERRIDE] = it }
                    ?: prefs.remove(LauncherViewModel.KEY_SCREEN_SHAPE_OVERRIDE)
                originalUiStyle?.let { prefs[LauncherViewModel.KEY_UI_STYLE] = it }
                    ?: prefs.remove(LauncherViewModel.KEY_UI_STYLE)
            }
        }
        clearDisplayConfig()
    }

    @Test
    fun savedSafeAreaResizesOpenSettingsAndPersistsWhenReopened() {
        startSettings(SETTINGS_DESTINATION_TOOLS)
        val full = viewport()
        val spec = SafeAreaSpec(75f, 70f, 0f)
        assertTrue(DisplayAdaptation.saveSafeArea(context, spec))
        settle()
        assertViewport(full, spec)

        startSettings(SETTINGS_DESTINATION_TOOLS)
        assertViewport(full, spec)
        findText(string(R.string.settings_backup_menu))
    }

    @Test
    fun minimumSafeAreaKeepsRoundSettingsScrollable() {
        startSettings(SETTINGS_DESTINATION_TOOLS)
        val full = viewport()
        instrumentation.runOnMainSync {
            settingsViewModel().setScreenShapeOverride(FlueScreenShape.ROUND)
        }
        val spec = SafeAreaSpec(55f, 55f, 50f)
        assertTrue(DisplayAdaptation.saveSafeArea(context, spec))
        settle()
        assertViewport(full, spec)
        // Backup navigation must remain reachable with the smallest supported viewport.
        findText(string(R.string.settings_backup_menu))
        clickText(string(R.string.settings_backup_menu))
        findText(string(R.string.settings_backup_import_page))
        swipeBack()
        assertTrue(device.hasObject(By.text(string(R.string.settings_backup_menu))))
    }

    @Test
    fun swipeBackUsesTheSettingsStackAndRetainsTheParentScrollPosition() {
        startSettings(SETTINGS_DESTINATION_TOOLS)
        clickText(string(R.string.settings_backup_menu))
        clickText(string(R.string.settings_backup_import_page))
        findText(string(R.string.settings_backup_import_pick))
        swipeBack()
        findText(string(R.string.settings_backup_export_page))
        swipeBack()
        // Check immediately, without scrolling the parent to make the assertion pass.
        assertTrue("Parent scroll position was lost", device.hasObject(By.text(string(R.string.settings_backup_menu))))
        swipeBack()
        findText(string(R.string.settings_root_title))
    }

    @Test
    fun aSliderConsumesHorizontalDragAndThePageStillScrollsVertically() {
        startSettings()
        // Material 3 exposes a standard SeekBar node. The custom Apple slider has no such
        // accessibility node, so use this style for a real pointer-input regression.
        instrumentation.runOnMainSync { settingsViewModel().setUiStyle(UiStyle.MATERIAL_3) }
        settle()
        clickText(string(R.string.settings_appearance))
        clickText(string(R.string.settings_display_fit))
        findText(string(R.string.settings_global_ui_size))
        val slider = findSlider()
        val bounds = slider.visibleBounds
        val before = settingsScale()
        assertTrue(bounds.width() > device.displayWidth / 4)
        device.swipe(
            bounds.centerX(), bounds.centerY(),
            bounds.left + bounds.width() * 4 / 5, bounds.centerY(), 35,
        )
        settle()
        assertTrue("Horizontal slider input did not update the setting", settingsScale() > before)
        assertFalse("Slider drag navigated back", device.hasObject(By.text(string(R.string.settings_section_layout))))
        // A vertical drag still belongs to the existing list rather than the back handler.
        findText(string(R.string.settings_honeycomb_cols))
        swipeBack()
        findText(string(R.string.settings_display_fit))
    }

    @Test
    fun builtInPhotoAndVideoEditorsUseSafeAreaAndSwipeBack() {
        startSettings(SETTINGS_DESTINATION_TOOLS)
        val spec = SafeAreaSpec(75f, 75f, 0f)
        for ((id, title) in listOf(
            BUILT_IN_PHOTO_WATCHFACE_ID to R.string.internal_watchface_photo_title,
            BUILT_IN_VIDEO_WATCHFACE_ID to R.string.internal_watchface_video_title,
        )) {
            assertTrue(DisplayAdaptation.saveSafeArea(context, SafeAreaSpec.default(roundScreen = false)))
            context.startActivity(
                Intent(context, InternalWatchFaceConfigActivity::class.java)
                    .putExtra("internal_watchface_id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            settle()
            assertTrue(resumedActivity() is InternalWatchFaceConfigActivity)
            findText(string(title))
            // Each Activity owns its own window insets, so measure this editor's baseline.
            val full = viewport()
            assertTrue(DisplayAdaptation.saveSafeArea(context, spec))
            settle()
            assertViewport(full, spec)
            swipeBack()
            assertTrue("Editor did not return to settings", resumedActivity() is SettingsActivity)
        }
    }

    private fun startSettings(destination: String? = null) {
        val intent = Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (destination != null) intent.putExtra(EXTRA_SETTINGS_DESTINATION, destination)
        context.startActivity(intent)
        settle()
        assertTrue(resumedActivity() is SettingsActivity)
    }

    private fun settingsViewModel(): LauncherViewModel =
        ViewModelProvider(resumedActivity() as SettingsActivity)[LauncherViewModel::class.java]

    private fun settingsScale(): Int {
        var value = -1
        instrumentation.runOnMainSync { value = settingsViewModel().globalUiScalePercent.value }
        return value
    }

    private fun resumedActivity(): Activity {
        var activity: Activity? = null
        val read = Runnable {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) read.run() else instrumentation.runOnMainSync(read)
        return requireNotNull(activity) { "No resumed activity" }
    }

    private fun viewport(): Rect {
        val node = device.wait(Until.findObject(By.pkg(context.packageName).scrollable(true)), 5000)
        assertNotNull("Settings scroll viewport is absent", node)
        return Rect(node!!.visibleBounds)
    }

    private fun assertViewport(full: Rect, spec: SafeAreaSpec) {
        val actual = viewport()
        val horizontal = full.width() * (100f - spec.widthPercent) / 200f
        val vertical = full.height() * (100f - spec.heightPercent) / 200f
        assertEquals("viewport left", full.left + horizontal, actual.left.toFloat(), 2f)
        assertEquals("viewport right", full.right - horizontal, actual.right.toFloat(), 2f)
        assertEquals("viewport top", full.top + vertical, actual.top.toFloat(), 2f)
        assertEquals("viewport bottom", full.bottom - vertical, actual.bottom.toFloat(), 2f)
    }

    private fun findSlider(): UiObject2 {
        for (attempt in 0 until 12) {
            device.findObjects(By.clazz("android.widget.SeekBar"))
                .firstOrNull { it.visibleBounds.height() > 12 && it.visibleBounds.width() > device.displayWidth / 4 }
                ?.let { return it }
            scrollDown()
        }
        error("No usable slider, visible text: ${visibleTexts()}")
    }

    private fun findText(text: String): UiObject2 {
        for (attempt in 0 until 20) {
            device.wait(Until.findObject(By.text(text)), 1000)?.let { return it }
            scrollDown()
        }
        error("Missing $text, visible text: ${visibleTexts()}")
    }

    private fun clickText(text: String) {
        findText(text).click()
        settle()
    }

    private fun scrollDown() {
        val bounds = viewport()
        device.swipe(bounds.centerX(), bounds.top + bounds.height() * 4 / 5,
            bounds.centerX(), bounds.top + bounds.height() / 5, 35)
        settle()
    }

    private fun swipeBack() {
        val y = device.displayHeight / 5
        device.swipe(device.displayWidth / 4, y, device.displayWidth * 19 / 20, y, 25)
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle(2000)
        SystemClock.sleep(500)
    }

    private fun clearDisplayConfig() {
        context.getSharedPreferences("fflue_display", Context.MODE_PRIVATE).edit().clear().commit()
        DisplayAdaptation.resetCacheForTest()
    }

    private fun string(id: Int): String = context.getString(id)
    private fun visibleTexts(): List<String> = device.findObjects(By.textContains(""))
        .mapNotNull { runCatching { it.text }.getOrNull() }
}
