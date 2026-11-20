package com.flue.launcher.surface

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.flue.launcher.EXTRA_SETTINGS_DESTINATION
import com.flue.launcher.R
import com.flue.launcher.SETTINGS_DESTINATION_SCREEN_FIT
import com.flue.launcher.SettingsActivity
import com.flue.launcher.surface.display.DisplayAdaptation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2a 模拟器旅程（R23–R25）：屏幕适配页的取消、保存、右滑返回逐个真实点击检查，
 * 不只看按钮在不在。
 */
@RunWith(AndroidJUnit4::class)
class DisplayAdaptationJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        SurfaceRuntime.get(context).stateStore.welcomeCompleted = true
        clearDisplayConfig()
        context.startActivity(
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_SCREEN_FIT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue(
            "屏幕适配页没有打开，屏幕上有：${visibleTexts()}",
            device.wait(Until.hasObject(By.text(string(R.string.screen_fit_subtitle))), 10_000),
        )
    }

    @After
    fun tearDown() {
        clearDisplayConfig()
    }

    @Test
    fun dpiCancelKeepsOriginalValue() {
        val before = DisplayAdaptation.effectiveDpi(context)
        clickText(string(R.string.app_dpi_title))
        clickText("+")
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.app_dpi_value, before + 1))), 3000))
        clickText(string(R.string.display_cancel))
        assertTrue(device.wait(Until.hasObject(By.text(string(R.string.screen_fit_subtitle))), 5000))
        assertNull("取消不能保存 DPI", DisplayAdaptation.savedDpi(context))
    }

    @Test
    fun dpiSaveAppliesAndReturnsToScreenFit() {
        val before = DisplayAdaptation.effectiveDpi(context)
        clickText(string(R.string.app_dpi_title))
        clickText("+")
        clickText(string(R.string.display_save))
        assertTrue(
            "保存后应回到屏幕适配页，屏幕上有：${visibleTexts()}",
            device.wait(Until.hasObject(By.text(string(R.string.screen_fit_subtitle))), 10_000),
        )
        assertEquals(before + 1, DisplayAdaptation.effectiveDpi(context))
        // 重建后的页面已经按新密度排版。
        var density = 0
        instrumentation.runOnMainSync {
            density = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .first().resources.configuration.densityDpi
        }
        assertEquals(before + 1, density)
    }

    @Test
    fun safeAreaSavesOnlyOnSaveAndSwipeBackCancels() {
        clickText(string(R.string.safe_area_title))
        clickText("−")
        clickText(string(R.string.display_save))
        assertTrue(device.wait(Until.hasObject(By.text(string(R.string.screen_fit_subtitle))), 5000))
        assertEquals(99.9f, DisplayAdaptation.safeArea(context).value!!.widthPercent)

        // 再进入、改动后右滑返回：等于取消。起点避开系统边缘手势区和中央操作区。
        clickText(string(R.string.safe_area_title))
        clickText("−")
        val y = device.displayHeight / 10
        device.swipe(device.displayWidth / 4, y, device.displayWidth * 19 / 20, y, 20)
        assertTrue(
            "右滑没有返回，屏幕上有：${visibleTexts()}",
            device.wait(Until.hasObject(By.text(string(R.string.screen_fit_subtitle))), 5000),
        )
        assertEquals(99.9f, DisplayAdaptation.safeArea(context).value!!.widthPercent)
    }

    private fun clearDisplayConfig() {
        context.getSharedPreferences("fflue_display", Context.MODE_PRIVATE).edit().clear().commit()
        DisplayAdaptation.resetCacheForTest()
    }

    private fun string(id: Int) = context.getString(id)

    private fun clickText(text: String) {
        var found = false
        for (attempt in 0 until 6) {
            if (device.wait(Until.hasObject(By.text(text)), 3000)) {
                found = true
                break
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 40)
            settle()
        }
        assertTrue("找不到“$text”，屏幕上有：${visibleTexts()}", found)
        settle()
        val target: UiObject2? = device.findObject(By.text(text))
        assertNotNull("“$text”消失了，屏幕上有：${visibleTexts()}", target)
        target!!.click()
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle(2000)
        Thread.sleep(600)
    }

    private fun visibleTexts(): List<String> =
        device.findObjects(By.textContains("")).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }
}
