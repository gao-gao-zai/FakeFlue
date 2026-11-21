package com.flue.launcher.watchface.standard.prepared

import android.content.Context
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import java.io.File

/**
 * R36 进程级入口：只为 `filesDir/watchmaker_faces/<id>/` 下的已安装表盘提供缓存。
 * 编辑器草稿（cacheDir 下）、临时预览等其它目录一律返回 null，走原加载路径，
 * 草稿与保存事务也就不会因缓存被改写。
 */
object WatchmakerPreparedCache {
    private const val DIRECTORY_NAME = "watchmaker-prepared"

    @Volatile
    private var shared: WatchPreparedCache? = null

    internal fun forInstalledRoot(context: Context, canonicalRoot: File): WatchPreparedCache? = runCatching {
        if (!isInstalledFaceRoot(context, canonicalRoot)) return@runCatching null
        get(context)
    }.getOrNull()

    /** 导入、保存、替换、删除之后调用；失败不影响调用方。 */
    fun invalidate(context: Context, installRoot: File?) {
        installRoot ?: return
        runCatching { get(context).invalidate(installRoot.canonicalFile.absolutePath) }
    }

    internal fun isInstalledFaceRoot(context: Context, canonicalRoot: File): Boolean {
        val facesRoot = File(context.filesDir, WatchmakerWatchFaceStorage.ROOT_DIRECTORY).canonicalFile
        return canonicalRoot.parentFile?.canonicalFile == facesRoot
    }

    private fun get(context: Context): WatchPreparedCache {
        val application = context.applicationContext ?: context
        val directory = File(application.cacheDir, DIRECTORY_NAME)
        shared?.takeIf { it.cacheDirectory == directory }?.let { return it }
        synchronized(this) {
            shared?.takeIf { it.cacheDirectory == directory }?.let { return it }
            return WatchPreparedCache(
                directory = directory,
                engineIdentity = engineIdentity(application),
            ).also { shared = it }
        }
    }

    /** 格式 + 引擎版本 + APK 安装身份：升级应用后旧条目自动不兼容。 */
    private fun engineIdentity(context: Context): String {
        val packageIdentity = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            "${info.versionCode}/${info.versionName}/${info.lastUpdateTime}"
        }.getOrDefault("unknown")
        return "format=${WatchPreparedCodec.FORMAT_VERSION};engine=${WatchPreparedCodec.ENGINE_VERSION};" +
            "luaj=3.0.1;app=$packageIdentity"
    }
}
