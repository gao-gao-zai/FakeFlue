package com.flue.launcher.surface

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.flue.launcher.EXTRA_SETTINGS_DESTINATION
import com.flue.launcher.R
import com.flue.launcher.SETTINGS_DESTINATION_SCREEN_FIT
import com.flue.launcher.SettingsActivity
import com.flue.launcher.surface.display.DisplayAdaptation
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.viewmodel.LauncherViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Standard Android accessibility actions and actual pointer input on the app's DPI page. */
@RunWith(AndroidJUnit4::class)
class SliderAccessibilityJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var originalStyle: String? = null

    @Before
    fun setUp() {
        val runtime = SurfaceRuntime.get(context)
        runtime.stateStore.welcomeCompleted = true
        instrumentation.runOnMainSync { runtime.controller.switchTo(SurfaceMode.FULL) }
        originalStyle = runBlocking {
            context.launcherSettings.data.first()[LauncherViewModel.KEY_UI_STYLE]
        }
        clearDisplayConfig()
    }

    @After
    fun tearDown() {
        finishSettings()
        runBlocking {
            context.launcherSettings.edit { prefs ->
                originalStyle?.let { prefs[LauncherViewModel.KEY_UI_STYLE] = it }
                    ?: prefs.remove(LauncherViewModel.KEY_UI_STYLE)
            }
        }
        clearDisplayConfig()
    }

    @Test
    fun allStylesExposeDpiProgressAndKeepAccessibleAndDraggedChangesAsDrafts() {
        for (style in UiStyle.entries) {
            finishSettings()
            runBlocking { context.launcherSettings.edit { it[LauncherViewModel.KEY_UI_STYLE] = style.name } }
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_SCREEN_FIT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            waitForText(string(R.string.screen_fit_subtitle))
            clickText(string(R.string.app_dpi_title))
            findSlider(style)
            val original = DisplayAdaptation.effectiveDpi(context)
            val requested = if (original < 620) original + 11 else original - 11
            val node = sliderNode()
            assertEquals("$style minimum", 80f, node.rangeInfo.min)
            assertEquals("$style maximum", 640f, node.rangeInfo.max)
            assertEquals("$style current", original.toFloat(), node.rangeInfo.current)
            assertTrue("$style SetProgress was rejected", node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, requested.toFloat()) },
            ))
            settle()
            assertEquals("$style accessible change", requested.toFloat(), sliderNode().rangeInfo.current)
            assertNull("$style accessibility must not save the draft", DisplayAdaptation.savedDpi(context))

            val bounds = findSlider(style)
            val endFraction = if (requested > 450) 0.2f else 0.8f
            device.swipe(bounds.centerX(), bounds.centerY(),
                bounds.left + (bounds.width() * endFraction).toInt(), bounds.centerY(), 35)
            settle()
            assertTrue("$style drag did not change the draft", sliderNode().rangeInfo.current != requested.toFloat())
            assertNull("$style drag must not save the draft", DisplayAdaptation.savedDpi(context))

            // The slider owns horizontal dragging, while the page retains its back gesture.
            val y = device.displayHeight / 10
            device.swipe(device.displayWidth / 4, y, device.displayWidth * 19 / 20, y, 25)
            waitForText(string(R.string.screen_fit_subtitle))
            assertNull("$style back must discard the draft", DisplayAdaptation.savedDpi(context))
        }
    }

    private fun findSlider(style: UiStyle): Rect {
        for (attempt in 0 until 12) {
            device.findObjects(By.pkg(context.packageName).clazz("android.widget.SeekBar"))
                // MIUIX uses a 24dp track; accessibility bounds need not match Apple/M3 height.
                .firstOrNull { !it.visibleBounds.isEmpty && it.visibleBounds.width() > device.displayWidth / 3 }
                ?.let { return Rect(it.visibleBounds) }
            scrollDown()
        }
        val candidates = device.findObjects(By.pkg(context.packageName).clazz("android.widget.SeekBar"))
            .map { it.visibleBounds }
        error("$style: No visible DPI slider; bounds=$candidates; texts=${visibleTexts()}")
    }

    private fun sliderNode(): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.SeekBar" && node.packageName == context.packageName && node.isVisibleToUser) {
                return node
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                find(child)?.let { return it }
            }
            return null
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertNotNull("No active accessibility window", root)
        return requireNotNull(find(root!!)) { "No accessible DPI slider: ${visibleTexts()}" }
    }

    private fun clickText(text: String) {
        for (attempt in 0 until 12) {
            device.wait(Until.findObject(By.text(text)), 1000)?.let { it.click(); settle(); return }
            scrollDown()
        }
        error("Missing $text: ${visibleTexts()}")
    }

    private fun scrollDown() {
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 35)
        settle()
    }

    private fun waitForText(text: String) {
        assertTrue("Missing $text: ${visibleTexts()}", device.wait(Until.hasObject(By.text(text)), 10_000))
    }

    private fun finishSettings() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }.distinct()
                .filterIsInstance<SettingsActivity>().forEach(Activity::finish)
        }
        settle()
    }

    private fun clearDisplayConfig() {
        context.getSharedPreferences("fflue_display", Context.MODE_PRIVATE).edit().clear().commit()
        DisplayAdaptation.resetCacheForTest()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle(2000)
        SystemClock.sleep(500)
    }

    private fun string(id: Int): String = context.getString(id)
    private fun visibleTexts(): List<String> = device.findObjects(By.textContains(""))
        .mapNotNull { runCatching { it.text }.getOrNull() }
}
