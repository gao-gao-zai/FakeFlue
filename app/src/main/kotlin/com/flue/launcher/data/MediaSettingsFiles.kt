package com.flue.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flue.launcher.applist.background.AppListBackgroundJson
import com.flue.launcher.applist.background.AppListBackgroundConfig
import com.flue.launcher.surface.DISGUISE_MEDIA_DIR
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.viewmodel.dataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 媒体写入、配置提交和旧文件清理共用的进程内事务边界。 */
internal object MediaSettingsFiles {
    private val mutex = Mutex()

    suspend fun <T> withTransaction(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    fun mediaRoot(context: Context, scope: ConfigScope): File = when (scope) {
        ConfigScope.FULL -> context.filesDir
        ConfigScope.DISGUISE -> File(context.filesDir, DISGUISE_MEDIA_DIR)
    }

    /** 写入期间可取消，取得新文件后检查取消，提交与清理连续完成。 */
    suspend fun replace(
        context: Context,
        writeNew: () -> String?,
        update: suspend (String) -> Unit,
    ): String = withTransaction {
        val path = writeNew() ?: throw IOException("媒体文件保存失败")
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            commit(context) {
                update(path)
                committed = true
            }
            path
        } finally {
            if (!committed) File(path).delete()
        }
    }

    suspend fun edit(context: Context, update: suspend () -> Unit) = withTransaction {
        currentCoroutineContext().ensureActive()
        commit(context, update)
    }

    private suspend fun commit(context: Context, update: suspend () -> Unit) = withContext(NonCancellable) {
        val previous = context.dataStore.data.first()
        update()
        val current = context.dataStore.data.first()
        val keepPaths = referencedFiles(current).mapTo(HashSet()) { it.absoluteFile.normalize().path }
        referencedFiles(previous)
            .filterNot { it.absoluteFile.normalize().path in keepPaths }
            .filter { isManagedFile(context, it) }
            .forEach { it.delete() }
    }

    /** 在实际配置事务内按最新背景值修改字段，保留并发导入的新图片路径。 */
    fun updateBackground(
        preferences: MutablePreferences,
        transform: (AppListBackgroundConfig) -> AppListBackgroundConfig,
    ) {
        val key = LauncherViewModel.KEY_APP_LIST_BACKGROUND
        val current = AppListBackgroundJson.decodeOrDefault(preferences[key])
        preferences[key] = AppListBackgroundJson.encode(transform(current).sanitized())
    }

    /** 读取全部物理配置，保留未选择、恢复失败或另一模式仍引用的文件。 */
    fun referencedFiles(preferences: Preferences): List<File> = buildList {
        listOf(
            LauncherViewModel.KEY_BUILTIN_PHOTO_PATH,
            LauncherViewModel.KEY_BUILTIN_VIDEO_PATH,
            LauncherViewModel.KEY_WATCHFACE_FONT_PATH,
        ).forEach { key ->
            preferences[key]?.let { add(File(it)) }
            val disguise = stringPreferencesKey(
                LauncherSettingsScopes.physicalName(key.name, ConfigScope.DISGUISE),
            )
            preferences[disguise]?.let { add(File(it)) }
        }
        val background = LauncherViewModel.KEY_APP_LIST_BACKGROUND
        val disguiseBackground = stringPreferencesKey(
            LauncherSettingsScopes.physicalName(background.name, ConfigScope.DISGUISE),
        )
        listOf(background, disguiseBackground).forEach { key ->
            AppListBackgroundJson.decodeOrDefault(preferences[key]).imagePath?.let { add(File(it)) }
        }
    }

    private fun isManagedFile(context: Context, file: File): Boolean = runCatching {
        val parent = file.canonicalFile.parentFile
        listOf(ConfigScope.FULL, ConfigScope.DISGUISE).any { scope ->
            val root = mediaRoot(context, scope)
            listOf(
                "internal_watchfaces/photo",
                "internal_watchfaces/video",
                "internal_watchfaces/font",
                "internal_watchfaces/app_list_background",
                "app_list_background",
            ).any { directory -> parent == File(root, directory).canonicalFile }
        }
    }.getOrDefault(false)
}
