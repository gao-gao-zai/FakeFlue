package com.flue.launcher.surface

import com.flue.launcher.surface.access.SettingsSearchGate
import com.flue.launcher.surface.apps.OpenFolderVisibility
import com.flue.launcher.surface.apps.PublicAppCandidate
import com.flue.launcher.surface.apps.PublicAppCatalog
import com.flue.launcher.surface.apps.PublicAppRules
import com.flue.launcher.surface.apps.PublicAppSort
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.mode.DisguiseScreenOff
import com.flue.launcher.surface.mode.SurfaceMode
import com.flue.launcher.ui.navigation.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R08、R10、R11：公开应用白名单、选择器检索排序、设置搜索门禁、熄屏回表盘。 */
class PublicAppsTest {

    @Test
    fun whitelistRoundTrips() {
        val entries = setOf("com.b/.Main", "com.a/.Main", " ")
        val raw = PublicAppRules.serialize(entries)
        assertEquals("com.a/.Main,com.b/.Main", raw)
        assertEquals(setOf("com.a/.Main", "com.b/.Main"), PublicAppRules.parse(raw))
        assertEquals(emptySet<String>(), PublicAppRules.parse(null))
        assertEquals(emptySet<String>(), PublicAppRules.parse(" , ,"))
    }

    @Test
    fun builtInAlwaysPublicOthersNeedSelection() {
        val wl = setOf("com.a/com.a.Main")
        assertTrue(PublicAppRules.isPublic("builtin", "com.self", builtIn = true, whitelist = emptySet()))
        assertTrue(PublicAppRules.isPublic("com.a/com.a.Main", "com.a", builtIn = false, whitelist = wl))
        assertFalse(PublicAppRules.isPublic("com.a/com.a.Other", "com.a", builtIn = false, whitelist = wl))
        assertFalse(PublicAppRules.isPublic("com.new/.Main", "com.new", builtIn = false, whitelist = wl))
    }

    @Test
    fun packageCheckForNotificationsAndTapActions() {
        val wl = setOf("com.a/com.a.Main")
        assertTrue(PublicAppRules.isPackagePublic("com.x", null, "com.self"))
        assertTrue(PublicAppRules.isPackagePublic("com.a", wl, "com.self"))
        assertTrue(PublicAppRules.isPackagePublic("com.self", wl, "com.self"))
        assertFalse(PublicAppRules.isPackagePublic("com.ab", wl, "com.self"))
        assertFalse(PublicAppRules.isPackagePublic(null, wl, "com.self"))
        assertFalse(PublicAppRules.isPackagePublic("com.a", emptySet(), "com.self"))
    }

    private val wechat = PublicAppCandidate("com.tencent.mm/.Main", "微信", "com.tencent.mm", 2, latin = "wei xin")
    private val maps = PublicAppCandidate("com.google.maps/.Main", "Google Maps", "com.google.maps", 0)
    private val alarm = PublicAppCandidate("com.clock/.Main", "Alarm", "com.clock", 1)
    private val all = listOf(wechat, maps, alarm)

    @Test
    fun searchByNamePackageInitialsAndPinyin() {
        assertTrue(wechat.matches("微"))
        assertTrue(wechat.matches("tencent"))
        assertTrue(wechat.matches("wx"))
        assertTrue(wechat.matches("weixin"))
        assertTrue(wechat.matches("wei x"))
        assertTrue(maps.matches("gm"))
        assertTrue(maps.matches("MAPS"))
        assertFalse(maps.matches("wx"))
        assertTrue(alarm.matches("  "))
    }

    @Test
    fun withoutTransliterationChineseNamesStillMatchByNameAndPackage() {
        val noLatin = wechat.copy(latin = null)
        assertTrue(noLatin.matches("微信"))
        assertTrue(noLatin.matches("tencent"))
        assertFalse(noLatin.matches("wx"))
    }

    @Test
    fun sortsDoNotDependOnSelectionChangesDuringEditing() {
        val name = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }
        assertEquals(listOf(maps, alarm, wechat), PublicAppCatalog.arrange(all, "", PublicAppSort.DESKTOP, emptySet(), name))
        assertEquals(listOf(alarm, maps, wechat), PublicAppCatalog.arrange(all, "", PublicAppSort.NAME, emptySet(), name))
        assertEquals(
            listOf(wechat, maps, alarm),
            PublicAppCatalog.arrange(all, "", PublicAppSort.SELECTED_FIRST, setOf(wechat.key), name),
        )
        assertEquals(listOf(wechat), PublicAppCatalog.arrange(all, "wx", PublicAppSort.DESKTOP, emptySet(), name))
    }

    @Test
    fun settingsSearchHidesDeniedFeaturesOnlyInDisguise() {
        val d = SurfaceMode.DISGUISE
        assertFalse(SettingsSearchGate.isVisible(d, "WATCH_FACES", "watchface_store"))
        assertFalse(SettingsSearchGate.isVisible(d, "TOOLS", "watch_store_market_url"))
        assertFalse(SettingsSearchGate.isVisible(d, "WATCH_FACE_MORE", "watchface_canvas_entry"))
        assertFalse(SettingsSearchGate.isVisible(d, "AGENT", null))
        assertFalse(SettingsSearchGate.isVisible(d, "DONATE", null))
        assertTrue(SettingsSearchGate.isVisible(d, "APPEARANCE", "blur_toggle"))
        assertTrue(SettingsSearchGate.isVisible(SurfaceMode.FULL, "WATCH_FACES", "watchface_store"))
        assertTrue(SettingsSearchGate.isVisible(SurfaceMode.EXTERNAL, "AGENT", null))
    }

    @Test
    fun screenOffReturnsToFaceOnlyInDisguise() {
        assertEquals(ScreenState.Face, DisguiseScreenOff.targetState(ConfigScope.DISGUISE, ScreenState.Apps))
        assertEquals(ScreenState.Face, DisguiseScreenOff.targetState(ConfigScope.DISGUISE, ScreenState.Notifications))
        assertNull(DisguiseScreenOff.targetState(ConfigScope.DISGUISE, ScreenState.Face))
        assertNull(DisguiseScreenOff.targetState(ConfigScope.DISGUISE, ScreenState.App))
        assertNull(DisguiseScreenOff.targetState(ConfigScope.FULL, ScreenState.Apps))
    }

    @Test
    fun openFolderFollowsWhitelistChanges() {
        assertEquals(OpenFolderVisibility.Update.Close, OpenFolderVisibility.update(null, listOf("a")))
        assertEquals(OpenFolderVisibility.Update.Close, OpenFolderVisibility.update("folder", emptyList<String>()))
        assertEquals(
            OpenFolderVisibility.Update.Show("folder'", listOf("a")),
            OpenFolderVisibility.update("folder'", listOf("a")),
        )
    }
}
