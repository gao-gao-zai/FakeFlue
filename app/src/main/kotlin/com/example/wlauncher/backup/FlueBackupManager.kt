package com.flue.launcher.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flue.launcher.applist.background.AppListBackgroundJson
import com.flue.launcher.data.NonClosingOutputStream
import com.flue.launcher.data.StagedFiles
import com.flue.launcher.data.MediaSettingsFiles
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.flue.launcher.data.repository.WidgetRepository
import com.flue.launcher.surface.DISGUISE_MEDIA_DIR
import com.flue.launcher.surface.apps.PublicAppOverrides
import com.flue.launcher.surface.config.DisguiseBackup
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import com.flue.launcher.watchface.WatchFacePhotoCache
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

data class FlueBackupOptions(
    val settings: Boolean = true,
    val appOrder: Boolean = true,
    val widgetOrder: Boolean = true,
    val sideScreenOrder: Boolean = true,
    val photoWatchFace: Boolean = true,
    val videoWatchFace: Boolean = true,
    /** 已安装的 .watch 表盘文件（WatchmakerWatchFaceStorage 里的表盘）。 */
    val watchFiles: Boolean = true
) {
    val hasAny: Boolean
        get() = settings || appOrder || widgetOrder || sideScreenOrder ||
            photoWatchFace || videoWatchFace || watchFiles

    fun constrainedBy(available: FlueBackupOptions): FlueBackupOptions {
        return FlueBackupOptions(
            settings = settings && available.settings,
            appOrder = appOrder && available.appOrder,
            widgetOrder = widgetOrder && available.widgetOrder,
            sideScreenOrder = sideScreenOrder && available.sideScreenOrder,
            photoWatchFace = photoWatchFace && available.photoWatchFace,
            videoWatchFace = videoWatchFace && available.videoWatchFace,
            watchFiles = watchFiles && available.watchFiles
        )
    }

    /**
     * 勾选的分区，按声明顺序给出稳定 key。UI 侧据此查 strings 拼展示名，
     * 于是「顺序」与「key→名称」各只有一处定义，两边不会写歪。
     */
    fun selectedSectionKeys(): List<String> = buildList {
        if (settings) add(SECTION_SETTINGS)
        if (appOrder) add(SECTION_APP_ORDER)
        if (widgetOrder) add(SECTION_WIDGET_ORDER)
        if (sideScreenOrder) add(SECTION_SIDE_SCREEN_ORDER)
        if (photoWatchFace) add(SECTION_PHOTO_WATCH_FACE)
        if (videoWatchFace) add(SECTION_VIDEO_WATCH_FACE)
        if (watchFiles) add(SECTION_WATCH_FILES)
    }

    companion object {
        const val SECTION_SETTINGS = "settings"
        const val SECTION_APP_ORDER = "appOrder"
        const val SECTION_WIDGET_ORDER = "widgetOrder"
        const val SECTION_SIDE_SCREEN_ORDER = "sideScreenOrder"
        const val SECTION_PHOTO_WATCH_FACE = "photoWatchFace"
        const val SECTION_VIDEO_WATCH_FACE = "videoWatchFace"
        const val SECTION_WATCH_FILES = "watchFiles"
    }
}

data class FlueBackupPreview(
    val appVersion: String,
    val createdAt: String,
    val availableOptions: FlueBackupOptions
)

/** 导出结果摘要，供「导出完成」页显示内容 / 大小 / 时间三行灰字。 */
data class FlueBackupExportSummary(
    /** 实际写进包里的分区：勾选了但没有内容可导（例如没有已安装表盘）的分区不算。 */
    val sections: FlueBackupOptions,
    /**
     * 备份包字节数。在写出流上累计得到，**不**写完再 openFileDescriptor 读
     * statSize —— SAF provider 不保证 statSize 可靠，累计值更准。
     */
    val bytes: Long,
    /** 与包内 manifest 的 createdAt 同源，格式 `yyyy-MM-dd HH:mm:ss`。 */
    val createdAt: String,
)

/** 只做字节计数的透传流，用来在写出过程中得到备份包真实大小。 */
internal class CountingOutputStream(
    private val delegate: java.io.OutputStream,
) : java.io.OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count += 1L
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len.toLong()
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}


object FlueBackupManager {
    private const val MANIFEST_ENTRY = BACKUP_MANIFEST_ENTRY
    private const val PHOTO_MEDIA_ENTRY = "watchfaces/photo/media"
    private const val VIDEO_MEDIA_ENTRY = "watchfaces/video/media"
    private const val FONT_ENTRY = "watchfaces/font/current"

    private const val WATCH_ARCHIVES_PREFIX = "watchfaces/watch/"

    /**
     * 自定义应用列表背景图在备份包里的条目。
     *
     * 设置里存的是**绝对路径**，只带路径过去没用：换机 / 重装后那个文件不在，配置就变成
     * 一个指向空气的路径（渲染侧会落回纯色，不崩，但老师的背景没了）。所以图跟着走。
     */
    private const val APP_LIST_BACKGROUND_ENTRY = "app_list_background/image"

    /**
     * 还原时背景图落进哪个子目录。与图片 / 视频表盘同一套 `internal_watchfaces/<folder>`
     * 布局：一份背景图只留一张，不会随着反复恢复堆积。
     */
    private const val APP_LIST_BACKGROUND_MEDIA_FOLDER = "app_list_background"

