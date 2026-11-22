package com.flue.launcher.surface

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the installed R8 APK through public activities and visible controls. The test APK is
 * built separately and signed with the same temporary CI key, so no application classes or
 * generated resource IDs may be referenced here.
 *
 * Run the default phase on a fresh install, force-stop the app externally, then run this class
 * again with `-e release_smoke_phase restore` to check persistence in a new application process.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseUiSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val state = context.getSharedPreferences("_test_release_ui_smoke", Context.MODE_PRIVATE)

    @Test
    fun normalUiFlowAndColdStartupPersistence() {
        assertEquals(PACKAGE_NAME, context.packageName)
        when (InstrumentationRegistry.getArguments().getString("release_smoke_phase") ?: "setup") {
            "setup" -> completeNormalUiFlow()
            "restore" -> verifyColdStartupPersistence()
            else -> throw AssertionError("Unknown release_smoke_phase")
        }
    }

    private fun completeNormalUiFlow() {
        assertTrue(state.edit().clear().commit())
        startLauncher()
        waitForText(string("onboarding_welcome_title"))
        clickText(string("onboarding_start"))
        clickText(string("surface_mode_full"))
        waitForText(string("onboarding_home_title"))
        val defaultHome = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        )?.activityInfo?.packageName
        if (defaultHome == PACKAGE_NAME) {
            clickText(string("onboarding_home_done"))
        } else {
            clickText(string("onboarding_home_skip"))
            clickText(string("onboarding_home_skip_confirm"))
        }
        waitForActivity(LAUNCHER_ACTIVITY)
        verifyFullSettingsAndMode()
        verifyBackupScreens()

        openScreenFit()
        val originalDpi = openDpiPage()
        clickText("+")
        waitForText(string("app_dpi_value", originalDpi + 1))
        clickText(string("display_cancel"))
        waitForText(string("screen_fit_subtitle"))
        assertEquals("Cancel must discard the draft DPI", originalDpi, openDpiPage())

        clickText("+")
        val expectedDpi = originalDpi + 1
        waitForText(string("app_dpi_value", expectedDpi))
        clickText(string("display_save"))
        waitForText(string("screen_fit_subtitle"))
        assertEquals("Save must update the next DPI page", expectedDpi, openDpiPage())
        clickText(string("display_cancel"))
        assertTrue(
            state.edit()
                .putInt("original_dpi", originalDpi)
                .putInt("expected_dpi", expectedDpi)
                .putInt("setup_pid", Process.myPid())
                .putBoolean("setup_complete", true)
                .commit(),
        )
    }

    private fun verifyColdStartupPersistence() {
        try {
            assertTrue("The setup phase must finish first", state.getBoolean("setup_complete", false))
            assertFalse(
                "Run adb force-stop between setup and restore to test a cold application process",
                state.getInt("setup_pid", -1) == Process.myPid(),
            )
            startLauncher()
            waitForActivity(LAUNCHER_ACTIVITY)
            assertFalse(device.hasObject(By.text(string("onboarding_welcome_title"))))
            verifyFullSettingsAndMode()

            openScreenFit()
            val expectedDpi = state.getInt("expected_dpi", -1)
            assertTrue(expectedDpi > 0)
            assertEquals("Saved DPI must survive a cold start", expectedDpi, openDpiPage())
            // Restore the original setting through the same user controls after checking it.
            clickText("−")
            waitForText(string("app_dpi_value", state.getInt("original_dpi", -1)))
            clickText(string("display_save"))
            waitForText(string("screen_fit_subtitle"))
            assertEquals(state.getInt("original_dpi", -1), openDpiPage())
            clickText(string("display_cancel"))
        } finally {
            assertTrue(state.edit().clear().commit())
        }
    }

    private fun verifyFullSettingsAndMode() {
        startSettings()
        waitForText(string("settings_root_title"))
        findText(string("settings_appearance"))
        startSettings("surface")
        waitForText(string("surface_settings_subtitle"))
        findText(string("surface_mode_current"))
        findText(string("surface_mode_full"))
    }

    private fun verifyBackupScreens() {
        startSettings("tools")
        clickText(string("settings_backup_menu"))
        clickText(string("settings_backup_import_page"))
        findText(string("settings_backup_import_pick"))
        swipeBack()
        clickText(string("settings_backup_export_page"))
        findText(string("backup_item_settings"))
        findText(string("settings_backup_export_start"))
        swipeBack()
        findText(string("settings_backup_import_page"))
        swipeBack()
        assertTrue("Back must preserve the parent scroll position", device.hasObject(By.text(string("settings_backup_menu"))))
    }

    private fun swipeBack() {
        val y = device.displayHeight / 5
        device.swipe(device.displayWidth / 4, y, device.displayWidth * 19 / 20, y, 25)
        settle()
    }

    private fun openScreenFit() {
        startSettings("screen_fit")
        waitForText(string("screen_fit_subtitle"))
    }

    private fun openDpiPage(): Int {
        clickText(string("app_dpi_title"))
        waitForText(string("app_dpi_hint"))
        val value = find(By.text(Pattern.compile("[0-9]+\\s+DPI")), "current DPI").text
        return value.substringBefore(' ').toInt()
    }

    private fun startLauncher() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(PACKAGE_NAME, LAUNCHER_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        settle()
    }

    private fun startSettings(destination: String? = null) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(PACKAGE_NAME, "com.flue.launcher.SettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (destination != null) intent.putExtra("settings_destination", destination)
        context.startActivity(intent)
        settle()
    }

    private fun string(name: String, vararg arguments: Any): String {
        val id = context.resources.getIdentifier(name, "string", PACKAGE_NAME)
        assertTrue("Installed APK has no string resource $name", id != 0)
        return context.getString(id, *arguments)
    }

    private fun waitForText(text: String) {
        assertTrue(
            "Missing $text, visible text is ${visibleTexts()}",
            device.wait(Until.hasObject(By.text(text)), 10_000),
        )
    }

    private fun clickText(text: String) {
        findText(text)
        settle()
        requireNotNull(device.findObject(By.text(text))) { "Text disappeared before click: $text" }.click()
        settle()
    }

    private fun findText(text: String): UiObject2 = find(By.text(text), text)

    private fun find(selector: BySelector, description: String): UiObject2 {
        for (attempt in 0 until 10) {
            device.wait(Until.findObject(selector), 1500)?.let { return it }
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                40,
            )
            settle()
        }
        throw AssertionError("Missing $description, visible text is ${visibleTexts()}")
    }

    private fun waitForActivity(className: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var resumed = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .map { it.componentName.className }
            }
            if (className in resumed) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected resumed $className, found $resumed, visible text is ${visibleTexts()}")
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle(2000)
        SystemClock.sleep(400)
    }

    private fun visibleTexts(): List<String> = device.findObjects(By.textContains(""))
        .mapNotNull { runCatching { it.text }.getOrNull() }
        .filter(String::isNotBlank)

    private companion object {
        const val PACKAGE_NAME = "com.gaozai.surface"
        const val LAUNCHER_ACTIVITY = "com.flue.launcher.LauncherActivity"
    }
}
