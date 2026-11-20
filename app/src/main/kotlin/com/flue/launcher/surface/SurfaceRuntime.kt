package com.flue.launcher.surface

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.util.Log
import com.flue.launcher.surface.access.SurfaceActivityGuard
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.config.ModeScopedDataStore
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.DisguiseEntryGate
import com.flue.launcher.surface.mode.ModeSwitchEffects
import com.flue.launcher.surface.mode.SurfaceModeController
import com.flue.launcher.surface.mode.SurfaceStateStore
import com.flue.launcher.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import com.flue.launcher.surface.apps.PublicAppRules
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * FFlue 模式内核的进程级单例：模式控制器、按模式投影的设置存储与页面守卫。
 *
 * [FlueApplication] 启动时调用 [install]；其他地方用 [get] 取，未安装时按需创建，
 * 保证测试或早期组件拿到的也是同一个实例。
 */
class SurfaceRuntime private constructor(private val app: Application) {

    val stateStore = SurfaceStateStore(app)

    val activityGuard = SurfaceActivityGuard()

    private val effects = AndroidModeSwitchEffects(app, activityGuard)

    val controller = SurfaceModeController(
        storage = stateStore,
        // P1 占位：专属配置与教程门槛在 P4 接入（R07、R18、R22）。
        gate = DisguiseEntryGate { null },
        effects = effects,
    )

    /** 原版代码读写 `launcher_settings` 时应使用的存储（R06）。 */
    val launcherSettings: DataStore<Preferences> = ModeScopedDataStore(app.dataStore, controller.configScope)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 当前模式的公开应用白名单（R10）：完整／外部模式为 null（不限制），伪装模式为已选应用。
     * 冷启动在伪装模式时先按空集合处理，避免设置读出来之前露出全部应用。
     */
    val publicApps: StateFlow<Set<String>?> = combine(app.dataStore.data, controller.configScope) { prefs, scope ->
        if (scope == ConfigScope.DISGUISE) {
            val name = LauncherSettingsScopes.physicalName(PublicAppRules.KEY, ConfigScope.DISGUISE)
            PublicAppRules.parse(prefs[stringPreferencesKey(name)])
        } else {
            null
        }
    }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            if (controller.configScope.value == ConfigScope.DISGUISE) emptySet() else null,
        )

    /** 按包名判断当前模式是否允许展示或启动该应用（R08、R11）。 */
    fun isPackagePublic(packageName: String?): Boolean =
        PublicAppRules.isPackagePublic(packageName, publicApps.value, app.packageName)

    /** 保存伪装模式的公开应用白名单（只在伪装配置里写）。 */
    suspend fun savePublicApps(entries: Set<String>) {
        app.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(LauncherSettingsScopes.physicalName(PublicAppRules.KEY, ConfigScope.DISGUISE))] =
                PublicAppRules.serialize(entries)
        }
    }

    /** 读伪装模式的公开应用白名单，不论当前模式（管理页在完整模式下也能配置）。 */
    suspend fun disguisePublicApps(): Set<String> {
        val name = LauncherSettingsScopes.physicalName(PublicAppRules.KEY, ConfigScope.DISGUISE)
        return PublicAppRules.parse(app.dataStore.data.first()[stringPreferencesKey(name)])
    }

    /**
     * 桌面 Activity 收到启动 Intent 时调用。
     *
     * - 首次使用流程没走完：打开引导（R18）。
     * - 外部模式下系统仍把 HOME 交给本应用：转交给用户选定的外部桌面，不把用户拉回 FFlue（R05）。
     *   从应用图标打开仍正常显示 FFlue（按完整配置）。
     *
     * @return true 表示已经转交给别的界面。
     */
    fun onLauncherIntent(context: Context, isHomeIntent: Boolean): Boolean {
        if (!stateStore.welcomeCompleted) {
            com.flue.launcher.surface.onboarding.SurfaceOnboardingActivity.start(context)
            return true
        }
        if (isHomeIntent && controller.mode.value == com.flue.launcher.surface.mode.SurfaceMode.EXTERNAL) {
            val target = controller.externalTarget ?: return false
            return effects.isLaunchableHome(target) && effects.launchExternalHome(target)
        }
        return false
    }

    /** 另一模式当前选中的表盘 ID，用于表盘写时复制（R06）。 */
    suspend fun selectedWatchFaceOfOtherMode(): String? {
        val other = when (controller.configScope.value) {
            ConfigScope.FULL -> ConfigScope.DISGUISE
            ConfigScope.DISGUISE -> ConfigScope.FULL
        }
        val physical = app.dataStore.data.first()
        val name = LauncherSettingsScopes.physicalName(KEY_SELECTED_WATCHFACE, other)
        return physical.asMap().entries.firstOrNull { it.key.name == name }?.value as? String
    }

    companion object {
        private const val KEY_SELECTED_WATCHFACE = "selected_watchface_id"

        @Volatile
        private var instance: SurfaceRuntime? = null

        fun install(app: Application): SurfaceRuntime {
            val runtime = get(app)
            runtime.activityGuard.attach(app, runtime.controller)
            return runtime
        }

        fun get(context: Context): SurfaceRuntime {
            val app = context.applicationContext as Application
            instance?.takeIf { it.app === app }?.let { return it }
            return synchronized(this) {
                // 正式运行时只有一个 Application；单元测试每个用例会换一个，此时重建。
                instance?.takeIf { it.app === app } ?: SurfaceRuntime(app).also { instance = it }
            }
        }
    }
}

