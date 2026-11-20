package com.flue.launcher.surface.config

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.flue.launcher.surface.mode.ConfigScope

/**
 * 物理 `launcher_settings` 与“某一模式看到的配置”之间的纯函数映射（R06）。
 *
 * - 完整模式：看到原版键名的值，看不到伪装前缀的键。
 * - 伪装模式：设备级键直接读写原值；随模式键读写带前缀的副本，没有副本时就是“未设置”，
 *   由原版代码套用默认值。
 */
object ModeScopedPreferences {

    fun project(physical: Preferences, scope: ConfigScope): Preferences {
        val view = mutablePreferencesOf()
        for ((key, value) in physical.asMap()) {
            val name = key.name
            val logical: String? = when (scope) {
                ConfigScope.FULL -> name.takeUnless { LauncherSettingsScopes.isDisguisePhysical(it) }
                ConfigScope.DISGUISE -> when {
                    LauncherSettingsScopes.isDisguisePhysical(name) ->
                        LauncherSettingsScopes.logicalNameOfDisguise(name)
                            .takeUnless { LauncherSettingsScopes.isDeviceGlobal(it) }
                    LauncherSettingsScopes.isDeviceGlobal(name) -> name
                    else -> null
                }
            }
            if (logical != null) view.putUntyped(logical, value)
        }
        return view.toPreferences()
    }

    /**
     * 把视图上的修改（[before] → [after]）写回物理配置。只写变化的键，其他模式的值原样保留。
     */
    fun applyViewChange(
        physical: Preferences,
        before: Preferences,
        after: Preferences,
        scope: ConfigScope,
    ): Preferences {
        val beforeMap = before.asMap().mapKeys { it.key.name }
        val afterMap = after.asMap().mapKeys { it.key.name }
        if (beforeMap.keys == afterMap.keys && beforeMap.all { (k, v) -> valuesEqual(v, afterMap[k]) }) {
            return physical
        }
        val result = physical.toMutablePreferences()
        for (name in beforeMap.keys + afterMap.keys) {
            // 视图里不应出现物理前缀；出现说明调用方绕过了映射，忽略以免写坏另一模式。
            if (LauncherSettingsScopes.isDisguisePhysical(name)) continue
            val old = beforeMap[name]
            val new = afterMap[name]
            if (valuesEqual(old, new)) continue
            val target = LauncherSettingsScopes.physicalName(name, scope)
            if (new == null) result.remove(stringPreferencesKey(target)) else result.putUntyped(target, new)
        }
        return result.toPreferences()
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean =
        if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b

    @Suppress("UNCHECKED_CAST")
    private fun androidx.datastore.preferences.core.MutablePreferences.putUntyped(name: String, value: Any) {
        when (value) {
            is String -> this[stringPreferencesKey(name)] = value
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is Double -> this[doublePreferencesKey(name)] = value
            is ByteArray -> this[byteArrayPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value as Set<String>
            else -> error("Unsupported preference value type for $name: ${value::class}")
        }
    }
}