    private val appOrderKey = LauncherViewModel.KEY_APP_ORDER.name
    private val sideScreenShortcutsKey = LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS.name
    private val sideScreenWidgetsKey = LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS.name
    private val hiddenAppsKey = LauncherViewModel.KEY_HIDDEN_APPS.name
    private val photoPathKey = LauncherViewModel.KEY_BUILTIN_PHOTO_PATH.name
    private val videoPathKey = LauncherViewModel.KEY_BUILTIN_VIDEO_PATH.name
    private val fontPathKey = LauncherViewModel.KEY_WATCHFACE_FONT_PATH.name
    private val lastWatchFaceErrorKey = LauncherViewModel.KEY_LAST_WATCHFACE_ERROR.name

    private val photoPreferenceNames = setOf(
        photoPathKey,
        LauncherViewModel.KEY_PHOTO_CLOCK_POSITION.name,
        LauncherViewModel.KEY_PHOTO_CLOCK_SIZE.name,
        LauncherViewModel.KEY_PHOTO_CLOCK_BOLD.name,
        LauncherViewModel.KEY_PHOTO_CLOCK_STYLE.name,
        LauncherViewModel.KEY_PHOTO_MD3E_SHAPE.name,
        LauncherViewModel.KEY_PHOTO_USE_THEME_TEXT_COLOR.name,
        LauncherViewModel.KEY_PHOTO_TEXT_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_AUTO_COLORS.name,
        LauncherViewModel.KEY_PHOTO_MD3E_TEXT_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_FACE_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_HOUR_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_MINUTE_COLOR.name,
        LauncherViewModel.KEY_PHOTO_MD3E_SECOND_COLOR.name,
        LauncherViewModel.KEY_PHOTO_SHOW_SECONDS.name,
        LauncherViewModel.KEY_PHOTO_CUSTOM_TEXT.name
    )

    private val videoPreferenceNames = setOf(
        videoPathKey,
        LauncherViewModel.KEY_VIDEO_CLOCK_POSITION.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_SIZE.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_BOLD.name,
        LauncherViewModel.KEY_VIDEO_FILL_SCREEN.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_COLOR_MODE.name,
        LauncherViewModel.KEY_VIDEO_CLOCK_STYLE.name,
        LauncherViewModel.KEY_VIDEO_MD3E_SHAPE.name,
        LauncherViewModel.KEY_VIDEO_USE_THEME_TEXT_COLOR.name,
        LauncherViewModel.KEY_VIDEO_TEXT_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_AUTO_COLORS.name,
        LauncherViewModel.KEY_VIDEO_MD3E_TEXT_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_FACE_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_HOUR_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_MINUTE_COLOR.name,
        LauncherViewModel.KEY_VIDEO_MD3E_SECOND_COLOR.name,
        LauncherViewModel.KEY_VIDEO_SHOW_SECONDS.name,
        LauncherViewModel.KEY_VIDEO_CUSTOM_TEXT.name
    )

    private val settingsExcludedNames = setOf(
        appOrderKey,
        sideScreenShortcutsKey,
        sideScreenWidgetsKey,
        lastWatchFaceErrorKey,
        fontPathKey
    ) + photoPreferenceNames + videoPreferenceNames +
        // 伪装模式的媒体路径是本机绝对路径，随 disguiseMedia 一起导出、恢复时重写。
        DisguiseBackup.localOnlyPhysicalNames(listOf(photoPathKey, videoPathKey, fontPathKey, lastWatchFaceErrorKey))

    private val appKeyFilteredNames = setOf(appOrderKey, hiddenAppsKey, sideScreenWidgetsKey, sideScreenShortcutsKey)

    private fun disguiseKey(key: Preferences.Key<String>): Preferences.Key<String> =
        stringPreferencesKey(LauncherSettingsScopes.physicalName(key.name, ConfigScope.DISGUISE))

    private fun disguiseMediaRoot(context: Context): File = File(context.filesDir, DISGUISE_MEDIA_DIR)

    fun suggestedFileName(context: Context): String {
        val version = appVersion(context).sanitizeFilePart()
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "Flue${version}_backup_${date}.zip"
    }

