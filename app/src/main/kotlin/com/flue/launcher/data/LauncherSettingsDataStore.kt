package com.flue.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * `launcher_settings` DataStore 的委托（R37）。
 *
 * 与 `preferencesDataStore(name)` 相同的文件路径与单例语义（每个进程每个文件只有一个实例），
 * 区别只有一个：文件损坏时先把原始字节原样复制到
 * `launcher_settings.preferences_pb.corrupt-<时间>`，再交给 DataStore 用空配置重建。
 *
 * 以前没有 corruptionHandler：损坏文件让每次读取都抛 CorruptionException，桌面启动即崩、
 * 反复崩；而如果改成「读不出就当空」又会被下一次写入覆盖、证据全无。现在桌面能起来，
 * 原文件也留着可供诊断或人工恢复。
 */
class CorruptionPreservingPreferencesDelegate(
    private val name: String,
) : ReadOnlyProperty<Context, DataStore<Preferences>> {
    private val lock = Any()

    @Volatile
    private var instance: DataStore<Preferences>? = null

    override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: create(thisRef.applicationContext).also { instance = it }
        }
    }

    private fun create(appContext: Context): DataStore<Preferences> {
        val file = appContext.preferencesDataStoreFile(name)
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { error ->
                preserveCorruptFile(file, error)
                emptyPreferences()
            },
            migrations = emptyList(),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file },
        )
    }

    companion object {
        private const val TAG = "FlueSettingsStore"

        /** 复制损坏文件，返回副本；复制失败返回 null（不阻止恢复启动）。 */
        fun preserveCorruptFile(file: File, error: CorruptionException? = null, nowMs: Long = System.currentTimeMillis()): File? {
            if (!file.isFile) return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(nowMs))
            val copy = File(file.parentFile, "${file.name}.corrupt-$stamp")
            return runCatching {
                file.copyTo(copy, overwrite = false)
                Log.w(TAG, "Settings file was unreadable; preserved a copy as ${copy.name}", error)
                copy
            }.getOrNull()
        }
    }
}
