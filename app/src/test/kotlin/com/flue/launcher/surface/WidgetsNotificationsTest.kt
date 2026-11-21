package com.flue.launcher.surface

import com.flue.launcher.surface.apps.NotificationVisibility
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.ThirdPartyScreenOff
import com.flue.launcher.surface.widgets.DisguiseWidgetRules
import com.flue.launcher.surface.widgets.WidgetLaunchCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2b-3：伪装小组件、小组件点击目标、通知放行和第三方应用熄屏（R09、R11）。 */
class WidgetsNotificationsTest {

    private val own = "com.gaozai.surface"
    private val publicApps = setOf("com.pub.weather/com.pub.weather.Main", "com.pub.music")
    private fun isPublic(pkg: String?) =
        com.flue.launcher.surface.apps.PublicAppRules.isPackagePublic(pkg, publicApps, own)

    @Test
    fun widgetEditingOnlyInFullOrManagement() {
        assertTrue(DisguiseWidgetRules.editable(ConfigScope.FULL, managementSession = false))
        assertFalse(DisguiseWidgetRules.editable(ConfigScope.DISGUISE, managementSession = false))
        assertTrue(DisguiseWidgetRules.editable(ConfigScope.DISGUISE, managementSession = true))
    }

    @Test
    fun emptyOrDisabledWidgetPageCannotBeEnteredInDisguise() {
        // 完整模式（可编辑）仍可进空页添加。
        assertTrue(DisguiseWidgetRules.pageAvailable(enabled = true, editable = true, hasUsableWidget = false))
        assertFalse(DisguiseWidgetRules.pageAvailable(enabled = true, editable = false, hasUsableWidget = false))
        assertTrue(DisguiseWidgetRules.pageAvailable(enabled = true, editable = false, hasUsableWidget = true))
        // 未启用时谁都进不去。
        assertFalse(DisguiseWidgetRules.pageAvailable(enabled = false, editable = true, hasUsableWidget = true))
    }

    @Test
    fun usableWidgetNeedsPublicProviderAndValidBinding() {
        val providers = mapOf(
            "1:com.pub.weather/.W" to "com.pub.weather",
            "2:com.hidden.chat/.W" to "com.hidden.chat",
            "3:com.pub.music/.W" to "com.pub.music",
        )
        fun usable(slots: List<String?>, valid: Set<String>) = DisguiseWidgetRules.hasUsableWidget(
            slots = slots,
            providerPackageOf = providers::get,
            isPackagePublic = ::isPublic,
            isBindingUsable = { it in valid },
        )
        assertFalse(usable(emptyList(), emptySet()))
        assertFalse(usable(listOf(null, ""), emptySet()))
        // 绑定有效但提供方未公开。
        assertFalse(usable(listOf("2:com.hidden.chat/.W"), setOf("2:com.hidden.chat/.W")))
        // 提供方公开但绑定失效（全部失效）。
        assertFalse(usable(listOf("1:com.pub.weather/.W", "3:com.pub.music/.W"), emptySet()))
        // 提供方已卸载。
        assertFalse(usable(listOf("9:com.gone/.W"), setOf("9:com.gone/.W")))
        assertTrue(usable(listOf("2:com.hidden.chat/.W", "3:com.pub.music/.W"), setOf("3:com.pub.music/.W")))
    }

    @Test
    fun widgetClickChecksCreatorAndExplicitFillInTarget() {
        assertTrue(WidgetLaunchCheck.allowsDispatch("com.pub.weather", null, ::isPublic))
        assertFalse(WidgetLaunchCheck.allowsDispatch("com.hidden.chat", null, ::isPublic))
        assertFalse(WidgetLaunchCheck.allowsDispatch(null, null, ::isPublic))
        // 公开应用的小组件借填充 Intent 指向未公开应用。
        assertFalse(WidgetLaunchCheck.allowsDispatch("com.pub.weather", "com.hidden.chat", ::isPublic))
        assertTrue(WidgetLaunchCheck.allowsDispatch("com.pub.weather", "com.pub.music", ::isPublic))
    }

    @Test
    fun widgetClickObservedTargetMustBePublic() {
        val ime = "com.example.ime"
        assertFalse(WidgetLaunchCheck.violates("com.pub.weather", own, ime, ::isPublic))
        assertFalse(WidgetLaunchCheck.violates(own, own, ime, ::isPublic))
        assertFalse(WidgetLaunchCheck.violates("com.android.systemui", own, ime, ::isPublic))
        assertFalse(WidgetLaunchCheck.violates(ime, own, ime, ::isPublic))
        assertTrue(WidgetLaunchCheck.violates("com.hidden.chat", own, ime, ::isPublic))
        assertTrue(WidgetLaunchCheck.violates("com.android.settings", own, null, ::isPublic))
    }

    @Test
    fun notificationsHiddenUnlessPublicOrAllowed() {
        val allowed = setOf("com.hidden.chat")
        assertTrue(NotificationVisibility.isVisible("com.pub.music", publicApps, allowed, own))
        assertTrue(NotificationVisibility.isVisible("com.pub.weather", publicApps, allowed, own))
        assertTrue(NotificationVisibility.isVisible("com.hidden.chat", publicApps, allowed, own))
        assertFalse(NotificationVisibility.isVisible("com.hidden.mail", publicApps, allowed, own))
        assertFalse(NotificationVisibility.isVisible(null, publicApps, allowed, own))
        assertFalse(NotificationVisibility.isVisible("", publicApps, setOf(""), own))
        // 完整模式不限制。
        assertTrue(NotificationVisibility.isVisible("com.hidden.mail", null, null, own))
        // 放行只管展示，打开仍要求公开。
        assertFalse(NotificationVisibility.canOpenDirectly("com.hidden.chat", publicApps, own))
        assertTrue(NotificationVisibility.canOpenDirectly("com.pub.music", publicApps, own))
        assertTrue(NotificationVisibility.canOpenDirectly("com.hidden.chat", null, own))
    }

    @Test
    fun thirdPartyScreenOffReturnsToFaceByDefaultInDisguise() {
        fun ret(
            scope: ConfigScope = ConfigScope.DISGUISE,
            keep: Boolean = false,
            own: Boolean = false,
            call: Boolean = false,
        ) = ThirdPartyScreenOff.shouldReturnToFace(scope, keep, own, call)
        assertTrue(ret())
        assertFalse(ret(scope = ConfigScope.FULL))
        assertFalse(ret(keep = true))
        // 本应用自己的页面在前台：交给桌面自己的熄屏规则。
        assertFalse(ret(own = true))
        assertFalse(ret(call = true))
    }

    @Test
    fun otherScreenOffForegroundPathsHonorSameExemptions() {
        fun allowed(
            scope: ConfigScope = ConfigScope.DISGUISE,
            keep: Boolean = false,
            own: Boolean = false,
            call: Boolean = false,
        ) = ThirdPartyScreenOff.foregroundAllowed(scope, keep, own, call)
        assertTrue(allowed())
        assertFalse(allowed(keep = true))
        assertFalse(allowed(call = true))
        // 桌面自己在前台（息屏显示）或完整模式：保持原行为。
        assertTrue(allowed(own = true, keep = true, call = true))
        assertTrue(allowed(scope = ConfigScope.FULL, keep = true, call = true))
    }
}