/** 当前模式是否允许使用该功能（R08）；伪装模式下不公开的功能入口和执行点都用它判断。 */
fun Context.surfaceAllows(feature: com.flue.launcher.surface.access.SurfaceFeature): Boolean =
    com.flue.launcher.surface.access.FeatureAccessPolicy.isAllowed(SurfaceRuntime.get(this).controller.mode.value, feature)

/** 按当前模式投影的 `launcher_settings`；原版里 `context.dataStore` 的模式感知替代品。 */
val Context.launcherSettings: DataStore<Preferences>
    get() = SurfaceRuntime.get(this).launcherSettings

private class AndroidModeSwitchEffects(
    private val app: Application,
    private val guard: SurfaceActivityGuard,
) : ModeSwitchEffects {

    override fun onEnteredRestrictedMode() {
        guard.enforceNow()
    }

    override fun onLeftRestrictedMode() {
        // P5：解除伪装专属系统限制。
    }

    override fun ownPackageName(): String = app.packageName

    override fun isLaunchableHome(flattenedComponent: String): Boolean {
        val component = ComponentName.unflattenFromString(flattenedComponent) ?: return false
        if (component.packageName == app.packageName) return false
        return homeActivities(app.packageManager).any { it == component }
    }

    override fun launchExternalHome(flattenedComponent: String): Boolean {
        val component = ComponentName.unflattenFromString(flattenedComponent) ?: return false
        return runCatching {
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }.onFailure { Log.w(TAG, "launch external home failed: $flattenedComponent", it) }.isSuccess
    }

    override fun defaultHomePackage(): String? = SurfaceHome.defaultHomePackage(app)

    private companion object {
        const val TAG = "SurfaceMode"
    }
}

/** HOME 相关的系统查询。 */
object SurfaceHome {
    fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** 系统当前默认 HOME 的包名；没有默认项（会弹选择器）时为 null。 */
    fun defaultHomePackage(context: Context): String? {
        val info = context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        val pkg = info.activityInfo?.packageName ?: return null
        // 没有默认项时解析到系统选择器（ResolverActivity，包名 "android"）。
        return pkg.takeUnless { it == "android" }
    }

    fun isSelfDefaultHome(context: Context): Boolean = defaultHomePackage(context) == context.packageName

    /** 除本应用外可作为外部桌面的 HOME Activity。 */
    fun externalHomeCandidates(context: Context): List<ComponentName> =
        homeActivities(context.packageManager).filter { it.packageName != context.packageName }
}

@Suppress("DEPRECATION")
private fun homeActivities(pm: PackageManager): List<ComponentName> =
    pm.queryIntentActivities(SurfaceHome.homeIntent(), 0)
        .mapNotNull { it.activityInfo }
        .filter { it.packageName != "android" }
        .map { ComponentName(it.packageName, it.name) }
        .distinct()

/**
 * 当前模式的私有媒体根目录（R06）。
 *
 * 内置图片／视频表盘、表盘字体和应用列表背景每次写入都会先清空目录；两种模式共用目录时，
 * 一个模式换图会删掉另一模式正在用的文件。完整模式保持原版路径（`filesDir`），
 * 伪装模式放在 `filesDir/fflue_disguise` 下。
 */
fun Context.modeMediaFilesDir(): java.io.File = when (SurfaceRuntime.get(this).controller.configScope.value) {
    ConfigScope.FULL -> filesDir
    ConfigScope.DISGUISE -> java.io.File(filesDir, DISGUISE_MEDIA_DIR)
}

const val DISGUISE_MEDIA_DIR = "fflue_disguise"
