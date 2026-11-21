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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.flue.launcher.surface.apps.NotificationVisibility
import com.flue.launcher.surface.mode.ThirdPartyScreenOff
import com.flue.launcher.surface.apps.HiddenDrawerCatalog
import com.flue.launcher.surface.mode.DisguiseEntryRequirement
import com.flue.launcher.surface.secret.SecretInterruptions
import com.flue.launcher.surface.secret.SecretRead
import com.flue.launcher.surface.secret.SecretStore
import com.flue.launcher.surface.setup.DisguiseSetupRules
import com.flue.launcher.surface.setup.DisguiseSetupStep
import com.flue.launcher.surface.tutorial.TutorialRules
import kotlinx.coroutines.flow.collectLatest
import com.flue.launcher.surface.apps.PublicAppOverride
import com.flue.launcher.surface.apps.PublicAppOverrides

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

    /** 隐藏密码（R12）；设备级，两种模式共用。 */
    val secretStore: SecretStore by lazy { SecretStore.create(app) }

    val controller = SurfaceModeController(
        storage = stateStore,
        // 所有进入伪装的路径都在这里检查：基础配置（含可读的隐藏密码，否则进去后没有回来的入口）
        // 和当前版本的教程成绩（R12、R19、R22）。
        gate = DisguiseEntryGate {
            when {
                !baseConfigured() -> DisguiseEntryRequirement.BASE_CONFIGURATION
                !TutorialRules.passes(stateStore.tutorialVersion) -> DisguiseEntryRequirement.TUTORIAL
                else -> null
            }
        },
        effects = effects,
    )

    /** R19：伪装专属配置里已确认的步骤。 */
    fun disguiseSetupDone(): Set<DisguiseSetupStep> = DisguiseSetupStep.parse(stateStore.disguiseSetupSteps)

    fun markDisguiseSetupStep(step: DisguiseSetupStep) {
        stateStore.disguiseSetupSteps = stateStore.disguiseSetupSteps + step.name
    }

    /** 基础配置完成：全部步骤确认过，且隐藏密码可读（R18、R19）。 */
    fun baseConfigured(): Boolean =
        DisguiseSetupRules.baseConfigured(disguiseSetupDone(), secretStore.read() is SecretRead.Available)

    private val _tutorialVersion = MutableStateFlow(stateStore.tutorialVersion)

    /** 已通过的教程版本（R22）；ADB 跳过也会更新它，前台教程据此继续。 */
    val tutorialVersion: StateFlow<Int> = _tutorialVersion.asStateFlow()

    fun recordTutorialPassed() {
        stateStore.tutorialVersion = TutorialRules.VERSION
        _tutorialVersion.value = TutorialRules.VERSION
    }

    /** 原版代码读写 `launcher_settings` 时应使用的存储（R06）。 */
    val launcherSettings: DataStore<Preferences> = ModeScopedDataStore(app.dataStore, controller.configScope)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 伪装配置里一个逗号分隔集合键的当前值：完整／外部模式为 null，伪装模式冷启动先按空集合。 */
    private fun disguiseSet(key: String): StateFlow<Set<String>?> =
        combine(app.dataStore.data, controller.configScope) { prefs, configScope ->
            if (configScope == ConfigScope.DISGUISE) {
                PublicAppRules.parse(prefs[stringPreferencesKey(disguiseName(key))])
            } else {
                null
            }
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            if (controller.configScope.value == ConfigScope.DISGUISE) emptySet() else null,
        )

    private fun disguiseName(key: String) = LauncherSettingsScopes.physicalName(key, ConfigScope.DISGUISE)

    private suspend fun saveDisguiseSet(key: String, entries: Set<String>) {
        app.dataStore.edit { prefs -> prefs[stringPreferencesKey(disguiseName(key))] = PublicAppRules.serialize(entries) }
    }

    private suspend fun readDisguiseSet(key: String): Set<String> =
        PublicAppRules.parse(app.dataStore.data.first()[stringPreferencesKey(disguiseName(key))])

    /**
     * 当前模式的公开应用白名单（R10）：完整／外部模式为 null（不限制），伪装模式为已选应用。
     * 冷启动在伪装模式时先按空集合处理，避免设置读出来之前露出全部应用。
     */
    val publicApps: StateFlow<Set<String>?> = disguiseSet(PublicAppRules.KEY)

    /** 伪装模式下额外允许展示通知的未公开应用（R11，包名）；完整模式为 null。 */
    val notificationAllowedApps: StateFlow<Set<String>?> = disguiseSet(NotificationVisibility.KEY)

    /** 按包名判断当前模式是否允许展示或启动该应用（R08、R11）。 */
    fun isPackagePublic(packageName: String?): Boolean =
        PublicAppRules.isPackagePublic(packageName, publicApps.value, app.packageName)

    /** 当前模式下该应用的通知是否进通知层（R11）。 */
    fun isNotificationVisible(packageName: String?): Boolean =
        NotificationVisibility.isVisible(packageName, publicApps.value, notificationAllowedApps.value, app.packageName)

    /** 保存伪装模式的公开应用白名单（只在伪装配置里写）。 */
    suspend fun savePublicApps(entries: Set<String>) = saveDisguiseSet(PublicAppRules.KEY, entries)

    /** 读伪装模式的公开应用白名单，不论当前模式（管理页在完整模式下也能配置）。 */
    suspend fun disguisePublicApps(): Set<String> = readDisguiseSet(PublicAppRules.KEY)

    suspend fun saveNotificationAllowedApps(packages: Set<String>) =
        saveDisguiseSet(NotificationVisibility.KEY, packages)

    suspend fun disguiseNotificationAllowedApps(): Set<String> = readDisguiseSet(NotificationVisibility.KEY)

    /** 伪装模式的公开名称／图标覆盖（R10）；完整／外部模式为 null。 */
    val publicOverrides: StateFlow<Map<String, PublicAppOverride>?> =
        combine(app.dataStore.data, controller.configScope) { prefs, configScope ->
            if (configScope == ConfigScope.DISGUISE) {
                PublicAppOverrides.parse(prefs[stringPreferencesKey(disguiseName(PublicAppOverrides.KEY))])
            } else {
                null
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 公开图标文件所在目录；不随当前模式变化，完整模式下的管理页也写这里。 */
    fun publicIconDir(): java.io.File =
        java.io.File(java.io.File(app.filesDir, DISGUISE_MEDIA_DIR), PublicAppOverrides.ICON_DIR)

    suspend fun disguisePublicOverrides(): Map<String, PublicAppOverride> =
        PublicAppOverrides.parse(app.dataStore.data.first()[stringPreferencesKey(disguiseName(PublicAppOverrides.KEY))])

    /** 改一项覆盖并清理不再引用的图标文件。 */
    suspend fun updatePublicOverride(componentKey: String, transform: (PublicAppOverride) -> PublicAppOverride) {
        var result: Map<String, PublicAppOverride> = emptyMap()
        app.dataStore.edit { prefs ->
            val key = stringPreferencesKey(disguiseName(PublicAppOverrides.KEY))
            result = PublicAppOverrides.update(PublicAppOverrides.parse(prefs[key]), componentKey, transform)
            prefs[key] = PublicAppOverrides.serialize(result)
        }
        val dir = publicIconDir()
        val files = dir.list()?.toList().orEmpty()
        PublicAppOverrides.orphanIcons(result, files).forEach { java.io.File(dir, it).delete() }
    }

    /** 伪装模式当前选中的表盘 ID（R19 初始表盘）；不论当前模式。 */
    suspend fun disguiseWatchFace(): String? =
        app.dataStore.data.first()[stringPreferencesKey(disguiseName(KEY_SELECTED_WATCHFACE))]

    suspend fun saveDisguiseWatchFace(id: String) {
        app.dataStore.edit { prefs -> prefs[stringPreferencesKey(disguiseName(KEY_SELECTED_WATCHFACE))] = id }
    }

    /** 隐藏完整抽屉里置顶的应用（componentKey）。 */
    suspend fun hiddenDrawerPinned(): Set<String> = readDisguiseSet(HiddenDrawerCatalog.PINNED_KEY)

    suspend fun saveHiddenDrawerPinned(keys: Set<String>) = saveDisguiseSet(HiddenDrawerCatalog.PINNED_KEY, keys)

    /** R09：伪装模式下在第三方应用里熄屏后，下次亮屏是否继续原应用（默认回表盘）。 */
    val screenOffKeepApp: StateFlow<Boolean> = app.dataStore.data
        .map { prefs -> prefs[booleanPreferencesKey(disguiseName(ThirdPartyScreenOff.KEY))] == true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun setScreenOffKeepApp(keep: Boolean) {
        app.dataStore.edit { prefs -> prefs[booleanPreferencesKey(disguiseName(ThirdPartyScreenOff.KEY))] = keep }
    }

    private val _managementSession = MutableStateFlow(false)

    /**
     * 本次管理流程是否有效（R12 管理资格）：伪装模式下允许编辑伪装小组件等管理操作。
     * 只保存在内存里，熄屏或模式改变即失效，不持久保存为永久解锁。
     */
    val managementSession: StateFlow<Boolean> = _managementSession.asStateFlow()

    /** 关于页隐藏管理菜单验证成功后调用（R15）。 */
    fun beginManagementSession() {
        _managementSession.value = true
    }

    fun endManagementSession() {
        _managementSession.value = false
    }

    @Volatile
    private var secretResetGrant = false

    /** R17：重置流程点满版本号后授予一次“不验证旧密码直接设置新密码”。 */
    fun grantSecretReset() {
        secretResetGrant = true
    }

    fun consumeSecretResetGrant(): Boolean {
        val granted = secretResetGrant
        secretResetGrant = false
        return granted
    }

    /**
     * 打开隐藏密码设置页时调用：完整／外部模式总是允许（使用者本人）；伪装模式需要
     * 本次管理会话（维护）或一次性重置授权（R13、R17）。
     */
    fun consumeSecretSetupAccess(): Boolean {
        if (controller.configScope.value != ConfigScope.DISGUISE) return true
        val reset = consumeSecretResetGrant()
        return reset || _managementSession.value
    }

    /** 熄屏、来电：结束管理会话，撤销一次性授权（R15、R16）。 */
    fun onInterrupted() {
        endManagementSession()
        clearHiddenDrawerChallenge()
        hiddenDrawerGrant = false
        secretResetGrant = false
    }

    private val interruptionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onInterrupted()
        }
    }

    private fun watchCalls() {
        // 不申请电话权限：用音频模式判断通话／响铃，只在管理会话期间轮询。
        scope.launch {
            _managementSession.collectLatest { active ->
                while (active) {
                    if (SecretInterruptions.inCall(app)) onInterrupted()
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }

    /** 本进程的标识：页面重建时用来判断授权是否来自同一进程（进程重建不沿用授权）。 */
    val processToken: String = java.util.UUID.randomUUID().toString()

    private val _hiddenDrawerChallenge = MutableStateFlow(false)

    /** R14：控制中心电量按住 2 秒松手后，桌面在原画面上显示静默验证层。 */
    val hiddenDrawerChallenge: StateFlow<Boolean> = _hiddenDrawerChallenge.asStateFlow()

    /** 只在伪装模式生效；完整／外部模式下没有隐藏入口。 */
    fun requestHiddenDrawerChallenge() {
        if (controller.configScope.value == ConfigScope.DISGUISE) _hiddenDrawerChallenge.value = true
    }

    fun clearHiddenDrawerChallenge() {
        _hiddenDrawerChallenge.value = false
    }

    @Volatile
    private var hiddenDrawerGrant = false

    /**
     * R14：静默验证成功后授予“本次”完整抽屉，只能用一次，不授予管理权限。
     */
    fun grantHiddenDrawer() {
        hiddenDrawerGrant = true
    }

    /** 打开隐藏抽屉时调用：完整／外部模式总是允许；伪装模式需要管理会话或一次性授权。 */
    fun consumeHiddenDrawerAccess(): Boolean {
        if (controller.configScope.value != ConfigScope.DISGUISE) return true
        val granted = hiddenDrawerGrant
        hiddenDrawerGrant = false
        return granted || _managementSession.value
    }

    init {
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app,
                interruptionReceiver,
                android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        watchCalls()
        scope.launch {
            controller.mode.drop(1).collect {
                endManagementSession()
                clearHiddenDrawerChallenge()
                hiddenDrawerGrant = false
                secretResetGrant = false
            }
        }
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

    /** 桌面收到带此标记的 HOME Intent 时，结束启动会话、不播返回动画，直接回表盘。 */
    const val EXTRA_RETURN_TO_FACE = "com.flue.launcher.extra.RETURN_TO_FACE"

    /** 把本应用桌面带回前台并停在表盘（R09 熄屏回表盘、R11 小组件越界）。 */
    fun returnToFaceIntent(context: Context): Intent = homeIntent()
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .putExtra(EXTRA_RETURN_TO_FACE, true)

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
