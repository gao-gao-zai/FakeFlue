package com.flue.launcher.surface.mode

import android.content.Context
import android.content.SharedPreferences

/**
 * 设备级 FFlue 状态（R06）：当前模式、外部桌面目标、首次使用各步骤成绩。
 *
 * 这些值不属于任何模式，切换模式时不能互相覆盖，所以不放在 `launcher_settings` 里，
 * 单独保存在 `fflue_surface_state`。写入用 `commit()`：模式切换要先确认落盘，再算成功（R07）。
 */
class SurfaceStateStore(private val prefs: SharedPreferences) : SurfaceStateStorage {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    override fun readMode(): SurfaceMode? = SurfaceMode.fromStored(prefs.getString(KEY_MODE, null))

    override fun writeMode(mode: SurfaceMode): Boolean = prefs.edit().putString(KEY_MODE, mode.name).commit()

    override fun readExternalTarget(): String? = prefs.getString(KEY_EXTERNAL_TARGET, null)

    override fun writeExternalTarget(flattenedComponent: String): Boolean =
        prefs.edit().putString(KEY_EXTERNAL_TARGET, flattenedComponent).commit()

    /** R18：欢迎流程（选择模式 + 默认桌面引导，完成或跳过）是否已走完。 */
    var welcomeCompleted: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WELCOME_COMPLETED, value).commit()
        }

    /** R18：默认桌面引导的结果。只有读回系统实际 HOME 为本应用时才是 [DefaultHomeResult.CONFIRMED]。 */
    var defaultHomeResult: DefaultHomeResult
        get() = DefaultHomeResult.entries.firstOrNull { it.name == prefs.getString(KEY_DEFAULT_HOME_RESULT, null) }
            ?: DefaultHomeResult.UNKNOWN
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_HOME_RESULT, value.name).commit()
        }

    /** R18/R19：伪装专属配置里已确认的步骤（[com.flue.launcher.surface.setup.DisguiseSetupStep] 的名字）。 */
    var disguiseSetupSteps: Set<String>
        get() = prefs.getStringSet(KEY_DISGUISE_SETUP_STEPS, null)?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_DISGUISE_SETUP_STEPS, value.toSet()).commit()
        }

    /** R22：通过的教程版本；0 表示没有成绩。 */
    var tutorialVersion: Int
        get() = prefs.getInt(KEY_TUTORIAL_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_TUTORIAL_VERSION, value).commit()
        }

    /** R19：退出伪装时优先的去处（完整 FFlue 或外部桌面），管理菜单据此排序。 */
    var preferredExit: SurfaceMode
        get() = if (prefs.getString(KEY_PREFERRED_EXIT, null) == SurfaceMode.EXTERNAL.name) SurfaceMode.EXTERNAL else SurfaceMode.FULL
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_EXIT, value.name).commit()
        }

    /**
     * R28、R29：用户在授权页主动检测过 Root 且拿到了 uid 0。只有这时才会启动导航守护，
     * 日常使用不因此弹出 Root 授权。检测失败会清掉。
     */
    var rootConfirmed: Boolean
        get() = prefs.getBoolean(KEY_ROOT_CONFIRMED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ROOT_CONFIRMED, value).commit()
        }

    /** R34：快速进入用的实体按键；[android.view.KeyEvent.KEYCODE_UNKNOWN] 表示关闭。 */
    var quickKeyCode: Int
        get() = prefs.getInt(KEY_QUICK_KEY_CODE, android.view.KeyEvent.KEYCODE_UNKNOWN)
        set(value) {
            prefs.edit().putInt(KEY_QUICK_KEY_CODE, value).commit()
        }

    var quickKeyPattern: com.flue.launcher.surface.entry.KeyPattern
        get() = com.flue.launcher.surface.entry.KeyPattern.parse(prefs.getString(KEY_QUICK_KEY_PATTERN, null))
            ?: com.flue.launcher.surface.entry.KeyPattern.TRIPLE
        set(value) {
            prefs.edit().putString(KEY_QUICK_KEY_PATTERN, value.name).commit()
        }

    companion object {
        const val FILE_NAME = "fflue_surface_state"
        const val KEY_DISGUISE_SETUP_STEPS = "disguise_setup_steps"
        const val KEY_TUTORIAL_VERSION = "tutorial_version"
        const val KEY_PREFERRED_EXIT = "preferred_exit"
        const val KEY_ROOT_CONFIRMED = "root_confirmed"
        const val KEY_QUICK_KEY_CODE = "quick_key_code"
        const val KEY_QUICK_KEY_PATTERN = "quick_key_pattern"
        const val KEY_MODE = "mode"
        const val KEY_EXTERNAL_TARGET = "external_home_target"
        const val KEY_WELCOME_COMPLETED = "welcome_completed"
        const val KEY_DEFAULT_HOME_RESULT = "default_home_result"
    }
}

enum class DefaultHomeResult { UNKNOWN, CONFIRMED, SKIPPED }