    suspend fun exportBackup(
        context: Context,
        targetUri: Uri,
        options: FlueBackupOptions,
    ): FlueBackupExportSummary {
        require(options.hasAny) { "没有选择要导出的内容" }
        val prefs = context.dataStore.data.first()
        // manifest 与「导出完成」页共用同一个时间源，别另起一套。
        val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // 已安装的 .watch 表盘目录。点号目录是安装暂存 / 编辑器修订 / 替换备份，不是表盘，
        // 以前会被当成表盘打进备份（R37）。
        val watchFaceDirs = if (options.watchFiles) {
            WatchmakerWatchFaceStorage.rootDirectory(context)
                .listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?: error("无法读取已安装表盘目录")
        } else emptyList()
        // 摘要只报告真正写进包里的分区：没有可导出的表盘时不再声称导出了表盘。
        val exported = options.copy(watchFiles = options.watchFiles && watchFaceDirs.isNotEmpty())
        require(exported.hasAny) { "没有可导出的内容" }

        val manifest = JSONObject()
            .put("schemaVersion", BACKUP_SCHEMA_VERSION)
            .put("appVersion", appVersion(context))
            .put("createdAt", createdAt)
            .put("sections", exported.toJson())

        if (exported.settings) {
            manifest.put("settings", prefs.toPreferenceJson(excludeNames = settingsExcludedNames))
        }
        if (exported.appOrder) {
            manifest.put("appOrder", prefs[LauncherViewModel.KEY_APP_ORDER].orEmpty().splitCsvJson())
        }
        if (exported.widgetOrder) {
            manifest.put("widgetOrder", prefs[LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS].orEmpty().splitWidgetJson())
        }
        if (exported.sideScreenOrder) {
            manifest.put("sideScreenShortcuts", prefs[LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS].orEmpty().splitShortcutJson())
        }

        val photoMedia = prefs[LauncherViewModel.KEY_BUILTIN_PHOTO_PATH]
            ?.let(::File)
            ?.takeIf { it.isFile }
        val videoMedia = prefs[LauncherViewModel.KEY_BUILTIN_VIDEO_PATH]
            ?.let(::File)
            ?.takeIf { it.isFile }
        val fontMedia = prefs[LauncherViewModel.KEY_WATCHFACE_FONT_PATH]
            ?.let(::File)
            ?.takeIf { it.isFile }
        // 自定义应用列表背景图。路径藏在 app_list_background 那份 JSON 里，所以要先解出来。
        val appListBackgroundMedia = if (exported.settings) {
            AppListBackgroundJson
                .decodeOrDefault(prefs[LauncherViewModel.KEY_APP_LIST_BACKGROUND])
                .imagePath
                ?.let(::File)
                ?.takeIf { it.isFile }
        } else {
            null
        }
        val appListBackgroundEntry = appListBackgroundMedia?.let {
            "$APP_LIST_BACKGROUND_ENTRY.${it.extension.ifBlank { "png" }.sanitizeFilePart()}"
        }
        if (appListBackgroundEntry != null) {
            manifest.put("appListBackgroundEntry", appListBackgroundEntry)
        }
        val disguiseFiles = if (exported.settings) collectDisguiseMedia(context, prefs) else emptyList()
        if (disguiseFiles.isNotEmpty()) {
            manifest.put(
                DisguiseBackup.MANIFEST_KEY,
                JSONObject().apply { disguiseFiles.forEach { (role, entry, _) -> if (role != null) put(role, entry) } }
                    .put("publicIcons", JSONArray(disguiseFiles.filter { it.role == null }.map { it.entry })),
            )
        }

        val fontEntry = fontMedia?.let { "$FONT_ENTRY.${it.extension.ifBlank { "ttf" }.sanitizeFilePart()}" }
        if (exported.photoWatchFace) {
            manifest.put(
                "photoWatchFace",
                JSONObject()
                    .put("preferences", prefs.toPreferenceJson(includeNames = photoPreferenceNames - photoPathKey))
                    .put("mediaEntry", photoMedia?.let { "$PHOTO_MEDIA_ENTRY.${it.extension.ifBlank { "png" }.sanitizeFilePart()}" } ?: JSONObject.NULL)
                    .put("fontEntry", fontEntry ?: JSONObject.NULL)
            )
        }
        if (exported.videoWatchFace) {
            manifest.put(
                "videoWatchFace",
                JSONObject()
                    .put("preferences", prefs.toPreferenceJson(includeNames = videoPreferenceNames - videoPathKey))
                    .put("mediaEntry", videoMedia?.let { "$VIDEO_MEDIA_ENTRY.${it.extension.ifBlank { "mp4" }.sanitizeFilePart()}" } ?: JSONObject.NULL)
                    .put("fontEntry", fontEntry ?: JSONObject.NULL)
            )
        }
        if (exported.watchFiles) {
            val entries = JSONArray()
            watchFaceDirs.forEach { dir -> entries.put("$WATCH_ARCHIVES_PREFIX${dir.name}.watch") }
            manifest.put("watchFiles", JSONObject().put("archives", entries))
        }

        val output = context.contentResolver.openOutputStream(targetUri) ?: error("无法写入备份文件")
        val counting = CountingOutputStream(output)
        try {
            counting.use { counted ->
                ZipOutputStream(counted.buffered()).use { zip ->
                    zip.writeTextEntry(MANIFEST_ENTRY, manifest.toString(2))
                    if (exported.photoWatchFace && photoMedia != null) {
                        zip.writeFileEntry(manifest.getJSONObject("photoWatchFace").getString("mediaEntry"), photoMedia)
                    }
                    if (exported.videoWatchFace && videoMedia != null) {
                        zip.writeFileEntry(manifest.getJSONObject("videoWatchFace").getString("mediaEntry"), videoMedia)
                    }
                    if ((exported.photoWatchFace || exported.videoWatchFace) && fontMedia != null && fontEntry != null) {
                        zip.writeFileEntry(fontEntry, fontMedia)
                    }
                    if (appListBackgroundEntry != null && appListBackgroundMedia != null) {
                        zip.writeFileEntry(appListBackgroundEntry, appListBackgroundMedia)
                    }
                    disguiseFiles.forEach { zip.writeFileEntry(it.entry, it.file) }
                    // 每个已安装表盘目录直接流式打成内嵌 .watch，不再整包缓存在内存里。
                    watchFaceDirs.forEach { faceDir ->
                        zip.putNextEntry(ZipEntry("$WATCH_ARCHIVES_PREFIX${faceDir.name}.watch"))
                        writeBackupWatchArchive(faceDir, NonClosingOutputStream(zip))
                        zip.closeEntry()
                    }
                }
            }
        } catch (error: Throwable) {
            // 半截的备份包不能留着冒充有效备份：尽力删掉，删不掉就截断成空文件。
            discardPartialExport(context, targetUri)
            throw error
        }
        // ZipOutputStream 关闭时才写完中央目录并 flush 到 counting，所以在 use 之后读。
        return FlueBackupExportSummary(
            sections = exported,
            bytes = counting.count,
            createdAt = createdAt,
        )
    }

