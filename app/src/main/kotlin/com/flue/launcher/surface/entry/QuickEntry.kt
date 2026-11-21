package com.flue.launcher.surface.entry

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.flue.launcher.R
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.SurfaceMode

/**
 * 快速进入伪装桌面的统一落点（R34）：快捷设置磁贴、外部 Intent、实体按键都到这里，
 * 再走 [DisguiseEntry.request] 的同一道门槛（基础配置、教程成绩）。
 *
 * 不读任何 extras：调用方没有办法借这里跳过配置、教程或桌面密码，也拿不到管理资格。
 */
class QuickEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action in ACCEPTED_ACTIONS) {
            DisguiseEntry.request(this, EntryOrigin.QUICK)
        }
        finish()
    }

    companion object {
        /** 供自动化工具使用：`am start -a com.gaozai.surface.action.ENTER_DISGUISE`。 */
        const val ACTION_ENTER_DISGUISE = "com.gaozai.surface.action.ENTER_DISGUISE"

        /** 本应用内部（磁贴、按键服务）使用。 */
        private const val ACTION_INTERNAL = "com.gaozai.surface.action.QUICK_ENTRY_INTERNAL"

        private val ACCEPTED_ACTIONS = setOf(ACTION_ENTER_DISGUISE, ACTION_INTERNAL)

        /** 新建一个不带任何附加数据的请求。 */
        fun intent(context: Context): Intent = Intent(context, QuickEntryActivity::class.java)
            .setAction(ACTION_INTERNAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

/** 系统快捷设置里的“伪装桌面”磁贴（R34）。已经在伪装模式时显示为开启。 */
class DisguiseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val inDisguise = SurfaceRuntime.get(this).controller.mode.value == SurfaceMode.DISGUISE
        tile.state = if (inDisguise) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.surface_quick_tile_label)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val run = { launch() }
        // 系统锁屏时先解锁；不绕过系统锁（R33）。
        if (isLocked) unlockAndRun(run) else run()
    }

    @Suppress("DEPRECATION")
    private fun launch() {
        val intent = QuickEntryActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
