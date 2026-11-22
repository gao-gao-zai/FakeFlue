package com.flue.launcher.surface

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 冒烟测试：确认安装身份正确，桌面能在设备上启动并进入前台。
 * 不依赖 Root、SIM 或手表专有硬件，可在模拟器上运行。
 */
@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun installedPackageUsesFflueIdentity() {
        // R03：安装名称为 FFlue，applicationId 为 com.gaozai.surface，Kotlin namespace 保持原版。
        assertEquals("com.gaozai.surface", context.packageName)
        val launcher = context.packageManager.getActivityInfo(
            ComponentName(context.packageName, "com.flue.launcher.LauncherActivity"),
            0,
        )
        assertEquals("com.flue.launcher", launcher.name.substringBeforeLast('.'))
    }

    @Test
    fun launcherReachesForeground() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            setClassName(context.packageName, "com.flue.launcher.LauncherActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        val shown = device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("桌面在 ${LAUNCH_TIMEOUT_MS}ms 内没有进入前台", shown == true)
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 20_000L
    }
}
