package com.flue.launcher.surface.entry

import android.content.Context
import android.content.Intent
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.mode.DisguiseEntryRequirement
import com.flue.launcher.surface.mode.ModeSwitchResult
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.surface.onboarding.SurfaceOnboardingActivity
import com.flue.launcher.surface.setup.DisguiseSetupActivity
import com.flue.launcher.surface.tutorial.TutorialActivity
import com.flue.launcher.surface.ui.reportModeSwitch

/** 这次进入伪装的请求从哪里发起；配置和教程走完后据此继续（R18、R22）。 */
enum class EntryOrigin {
    /** 只是配置或重学，不进入伪装。 */
    NONE,

    /** 设置 → 桌面模式 → 进入伪装桌面。 */
    SETTINGS,

    /** 首次使用选择了伪装桌面；进入后继续默认桌面引导。 */
    ONBOARDING,
    ;

    companion object {
        const val EXTRA = "com.flue.launcher.extra.DISGUISE_ENTRY_ORIGIN"

        fun from(intent: Intent?): EntryOrigin =
            intent?.getStringExtra(EXTRA)?.let { name -> entries.firstOrNull { it.name == name } } ?: NONE
    }
}

/**
 * 所有进入伪装模式的界面入口（R18、R22）。门槛统一由 [com.flue.launcher.surface.mode.SurfaceModeController]
 * 判断；这里只负责缺什么就打开什么：缺基础配置开配置向导，缺教程成绩开教程。
 */
object DisguiseEntry {

    /** 请求进入伪装。成功时按来源继续；被拦下时打开对应页面。 */
    fun request(context: Context, origin: EntryOrigin): ModeSwitchResult {
        val result = SurfaceRuntime.get(context).controller.switchTo(SurfaceMode.DISGUISE)
        when (result) {
            is ModeSwitchResult.Blocked -> openRequirement(context, result.requirement, origin)
            ModeSwitchResult.Success, ModeSwitchResult.AlreadyInMode -> {
                context.reportModeSwitch(result, SurfaceMode.DISGUISE)
                continueEntered(context, origin)
            }
            else -> context.reportModeSwitch(result, SurfaceMode.DISGUISE)
        }
        return result
    }

    fun openRequirement(context: Context, requirement: DisguiseEntryRequirement, origin: EntryOrigin) {
        val intent = when (requirement) {
            DisguiseEntryRequirement.BASE_CONFIGURATION -> DisguiseSetupActivity.intent(context, origin)
            DisguiseEntryRequirement.TUTORIAL -> TutorialActivity.intent(context, origin)
        }
        context.startActivity(intent.addFlagsIfNeeded(context))
    }

    /** 已经进入伪装：首次使用回到默认桌面引导，其余回到表盘。 */
    private fun continueEntered(context: Context, origin: EntryOrigin) {
        when (origin) {
            EntryOrigin.ONBOARDING -> SurfaceOnboardingActivity.resumeDefaultHome(context)
            EntryOrigin.SETTINGS -> runCatching { context.startActivity(SurfaceHome.returnToFaceIntent(context)) }
            EntryOrigin.NONE -> Unit
        }
    }

    private fun Intent.addFlagsIfNeeded(context: Context): Intent =
        if (context is android.app.Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
