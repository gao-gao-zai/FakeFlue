package com.flue.launcher.surface

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.flue.launcher.LauncherActivity
import com.flue.launcher.R
import com.flue.launcher.WatchStoreActivity
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.mode.DefaultHomeResult
import com.flue.launcher.surface.mode.DisguiseEntryGate
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.mode.SurfaceModeController
import com.flue.launcher.surface.mode.SurfaceStateStore
import com.flue.launcher.viewmodel.dataStore
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

/** P1 模拟器旅程：完整 ↔ 伪装切换、配置隔离、重启保持、页面守卫、默认桌面跳过。 */
@RunWith(AndroidJUnit4::class)
class SurfaceModeJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val runtime = SurfaceRuntime.get(context)
    private val device = UiDevice.getInstance(instrumentation)
    private val face = stringPreferencesKey("selected_watchface_id")

    @Before
    fun setUp() {
        runtime.stateStore.welcomeCompleted = true
        // 进入伪装需要基础配置（含可读的隐藏密码）和当前版本的教程成绩（R12、R19、R22）。
        if (runtime.secretStore.read() !is com.flue.launcher.surface.secret.SecretRead.Available) {
            assertTrue(runtime.secretStore.save(listOf(1, 2, 3, 4, 5, 1)))
        }
        com.flue.launcher.surface.setup.DisguiseSetupStep.entries.forEach(runtime::markDisguiseSetupStep)
        runtime.recordTutorialPassed()
        runOnMain { runtime.controller.switchTo(SurfaceMode.FULL) }
    }

    @After
    fun tearDown() {
        runOnMain { runtime.controller.switchTo(SurfaceMode.FULL) }
        runtime.stateStore.welcomeCompleted = true
    }

    @Test
    fun switchingModesIsolatesSettingsAndSurvivesRestart() = runBlocking {
        val settings = context.launcherSettings
        settings.edit { it[face] = "journey_full" }

        assertEquals(ModeSwitchResult.Success, runOnMain { runtime.controller.switchTo(SurfaceMode.DISGUISE) })
        settings.edit { it[face] = "journey_disguise" }
        assertEquals("journey_disguise", settings.data.first()[face])

        // “重启”：新控制器从持久化状态读回最后的模式。
        val restarted = SurfaceModeController(SurfaceStateStore(context), DisguiseEntryGate { null }, NoEffects)
        assertEquals(SurfaceMode.DISGUISE, restarted.mode.value)

        assertEquals(ModeSwitchResult.Success, runOnMain { runtime.controller.switchTo(SurfaceMode.FULL) })
        assertEquals("journey_full", settings.data.first()[face])
        val raw = context.dataStore.data.first()
        assertEquals("journey_full", raw[face])
        assertEquals(
            "journey_disguise",
            raw[stringPreferencesKey(LauncherSettingsScopes.DISGUISE_PREFIX + "selected_watchface_id")],
        )
    }

    @Test
    fun restrictedActivityIsClosedInDisguise() {
        runOnMain { runtime.controller.switchTo(SurfaceMode.DISGUISE) }
        context.startActivity(Intent(context, WatchStoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
        assertFalse("伪装模式下表盘市场不能停留在前台", resumedActivities().any { it is WatchStoreActivity })
    }

    @Test
    fun onboardingSkipRecordsSkippedAndCompletes() {
        runtime.stateStore.welcomeCompleted = false
        runtime.stateStore.defaultHomeResult = DefaultHomeResult.UNKNOWN
        context.startActivity(
            Intent(context, LauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        clickText(context.getString(R.string.onboarding_start))
        clickText(context.getString(R.string.surface_mode_full))
        if (SurfaceHome.isSelfDefaultHome(context)) {
            clickText(context.getString(R.string.onboarding_home_done))
            waitUntil { runtime.stateStore.welcomeCompleted }
            assertEquals(DefaultHomeResult.CONFIRMED, runtime.stateStore.defaultHomeResult)
        } else {
            clickText(context.getString(R.string.onboarding_home_skip))
            // 第一次只显示后果说明，不算完成。
            assertFalse(runtime.stateStore.welcomeCompleted)
            clickText(context.getString(R.string.onboarding_home_skip_confirm))
            waitUntil { runtime.stateStore.welcomeCompleted }
            assertEquals(DefaultHomeResult.SKIPPED, runtime.stateStore.defaultHomeResult)
        }
        assertEquals(SurfaceMode.FULL, runtime.controller.mode.value)
    }

    private fun clickText(text: String) {
        var found = false
        for (attempt in 0 until 8) {
            if (device.wait(Until.hasObject(By.text(text)), 3000)) {
                found = true
                break
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 40)
            settle()
        }
        assertTrue("找不到“$text”，屏幕上有：${visibleTexts()}", found)
        // 滚动停下后重新定位再点，避免点在惯性滑动中的旧位置。
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

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue(condition())
    }

    private fun resumedActivities(): Collection<Activity> {
        var result: Collection<Activity> = emptyList()
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return result
    }

    private fun <T> runOnMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private object NoEffects : com.flue.launcher.surface.mode.ModeSwitchEffects {
        override fun onEnteredRestrictedMode() = Unit
        override fun onLeftRestrictedMode() = Unit
        override fun ownPackageName() = "com.gaozai.surface"
        override fun isLaunchableHome(flattenedComponent: String) = false
        override fun launchExternalHome(flattenedComponent: String) = false
        override fun defaultHomePackage(): String? = null
    }
}