    private fun discardPartialExport(context: Context, uri: Uri) {
        val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .getOrDefault(false)
        if (!deleted) {
            runCatching { context.contentResolver.openOutputStream(uri, "wt")?.close() }
        }
    }

    suspend fun readPreview(context: Context, sourceUri: Uri): FlueBackupPreview {
        val manifest = context.contentResolver.openInputStream(sourceUri)?.use { readBackupManifest(it) }
            ?: error("无法读取备份文件")
        return previewFromManifest(manifest)
    }

    suspend fun importBackup(
        context: Context,
        sourceUri: Uri,
        options: FlueBackupOptions,
        /** 本机已安装应用的 componentKey；为 null 表示应用列表尚未加载，此时不按它过滤。 */
        availableAppKeys: Set<String>?,
        widgetRepository: WidgetRepository
    ): FlueBackupImportResult {
        require(options.hasAny) { "没有选择要导入的内容" }
        val validWidgetKeys = widgetRepository.getAllWidgets().map { it.widgetKey }.toSet()
        return importBackupArchive(
            context = context,
            openInput = { context.contentResolver.openInputStream(sourceUri) },
            options = options,
            availableAppKeys = availableAppKeys,
            validWidgetKeys = validWidgetKeys,
        )
    }

    /**
     * 导入事务（R37）：
     *
     * 1. 有上限地解包到私有临时目录，拒绝越界、重复条目与更高的格式版本；
     * 2. 把要恢复的媒体文件**先**写成新文件（旧文件不动）；
     * 3. 一次 `dataStore.edit` 提交所有配置；提交失败则删掉第 2 步的新文件，旧配置与旧文件原样保留；
     * 4. 提交成功后才清理被替换掉的旧媒体文件、把公开图标换上；
     * 5. 逐个安装内嵌 .watch，逐个记录成败。
     */
    internal suspend fun importBackupArchive(
        context: Context,
        openInput: () -> InputStream?,
        options: FlueBackupOptions,
        availableAppKeys: Set<String>?,
        validWidgetKeys: Set<String>,
        limits: BackupArchiveLimits = BackupArchiveLimits(),
    ): FlueBackupImportResult {
        require(options.hasAny) { "没有选择要导入的内容" }
        val tempRoot = File(context.cacheDir, "flue_backup_import_${UUID.randomUUID()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val staging = MediaStaging()
        try {
            val manifest = openInput()?.use { extractBackupArchive(it, tempRoot, limits) }
                ?: error("无法读取备份文件")
            val available = previewFromManifest(manifest).availableOptions
            val selected = options.constrainedBy(available)
            val requested = selected.selectedSectionKeys()
            val restored = ArrayList<String>()
            val issues = ArrayList<FlueBackupImportIssue>()

            var skippedSettings = 0
            MediaSettingsFiles.withTransaction {
                // ---- 第 2 步：先把媒体写成新文件 ----
                fun stage(section: String, item: String, entry: String?, directory: File, fallbackExtension: String): StageOutcome {
                    if (entry.isNullOrBlank() || entry == "null") return StageOutcome.Absent
                    val source = resolveBackupEntry(tempRoot, entry)
                        ?: return StageOutcome.Failed.also {
                            issues += FlueBackupImportIssue(section, item, "备份里缺少文件")
                        }
                    return try {
                        StageOutcome.Staged(staging.stageFile(source, directory, mediaExtension(source, fallbackExtension)).absolutePath)
                    } catch (error: Exception) {
                        issues += FlueBackupImportIssue(section, item, error.describe())
                        StageOutcome.Failed
                    }
                }
                val internalRoot = File(context.filesDir, "internal_watchfaces")
                val disguiseInternalRoot = File(disguiseMediaRoot(context), "internal_watchfaces")

                val settingsSection = FlueBackupOptions.SECTION_SETTINGS
                val appListBackground = if (selected.settings) {
                    stage(
                        settingsSection, "appListBackground", manifest.optString("appListBackgroundEntry"),
                        File(internalRoot, APP_LIST_BACKGROUND_MEDIA_FOLDER), "png",
                    )
                } else StageOutcome.Absent
                val disguiseManifest = if (selected.settings) manifest.optJSONObject(DisguiseBackup.MANIFEST_KEY) else null
                val disguisePhoto = stage(settingsSection, "disguisePhoto", disguiseManifest?.optString("photoEntry"), File(disguiseInternalRoot, "photo"), "png")
                val disguiseVideo = stage(settingsSection, "disguiseVideo", disguiseManifest?.optString("videoEntry"), File(disguiseInternalRoot, "video"), "mp4")
                val disguiseFont = stage(settingsSection, "disguiseFont", disguiseManifest?.optString("fontEntry"), File(disguiseInternalRoot, "font"), "ttf")
                val disguiseBackground = stage(
                    settingsSection, "disguiseAppListBackground", disguiseManifest?.optString("appListBackgroundEntry"),
                    File(disguiseInternalRoot, APP_LIST_BACKGROUND_MEDIA_FOLDER), "png",
                )
                disguiseManifest?.optJSONArray("publicIcons").toStringList().forEach { entry ->
                    val name = DisguiseBackup.publicIconName(entry) ?: return@forEach
                    val source = resolveBackupEntry(tempRoot, entry)
                    if (source == null) {
                        issues += FlueBackupImportIssue(settingsSection, "publicIcon:$name", "备份里缺少文件")
                        return@forEach
                    }
                    try {
                        staging.stageReplacement(source, File(File(disguiseMediaRoot(context), PublicAppOverrides.ICON_DIR), name))
                    } catch (error: Exception) {
                        issues += FlueBackupImportIssue(settingsSection, "publicIcon:$name", error.describe())
                    }
                }

                // 图片 / 视频表盘：媒体与字体任一失败，整个分区不写，本机原配置保持不变。
                val fontOutcomes = HashMap<String, StageOutcome>()
                fun stageFaceSection(section: String, json: JSONObject?, mediaFolder: String, fallback: String): FaceMedia? {
                    if (json == null) return FaceMedia(StageOutcome.Absent, StageOutcome.Absent)
                    val media = stage(section, "media", json.optString("mediaEntry"), File(internalRoot, mediaFolder), fallback)
                    val fontEntry = json.optString("fontEntry")
                    val font = fontOutcomes[fontEntry] ?: stage(section, "font", fontEntry, File(internalRoot, "font"), "ttf")
                        .also { fontOutcomes[fontEntry] = it }
                    if (media is StageOutcome.Failed || font is StageOutcome.Failed) {
                        if (font is StageOutcome.Failed && issues.none { it.section == section && it.item == "font" }) {
                            issues += FlueBackupImportIssue(section, "font", "字体文件恢复失败")
                        }
                        return null
                    }
                    return FaceMedia(media, font)
                }
                val photoJson = manifest.optJSONObject("photoWatchFace")
                val videoJson = manifest.optJSONObject("videoWatchFace")
                val photoMedia = if (selected.photoWatchFace) {
                    stageFaceSection(FlueBackupOptions.SECTION_PHOTO_WATCH_FACE, photoJson, "photo", "png")
                } else null
                val videoMedia = if (selected.videoWatchFace) {
                    stageFaceSection(FlueBackupOptions.SECTION_VIDEO_WATCH_FACE, videoJson, "video", "mp4")
                } else null

                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    // ---- 第 3 步：一次提交全部配置 ----
                    val sectionsInEdit = ArrayList<String>()
                    val committedPreferences = try {
                        context.dataStore.edit { prefs ->
                            sectionsInEdit.clear()
                            skippedSettings = 0
                            if (selected.settings) {
                                val backgroundKey = LauncherViewModel.KEY_APP_LIST_BACKGROUND
                                val disguiseBackgroundKey = disguiseKey(backgroundKey)
                                val previousBackground = prefs[backgroundKey]
                                val previousDisguiseBackground = prefs[disguiseBackgroundKey]
                                skippedSettings += prefs.applyPreferenceJson(
                                    manifest.optJSONObject("settings"),
                                    availableAppKeys = availableAppKeys,
                                    validWidgetKeys = validWidgetKeys,
                                    excludeNames = settingsExcludedNames
                                )
                                prefs.applyBackgroundOutcome(backgroundKey, appListBackground, previousBackground)
                                prefs.applyBackgroundOutcome(disguiseBackgroundKey, disguiseBackground, previousDisguiseBackground)
                                (disguisePhoto as? StageOutcome.Staged)?.let { prefs[disguiseKey(LauncherViewModel.KEY_BUILTIN_PHOTO_PATH)] = it.path }
                                (disguiseVideo as? StageOutcome.Staged)?.let { prefs[disguiseKey(LauncherViewModel.KEY_BUILTIN_VIDEO_PATH)] = it.path }
                                (disguiseFont as? StageOutcome.Staged)?.let { prefs[disguiseKey(LauncherViewModel.KEY_WATCHFACE_FONT_PATH)] = it.path }
                                sectionsInEdit += FlueBackupOptions.SECTION_SETTINGS
                            }
                            if (selected.appOrder) {
                                val appOrder = manifest.optJSONArray("appOrder")
                                    .toStringList()
                                    .filterAppKeys(availableAppKeys)
                                if (appOrder.isEmpty()) prefs.remove(LauncherViewModel.KEY_APP_ORDER) else prefs[LauncherViewModel.KEY_APP_ORDER] = appOrder.joinToString(",")
                                sectionsInEdit += FlueBackupOptions.SECTION_APP_ORDER
                            }
                            if (selected.widgetOrder) {
                                val widgets = manifest.optJSONArray("widgetOrder")
                                    .toStringList()
                                    .filter { raw -> raw.widgetKeyPart() in validWidgetKeys }
                                if (widgets.isEmpty()) prefs.remove(LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS) else prefs[LauncherViewModel.KEY_SIDE_SCREEN_WIDGETS] = widgets.joinToString("|")
                                sectionsInEdit += FlueBackupOptions.SECTION_WIDGET_ORDER
                            }
                            if (selected.sideScreenOrder) {
                                val shortcuts = manifest.optJSONArray("sideScreenShortcuts")
                                    .toNullableStringList()
                                    .map { value -> value?.takeIf { availableAppKeys == null || it in availableAppKeys } }
                                val serializedShortcuts = serializeShortcuts(shortcuts)
                                if (serializedShortcuts.isEmpty()) prefs.remove(LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS) else prefs[LauncherViewModel.KEY_SIDE_SCREEN_SHORTCUTS] = serializedShortcuts
                                sectionsInEdit += FlueBackupOptions.SECTION_SIDE_SCREEN_ORDER
                            }
                            if (photoMedia != null) {
                                skippedSettings += prefs.applyFaceSection(photoJson, photoMedia, LauncherViewModel.KEY_BUILTIN_PHOTO_PATH)
                                sectionsInEdit += FlueBackupOptions.SECTION_PHOTO_WATCH_FACE
                            }
                            if (videoMedia != null) {
                                skippedSettings += prefs.applyFaceSection(videoJson, videoMedia, LauncherViewModel.KEY_BUILTIN_VIDEO_PATH)
                                sectionsInEdit += FlueBackupOptions.SECTION_VIDEO_WATCH_FACE
                            }
                        }
                    } catch (error: Throwable) {
                        // 配置没提交：新写的媒体全部删掉，旧文件、旧配置一个不动。
                        staging.rollback()
                        throw error
                    }
                    restored += sectionsInEdit

                    // ---- 第 4 步：提交成功后才清理旧文件、换上公开图标 ----
                    staging.commit(MediaSettingsFiles.referencedFiles(committedPreferences)).forEach { failedName ->
                        issues += FlueBackupImportIssue(settingsSection, "publicIcon:$failedName", "图标替换失败")
                    }
                    if (disguisePhoto is StageOutcome.Staged || disguiseVideo is StageOutcome.Staged ||
                        photoMedia?.media is StageOutcome.Staged || videoMedia?.media is StageOutcome.Staged
                    ) {
                        WatchFacePhotoCache.clear()
                    }

                }
            }
            currentCoroutineContext().ensureActive()

            // ---- 第 5 步：内嵌 .watch 逐个安装 ----
            var restoredFaces = 0
            var failedFaces = 0
            if (selected.watchFiles) {
                val archives = manifest.optJSONObject("watchFiles")?.optJSONArray("archives")
                val section = FlueBackupOptions.SECTION_WATCH_FILES
                for (i in 0 until (archives?.length() ?: 0)) {
                    currentCoroutineContext().ensureActive()
                    val entry = archives?.optString(i).orEmpty()
                    if (entry.isBlank()) continue
                    val label = entry.substringAfterLast('/').take(96)
                    val archiveFile = resolveBackupEntry(tempRoot, entry)
                    if (archiveFile == null) {
                        failedFaces += 1
                        issues += FlueBackupImportIssue(section, label, "备份里缺少该表盘文件")
                        continue
                    }
                    val sanitizedArchive = File(tempRoot, "sanitized-watch-$i.watch")
                    try {
                        sanitizeBackupWatchArchive(archiveFile, sanitizedArchive)
                        WatchmakerWatchFaceStorage.importArchiveFile(context, sanitizedArchive)
                        restoredFaces += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failedFaces += 1
                        issues += FlueBackupImportIssue(section, label, error.describe())
                    } finally {
                        sanitizedArchive.delete()
                        // 已安装的表盘不再需要解包副本，及时释放空间。
                        archiveFile.delete()
                    }
                }
                if (restoredFaces > 0 || failedFaces == 0) restored += section
            }

            return FlueBackupImportResult(
                requestedSections = requested,
                restoredSections = requested.filter { it in restored },
                issues = issues,
                restoredWatchFaces = restoredFaces,
                failedWatchFaces = failedFaces,
                skippedSettings = skippedSettings,
            )
        } catch (error: Throwable) {
            staging.rollback()
            throw error
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private sealed class StageOutcome {
        /** 备份里没有这项，配置保持备份里写的样子（与旧行为一致）。 */
        object Absent : StageOutcome()
        object Failed : StageOutcome()
        data class Staged(val path: String) : StageOutcome()
    }

    private data class FaceMedia(val media: StageOutcome, val font: StageOutcome)

    /**
     * 导入期间新写入的媒体文件。提交前调用 [rollback] 会删掉它们；[commit] 在配置提交后
     * 清掉同目录下被替换的旧文件，并把公开图标从临时名换成正式名。
     */
    private class MediaStaging {
        private val stagedFiles = ArrayList<File>()
        private val replacements = ArrayList<Pair<File, File>>()
        private var finished = false

        fun stageFile(source: File, directory: File, extension: String): File {
            val staged = StagedFiles.writeNew(directory, "current", extension, write = { output ->
                source.inputStream().use { it.copyTo(output) }
            })
            stagedFiles += staged
            return staged
        }

        fun stageReplacement(source: File, finalTarget: File) {
            val directory = finalTarget.parentFile ?: error("图标目录无效")
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) error("无法创建图标目录")
            val temp = File(directory, ".${finalTarget.name}.import-${UUID.randomUUID()}")
            try {
                source.inputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
            replacements += temp to finalTarget
        }

        fun rollback() {
            if (finished) return
            finished = true
            stagedFiles.forEach { it.delete() }
            replacements.forEach { (temp, _) -> temp.delete() }
        }

        /** 返回换上失败的图标文件名。 */
        fun commit(referencedFiles: Collection<File>): List<String> {
            if (finished) return emptyList()
            finished = true
            val referencedPaths = referencedFiles.mapTo(HashSet()) { it.absoluteFile.normalize().path }
            val (adoptedFiles, unusedFiles) = stagedFiles.partition { it.absoluteFile.normalize().path in referencedPaths }
            // 分区失败时暂存成功的另一项没有生效，只删新文件，保留旧资源。
            unusedFiles.forEach { it.delete() }
            adoptedFiles.mapNotNull { it.parentFile }.distinct().forEach { directory ->
                // 字体等目录可以被多个分区、模式引用，按实际提交后的全部路径保留。
                StagedFiles.pruneExcept(directory, referencedFiles)
            }
            return replacements.mapNotNull { (temp, target) ->
                runCatching { StagedFiles.moveReplacing(temp, target) }
                    .fold(onSuccess = { null }, onFailure = { temp.delete(); target.name })
            }
        }
    }

    private fun MutablePreferences.applyBackgroundOutcome(
        key: Preferences.Key<String>,
        outcome: StageOutcome,
        previous: String?,
    ) {
        when (outcome) {
            is StageOutcome.Staged -> {
                val config = AppListBackgroundJson.decodeOrDefault(this[key]).copy(imagePath = outcome.path)
                runCatching { AppListBackgroundJson.encode(config) }.getOrNull()?.let { this[key] = it }
            }
            // 图没恢复成功：保留本机原来的背景配置，不换成一个指向来源机路径的配置。
            StageOutcome.Failed -> if (previous == null) remove(key) else this[key] = previous
            StageOutcome.Absent -> Unit
        }
    }

    /** 写入图片 / 视频表盘分区，返回跳过的设置项数。 */
    private fun MutablePreferences.applyFaceSection(
        json: JSONObject?,
        media: FaceMedia,
        mediaKey: Preferences.Key<String>,
    ): Int {
        if (json == null) return 0
        val skipped = applyPreferenceJson(json.optJSONObject("preferences"))
        (media.media as? StageOutcome.Staged)?.let { this[mediaKey] = it.path }
        (media.font as? StageOutcome.Staged)?.let { this[LauncherViewModel.KEY_WATCHFACE_FONT_PATH] = it.path }
        return skipped
    }

    private fun mediaExtension(source: File, fallback: String): String =
        source.extension.ifBlank { fallback }.sanitizeFilePart().take(8).ifBlank { fallback }

    private fun Throwable.describe(): String =
        message?.trim()?.take(240)?.takeIf { it.isNotEmpty() } ?: javaClass.simpleName

    private fun List<String>.filterAppKeys(availableAppKeys: Set<String>?): List<String> =
        if (availableAppKeys == null) this else filter(availableAppKeys::contains)

    private data class DisguiseMediaFile(val role: String?, val entry: String, val file: File)

    /** 伪装模式的媒体文件：role 为 null 的是公开图标。只收本机伪装媒体目录里的文件。 */
    private fun collectDisguiseMedia(context: Context, prefs: Preferences): List<DisguiseMediaFile> {
        val root = disguiseMediaRoot(context).canonicalFile
        fun local(path: String?): File? = path?.let(::File)?.takeIf { it.isFile }
            ?.takeIf { it.canonicalFile.path.startsWith(root.path + File.separator) }
        fun media(role: String, entry: String, file: File?, default: String) =
            file?.let { DisguiseMediaFile(role, "$entry.${DisguiseBackup.extensionOf(it.name, default)}", it) }
        val background = AppListBackgroundJson.decodeOrDefault(prefs[disguiseKey(LauncherViewModel.KEY_APP_LIST_BACKGROUND)])
        val icons = PublicAppOverrides.parse(prefs[stringPreferencesKey(
            LauncherSettingsScopes.physicalName(PublicAppOverrides.KEY, ConfigScope.DISGUISE),
        )]).values.mapNotNullTo(LinkedHashSet()) { it.iconFile }
        val iconDir = File(root, PublicAppOverrides.ICON_DIR)
        return listOfNotNull(
            media("photoEntry", DisguiseBackup.PHOTO_ENTRY, local(prefs[disguiseKey(LauncherViewModel.KEY_BUILTIN_PHOTO_PATH)]), "png"),
            media("videoEntry", DisguiseBackup.VIDEO_ENTRY, local(prefs[disguiseKey(LauncherViewModel.KEY_BUILTIN_VIDEO_PATH)]), "mp4"),
            media("fontEntry", DisguiseBackup.FONT_ENTRY, local(prefs[disguiseKey(LauncherViewModel.KEY_WATCHFACE_FONT_PATH)]), "ttf"),
            media("appListBackgroundEntry", DisguiseBackup.APP_LIST_BACKGROUND_ENTRY, local(background.imagePath), "png"),
        ) + icons.mapNotNull { name ->
            val entry = DisguiseBackup.publicIconEntry(name) ?: return@mapNotNull null
            File(iconDir, name).takeIf { it.isFile }?.let { DisguiseMediaFile(null, entry, it) }
        }
    }

    private fun previewFromManifest(manifest: JSONObject): FlueBackupPreview {
        return FlueBackupPreview(
            appVersion = manifest.optString("appVersion", "unknown"),
            createdAt = manifest.optString("createdAt", ""),
            availableOptions = FlueBackupOptions(
                settings = manifest.has("settings"),
                appOrder = manifest.has("appOrder"),
                widgetOrder = manifest.has("widgetOrder"),
                sideScreenOrder = manifest.has("sideScreenShortcuts"),
                photoWatchFace = manifest.has("photoWatchFace"),
                videoWatchFace = manifest.has("videoWatchFace"),
                watchFiles = manifest.has("watchFiles")
            )
        )
    }

    private fun Preferences.toPreferenceJson(
        includeNames: Set<String>? = null,
        excludeNames: Set<String> = emptySet()
    ): JSONObject {
        val output = JSONObject()
        asMap().forEach { (key, value) ->
            val name = key.name
            if (includeNames != null && name !in includeNames) return@forEach
            if (name in excludeNames) return@forEach
            val entry = when (value) {
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is String -> JSONObject().put("type", "string").put("value", value)
                else -> null
            }
            if (entry != null) output.put(name, entry)
        }
        return output
    }

    /**
     * 把备份里的一组设置写回，返回跳过的项数。
     *
     * 只接受 [BackupPreferenceSchema] 登记过的键，且声明的类型与值本身都必须匹配登记类型：
     * 类型错的值一旦写进 DataStore，设置收集端读取时就会 ClassCastException 并反复崩溃。
     * [availableAppKeys] 为 null 表示本机应用列表未知，应用相关键原样写回不过滤。
     */
    internal fun MutablePreferences.applyPreferenceJson(
        json: JSONObject?,
        availableAppKeys: Set<String>? = null,
        validWidgetKeys: Set<String> = emptySet(),
        excludeNames: Set<String> = emptySet()
    ): Int {
        if (json == null) return 0
        var skipped = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name in excludeNames) continue
            val expected = BackupPreferenceSchema.typeOfPhysical(name)
            val entry = json.optJSONObject(name)
            if (expected == null || entry == null || entry.optString("type") != expected.wireName) {
                skipped += 1
                continue
            }
            // 伪装模式的同名键按原名清洗。
            val logical = DisguiseBackup.logicalName(name)
            val value = if (entry.isNull("value")) null else entry.opt("value")
            when (expected) {
                BackupPreferenceSchema.Type.BOOLEAN -> {
                    if (value is Boolean) this[booleanPreferencesKey(name)] = value else skipped += 1
                }
                BackupPreferenceSchema.Type.INT -> {
                    val number = (value as? Number)?.takeIf { it.toLong() in Int.MIN_VALUE..Int.MAX_VALUE }
                    // 拒绝 1.5 这类非整数，以免被静默截断。
                    if (number != null && number.toDouble() == number.toLong().toDouble()) {
                        this[intPreferencesKey(name)] = number.toInt()
                    } else {
                        skipped += 1
                    }
                }
                BackupPreferenceSchema.Type.STRING -> {
                    if (value !is String) {
                        skipped += 1
                        continue
                    }
                    val sanitized = if (availableAppKeys == null && logical != sideScreenWidgetsKey) {
                        value
                    } else when (logical) {
                        appOrderKey, hiddenAppsKey ->
                            value.split(',').filter { availableAppKeys?.contains(it) ?: true }.joinToString(",")
                        sideScreenWidgetsKey -> value.split('|', ',').filter { it.widgetKeyPart() in validWidgetKeys }.joinToString("|")
                        sideScreenShortcutsKey -> serializeShortcuts(
                            value.split('|').map { token ->
                                token.takeIf { it.isNotBlank() }?.takeIf { availableAppKeys?.contains(it) ?: true }
                            }
                        )
                        else -> value
                    }
                    if (sanitized.isBlank() && logical in appKeyFilteredNames) {
                        remove(stringPreferencesKey(name))
                    } else {
                        this[stringPreferencesKey(name)] = sanitized
                    }
                }
            }
        }
        return skipped
    }

