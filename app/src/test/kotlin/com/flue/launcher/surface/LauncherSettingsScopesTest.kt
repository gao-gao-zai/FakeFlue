package com.flue.launcher.surface

import androidx.datastore.preferences.core.Preferences
import com.flue.launcher.surface.config.LauncherSettingsScopes
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.viewmodel.LauncherViewModel
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R06：LauncherViewModel 声明的每个设置键都必须归入设备级或随模式之一。 */
class LauncherSettingsScopesTest {

    private fun declaredKeyNames(): Set<String> =
        LauncherViewModel::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                (field.get(null) as Preferences.Key<*>).name
            }
            .toSet()

    @Test
    fun everyDeclaredKeyIsClassifiedExactlyOnce() {
        val declared = declaredKeyNames()
        assertTrue("没有找到任何设置键，反射方式可能失效", declared.size > 100)
        val overlap = LauncherSettingsScopes.deviceGlobalKeys intersect LauncherSettingsScopes.modeScopedKeys
        assertEquals("同一个键不能同时是设备级和随模式", emptySet<String>(), overlap)
        val classified = LauncherSettingsScopes.deviceGlobalKeys + LauncherSettingsScopes.modeScopedKeys
        assertEquals("未分类的设置键", emptySet<String>(), declared - classified)
        assertEquals("分类表里有已不存在的键", emptySet<String>(), classified - declared)
    }

    @Test
    fun physicalNamesFollowScope() {
        assertEquals("selected_watchface_id", LauncherSettingsScopes.physicalName("selected_watchface_id", ConfigScope.FULL))
        assertEquals(
            LauncherSettingsScopes.DISGUISE_PREFIX + "selected_watchface_id",
            LauncherSettingsScopes.physicalName("selected_watchface_id", ConfigScope.DISGUISE),
        )
        assertEquals("global_ui_scale_percent", LauncherSettingsScopes.physicalName("global_ui_scale_percent", ConfigScope.DISGUISE))
    }
}
