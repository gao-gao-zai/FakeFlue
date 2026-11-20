package com.flue.launcher.surface.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flue.launcher.surface.mode.ConfigScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 按当前模式投影的 `launcher_settings`（R06）。
 *
 * 原版代码照旧通过 `DataStore<Preferences>` 的 `data` / `edit {}` 读写；这里把读写映射到同一个
 * 物理 DataStore 的对应键上，因此仍只有一份正式配置。[scope] 变化时，[data] 立即发出另一模式的视图，
 * 已经在收集的界面会随之切换。
 */
class ModeScopedDataStore(
    private val physical: DataStore<Preferences>,
    private val scope: StateFlow<ConfigScope>,
) : DataStore<Preferences> {

    override val data: Flow<Preferences> =
        combine(physical.data, scope) { prefs, current -> ModeScopedPreferences.project(prefs, current) }
            .distinctUntilChanged()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        // 提交方固定了模式（[ConfigScopePin]）就按它写；否则在同一次物理事务里取当前模式，
        // 保证读、改、写落在同一套配置上。
        val pinned = currentCoroutineContext()[ConfigScopePin]?.scope
        var written: ConfigScope = pinned ?: scope.value
        val result = physical.updateData { current ->
            val target = pinned ?: scope.value
            written = target
            val view = ModeScopedPreferences.project(current, target)
            val changed = transform(view)
            ModeScopedPreferences.applyViewChange(current, view, changed, target)
        }
        return ModeScopedPreferences.project(result, written)
    }
}