    private fun FlueBackupOptions.toJson(): JSONObject {
        return JSONObject()
            .put("settings", settings)
            .put("appOrder", appOrder)
            .put("widgetOrder", widgetOrder)
            .put("sideScreenOrder", sideScreenOrder)
            .put("photoWatchFace", photoWatchFace)
            .put("videoWatchFace", videoWatchFace)
            .put("watchFiles", watchFiles)
    }

    private fun String.splitCsvJson(): JSONArray {
        val array = JSONArray()
        split(',').map(String::trim).filter(String::isNotBlank).forEach(array::put)
        return array
    }

    private fun String.splitWidgetJson(): JSONArray {
        val array = JSONArray()
        split(if (contains('|')) '|' else ',').map(String::trim).filter(String::isNotBlank).forEach(array::put)
        return array
    }

    private fun String.splitShortcutJson(): JSONArray {
        val array = JSONArray()
        val parts = split('|')
        repeat(12) { index ->
            val value = parts.getOrNull(index)?.takeIf { it.isNotBlank() }
            array.put(value ?: JSONObject.NULL)
        }
        return array
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
            }
        }
    }

    private fun JSONArray?.toNullableStringList(): List<String?> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            if (isNull(index)) null else optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun serializeShortcuts(shortcuts: List<String?>): String {
        val normalized = List(12) { index -> shortcuts.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "" }
        return if (normalized.all(String::isEmpty)) "" else normalized.joinToString("|")
    }

    private fun String.widgetKeyPart(): String {
        val separator = indexOf(':')
        return if (separator > 0) substring(separator + 1) else this
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(this) }
        closeEntry()
    }

    private fun appVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun String.sanitizeFilePart(): String {
        return replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    }
}

internal fun writeBackupWatchArchive(faceDirectory: File, output: OutputStream) {
    ZipOutputStream(output).use { inner ->
        faceDirectory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(faceDirectory).invariantSeparatorsPath
                if (isFlueInstallMetadataPath(relative)) return@forEach
                inner.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(inner) }
                inner.closeEntry()
            }
    }
}
