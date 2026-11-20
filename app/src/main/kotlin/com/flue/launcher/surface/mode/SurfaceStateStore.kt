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

    companion object {
        const val FILE_NAME = "fflue_surface_state"
        const val KEY_MODE = "mode"
        const val KEY_EXTERNAL_TARGET = "external_home_target"
        const val KEY_WELCOME_COMPLETED = "welcome_completed"
        const val KEY_DEFAULT_HOME_RESULT = "default_home_result"
    }
}

enum class DefaultHomeResult { UNKNOWN, CONFIRMED, SKIPPED }
