package com.flue.launcher.surface

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.AppListItemType
import com.flue.launcher.surface.apps.SelfModules
import com.flue.launcher.surface.comm.CommRole
import com.flue.launcher.surface.comm.CommStore
import com.flue.launcher.surface.comm.InCallActivity
import com.flue.launcher.surface.comm.MessagesActivity
import com.flue.launcher.surface.comm.PhoneActivity
import com.flue.launcher.surface.comm.RestoreOutcome
import com.flue.launcher.surface.comm.RestoreProgress
import com.flue.launcher.surface.comm.RoleRecord
import com.flue.launcher.surface.comm.SmsRules
import com.flue.launcher.surface.comm.SmsSendState
import com.flue.launcher.surface.comm.SmsSendTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R32 通信状态持久化、桌面锁豁免和内置模块识别（需要 Android 运行时）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CommStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = CommStore(context.getSharedPreferences("comm_test", Context.MODE_PRIVATE))

    @Test
    fun roleRecordsPersistIncludingEmptyOriginal() {
        val a = store()
        assertEquals(RoleRecord.NONE, a.read(CommRole.DIALER))
        assertTrue(a.write(CommRole.DIALER, RoleRecord(true, "com.android.dialer")))
        assertTrue(a.write(CommRole.SMS, RoleRecord(true, null)))
        val b = store()
        assertEquals(RoleRecord(true, "com.android.dialer"), b.read(CommRole.DIALER))
        assertEquals(RoleRecord(true, null), b.read(CommRole.SMS))
        b.write(CommRole.DIALER, RoleRecord.NONE)
        assertEquals(RoleRecord.NONE, store().read(CommRole.DIALER))
    }

    @Test
    fun restoreProgressSurvivesProcessRebuild() {
        val progress = RestoreProgress(
            active = true,
            roles = setOf(CommRole.DIALER, CommRole.SMS),
            done = setOf(CommRole.SMS),
            skipped = emptySet(),
            chosen = mapOf(CommRole.DIALER to "com.b"),
            failed = mapOf(CommRole.DIALER to RestoreOutcome.EMPTY),
            attempted = mapOf(CommRole.DIALER to "com.b", CommRole.SMS to "com.android.mms"),
            awaiting = CommRole.DIALER,
        )
        assertTrue(store().writeProgress(progress))
        assertEquals(progress, store().readProgress())

        store().writeProgress(progress.copy(awaiting = null, failed = emptyMap(), chosen = emptyMap()))
        val read = store().readProgress()
        assertNull(read.awaiting)
        assertTrue(read.failed.isEmpty())
        assertTrue(read.chosen.isEmpty())

        store().writeProgress(RestoreProgress.IDLE)
        assertEquals(RestoreProgress.IDLE, store().readProgress())
        assertFalse(store().readProgress().active)
    }

    @Test
    fun smsSendReceiptsPersistPerMessage() {
        val tracker = SmsSendTracker(2)
        tracker.report(0, SmsRules.RESULT_OK)
        store().writeSend("content://sms/1", tracker)
        val read = store().readSend("content://sms/1")!!
        assertEquals(SmsSendState.SENDING, read.state)
        assertEquals(SmsSendState.SENT, read.report(1, SmsRules.RESULT_OK))
        store().clearSend("content://sms/1")
        assertNull(store().readSend("content://sms/1"))
    }

    @Test
    fun desktopLockNeverCoversInCallScreen() {
        assertTrue(SurfaceRuntime.isDesktopLockExempt(Robolectric.buildActivity(InCallActivity::class.java).get()))
        assertFalse(SurfaceRuntime.isDesktopLockExempt(Robolectric.buildActivity(PhoneActivity::class.java).get()))
        assertFalse(SurfaceRuntime.isDesktopLockExempt(Robolectric.buildActivity(MessagesActivity::class.java).get()))
    }

    @Test
    fun desktopLockCoversToolPagesUnlessAnAlertIsRinging() {
        val tool = com.flue.launcher.surface.tools.AlarmToolActivity::class.java
        assertFalse(SurfaceRuntime.isDesktopLockExempt(Robolectric.buildActivity(tool).get()))
        // 带着提醒标记但没有任何闹钟或计时器在响：仍然被锁拦（外部伪造的标记绕不过去）。
        val forged = android.content.Intent(context, tool)
            .putExtra(com.flue.launcher.surface.tools.ToolActivity.EXTRA_FROM_ALERT, true)
        assertFalse(SurfaceRuntime.isDesktopLockExempt(Robolectric.buildActivity(tool, forged).get()))
        assertTrue(SelfModules.isModule(context.packageName, tool.name, context.packageName))
    }

    @Test
    fun builtInModulesAreAppEntriesOfOwnPackage() {
        val own = context.packageName
        val icon = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        fun app(pkg: String, activity: String, type: AppListItemType = AppListItemType.APP, settings: Boolean = false) =
            AppInfo(label = "x", packageName = pkg, activityName = activity, cachedIcon = icon, cachedBlurredIcon = icon,
                isBuiltInSettingsEntry = settings, itemType = type)
        assertTrue(SelfModules.isModule(app(own, PhoneActivity::class.java.name), own))
        assertFalse(SelfModules.isModule(app(own, "com.flue.launcher.LauncherActivity"), own))
        assertFalse(SelfModules.isModule(app(own, PhoneActivity::class.java.name, AppListItemType.SHORTCUT), own))
        assertFalse(SelfModules.isModule(app(own, PhoneActivity::class.java.name, settings = true), own))
        assertFalse(SelfModules.isModule(app("com.other", PhoneActivity::class.java.name), own))
    }
}
