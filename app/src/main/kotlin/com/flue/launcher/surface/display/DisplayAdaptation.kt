package com.flue.launcher.surface.display

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * 设备级显示适配（R23、R24）。完整模式和伪装模式共用这一份配置，不经过模式投影。
 *
 * 用独立的同步 SharedPreferences：密度要在 attachBaseContext 里读，DataStore 那时还不可用。
 */
object DisplayAdaptation {
    private const val PREFS_NAME = "fflue_display"
    private const val KEY_APP_DPI = "app_dpi"
    private const val KEY_SAFE_WIDTH = "safe_width_percent"
    private const val KEY_SAFE_HEIGHT = "safe_height_percent"
    private const val KEY_SAFE_CORNER = "safe_corner_percent"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveActivities = mutableListOf<WeakReference<Activity>>()
    private var installed = false

    @Volatile
    private var safeAreaState: MutableStateFlow<SafeAreaSpec?>? = null

    private fun prefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- R24 独立 DPI ----

    /** 设备出厂的稳定密度，不受用户在系统里调的 DPI 影响。 */
    fun stableDpi(): Int = DisplayMetrics.DENSITY_DEVICE_STABLE

    fun savedDpi(context: Context): Int? = runCatching {
        prefs(context).takeIf { it.contains(KEY_APP_DPI) }?.getInt(KEY_APP_DPI, 0)
    }.getOrNull()?.let(AppDensity::clamp)

    fun effectiveDpi(context: Context): Int = AppDensity.effective(savedDpi(context), stableDpi())

    /** 同步保存。传入稳定默认值等同恢复默认。 */
    fun saveDpi(context: Context, dpi: Int): Boolean = runCatching {
        val stored = AppDensity.toStored(dpi, stableDpi())
        prefs(context).edit().apply {
            if (stored == null) remove(KEY_APP_DPI) else putInt(KEY_APP_DPI, stored)
        }.commit()
    }.getOrDefault(false)

    /**
     * 返回按应用 DPI 排版的 Context。在 Application / Activity 的 attachBaseContext 里调用，
     * 新页面第一帧就是最终密度。dp 屏幕尺寸同步换算，保持像素尺寸不变。
     */
    fun applyDensity(context: Context): Context {
        val config = densityConfiguration(context.resources.configuration, effectiveDpi(context)) ?: return context
        return context.createConfigurationContext(config)
    }

    /** 目标密度下的 Configuration；已经是目标密度时返回 null。 */
    internal fun densityConfiguration(current: Configuration, targetDpi: Int): Configuration? {
        if (current.densityDpi == targetDpi || current.densityDpi <= 0) return null
        val config = Configuration(current)
        config.densityDpi = targetDpi
        if (current.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
            config.screenWidthDp = AppDensity.rescaleDp(current.screenWidthDp, current.densityDpi, targetDpi)
        }
        if (current.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            config.screenHeightDp = AppDensity.rescaleDp(current.screenHeightDp, current.densityDpi, targetDpi)
        }
        if (current.smallestScreenWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            config.smallestScreenWidthDp =
                AppDensity.rescaleDp(current.smallestScreenWidthDp, current.densityDpi, targetDpi)
        }
        return config
    }

    // ---- R23 安全区 ----

    /** 已保存的安全区；null 表示未校准，由界面按屏幕形状取 [SafeAreaSpec.default]。 */
    fun safeArea(context: Context): StateFlow<SafeAreaSpec?> {
        safeAreaState?.let { return it.asStateFlow() }
        synchronized(this) {
            val state = safeAreaState ?: MutableStateFlow(readSafeArea(context)).also { safeAreaState = it }
            return state.asStateFlow()
        }
    }

    private fun readSafeArea(context: Context): SafeAreaSpec? = runCatching {
        val p = prefs(context)
        if (!p.contains(KEY_SAFE_WIDTH)) return@runCatching null
        SafeAreaSpec(
            widthPercent = p.getFloat(KEY_SAFE_WIDTH, SafeAreaSpec.MAX_SIZE_PERCENT),
            heightPercent = p.getFloat(KEY_SAFE_HEIGHT, SafeAreaSpec.MAX_SIZE_PERCENT),
            cornerPercent = p.getFloat(KEY_SAFE_CORNER, 0f),
        ).normalized()
    }.getOrNull()

    fun saveSafeArea(context: Context, spec: SafeAreaSpec): Boolean {
        val normalized = spec.normalized()
        val ok = runCatching {
            prefs(context).edit()
                .putFloat(KEY_SAFE_WIDTH, normalized.widthPercent)
                .putFloat(KEY_SAFE_HEIGHT, normalized.heightPercent)
                .putFloat(KEY_SAFE_CORNER, normalized.cornerPercent)
                .commit()
        }.getOrDefault(false)
        if (ok) {
            safeArea(context)
            safeAreaState?.value = normalized
        }
        return ok
    }

    /** 测试用：丢掉内存里缓存的安全区，下次从 SharedPreferences 重读。 */
    internal fun resetCacheForTest() {
        safeAreaState = null
    }

    // ---- 生效 ----

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                synchronized(liveActivities) { liveActivities += WeakReference(activity) }
            }

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(liveActivities) {
                    liveActivities.removeAll { it.get() == null || it.get() === activity }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** DPI 保存后重建所有已打开的页面，让它们在 attachBaseContext 里拿到新密度。 */
    fun recreateAll() {
        mainHandler.post {
            val snapshot = synchronized(liveActivities) { liveActivities.mapNotNull { it.get() } }
            snapshot.filter { !it.isFinishing && !it.isDestroyed }.forEach { it.recreate() }
        }
    }
}
