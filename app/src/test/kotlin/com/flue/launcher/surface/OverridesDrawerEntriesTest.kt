package com.flue.launcher.surface

import com.flue.launcher.surface.access.SettingsEntryGate
import com.flue.launcher.surface.access.SettingsEntryGate.Decision
import com.flue.launcher.surface.apps.HiddenDrawerCatalog
import com.flue.launcher.surface.apps.PublicAppCandidate
import com.flue.launcher.surface.apps.PublicAppOverride
import com.flue.launcher.surface.apps.PublicAppOverrides
import com.flue.launcher.surface.config.DisguiseBackup
import com.flue.launcher.surface.mode.ConfigScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2b-4：公开名称／图标覆盖、隐藏完整抽屉、完整设置进入检查、伪装媒体备份（R08、R10、R14）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OverridesDrawerEntriesTest {

    // ---- 公开名称／图标覆盖 ----

    @Test
    fun overridesRoundTripAndDropEmptyOrUnsafe() {
        val raw = PublicAppOverrides.serialize(
            mapOf(
                "a/.Main" to PublicAppOverride(label = "  计算器\n", iconFile = "icon_1.png"),
                "b/.Main" to PublicAppOverride(label = " ", iconFile = null),
                "c/.Main" to PublicAppOverride(iconFile = "../evil.png"),
            ),
        )
        val parsed = PublicAppOverrides.parse(raw)
        assertEquals(mapOf("a/.Main" to PublicAppOverride("计算器", "icon_1.png")), parsed)
        assertEquals(emptyMap<String, PublicAppOverride>(), PublicAppOverrides.parse("not json"))
        assertEquals(emptyMap<String, PublicAppOverride>(), PublicAppOverrides.parse(null))
    }

    @Test
    fun unsafeIconNamesRejected() {
        assertTrue(PublicAppOverrides.isSafeIconFile("icon_ab12.png"))
        listOf("../x.png", "a/b.png", ".hidden", "", "null", null).forEach {
            assertFalse(it.toString(), PublicAppOverrides.isSafeIconFile(it))
        }
    }

    @Test
    fun updateRemovesEntryWhenReset() {
        val one = PublicAppOverrides.update(emptyMap(), "k") { it.copy(label = "名字") }
        assertEquals(PublicAppOverride("名字", null), one["k"])
        val withIcon = PublicAppOverrides.update(one, "k") { it.copy(iconFile = "icon_2.png") }
        assertEquals(PublicAppOverride("名字", "icon_2.png"), withIcon["k"])
        assertTrue(PublicAppOverrides.update(withIcon, "k") { PublicAppOverride() }.isEmpty())
    }

    @Test
    fun labelsNormalizedAndCapped() {
        assertNull(PublicAppOverrides.normalizeLabel("\t \n"))
        assertEquals("a b", PublicAppOverrides.normalizeLabel(" a\r\nb "))
        // 普通字母 r、n、t 和反斜杠不受影响，只合并真正的回车、换行、制表符。
        assertEquals("Internet", PublicAppOverrides.normalizeLabel("Internet"))
        assertEquals("a\\b x", PublicAppOverrides.normalizeLabel("a\\b\tx"))
        assertEquals(PublicAppOverrides.MAX_LABEL_LENGTH, PublicAppOverrides.normalizeLabel("x".repeat(99))!!.length)
    }

    @Test
    fun orphanIconsAreOnlyUnreferencedFiles() {
        val overrides = mapOf("k" to PublicAppOverride(iconFile = "icon_keep.png"))
        assertEquals(
            setOf("icon_old.png", ".icon_new.png.tmp"),
            PublicAppOverrides.orphanIcons(overrides, listOf("icon_keep.png", "icon_old.png", ".icon_new.png.tmp")),
        )
    }

    // ---- 隐藏完整抽屉 ----

    private fun app(key: String, label: String, latin: String? = null) =
        PublicAppCandidate(key = key, label = label, packageName = "pkg.$key", desktopIndex = 0, latin = latin)

    private val apps = listOf(
        app("wx", "微信", "wei xin"),
        app("al", "支付宝", "zhi fu bao"),
        app("cal", "Calculator"),
        app("cam", "camera"),
        app("num", "12306"),
        app("bb", "Browser"),
    )

    private val byText = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }

    @Test
    fun drawerGroupsByInitialWithOtherLast() {
        val layout = HiddenDrawerCatalog.arrange(apps, "", emptySet(), byText)
        assertEquals(listOf('B', 'C', 'W', 'Z', '#'), layout.letters)
        assertEquals(listOf("Calculator", "camera"), layout.sections[1].entries.map { it.label })
        assertTrue(layout.pinned.isEmpty())
    }

    @Test
    fun pinnedComeFirstWithoutDuplicatesAndStalePinsIgnored() {
        val layout = HiddenDrawerCatalog.arrange(apps, "", setOf("al", "gone"), byText)
        assertEquals(listOf("al"), layout.pinned.map { it.key })
        assertFalse(layout.sections.any { section -> section.entries.any { it.key == "al" } })
        assertEquals(emptySet<String>(), HiddenDrawerCatalog.togglePin(setOf("al"), "al"))
        assertEquals(setOf("al", "wx"), HiddenDrawerCatalog.togglePin(setOf("al"), "wx"))
    }

    @Test
    fun drawerSearchUsesInitialsAndLatin() {
        val layout = HiddenDrawerCatalog.arrange(apps, "zfb", emptySet(), byText)
        assertEquals(listOf("al"), layout.sections.flatMap { it.entries }.map { it.key })
        assertTrue(HiddenDrawerCatalog.arrange(apps, "nothing-matches", emptySet(), byText).isEmpty)
    }

    @Test
    fun letterIndexPointsAtSectionHeader() {
        val layout = HiddenDrawerCatalog.arrange(apps, "", setOf("wx"), byText)
        // 置顶 1 项；B 组标题在 1，B 组 1 项；C 组标题在 3。
        assertEquals(1, HiddenDrawerCatalog.indexOfLetter(layout, 'B'))
        assertEquals(3, HiddenDrawerCatalog.indexOfLetter(layout, 'C'))
        // 没有 D 组时跳到其后最近的一组（Z）。
        assertEquals(HiddenDrawerCatalog.indexOfLetter(layout, 'Z'), HiddenDrawerCatalog.indexOfLetter(layout, 'D'))
        assertEquals(layout.pinned.size + layout.sections.dropLast(1).sumOf { 1 + it.entries.size },
            HiddenDrawerCatalog.indexOfLetter(layout, '#'))
    }

    // ---- 完整设置进入检查 ----

    @Test
    fun settingsGateOnlyAllowsWatchFaceEntryWithoutManagement() {
        val wf = SettingsEntryGate.WATCH_FACES_DESTINATION
        assertEquals(Decision.ALLOW, SettingsEntryGate.decide(ConfigScope.FULL, false, null, false))
        assertEquals(Decision.ALLOW, SettingsEntryGate.decide(ConfigScope.DISGUISE, true, "surface", false))
        assertEquals(Decision.ALLOW, SettingsEntryGate.decide(ConfigScope.DISGUISE, false, wf, true))
        // 外部 Intent／静态快捷方式：没有目的地或目的地不是表盘页。
        assertEquals(Decision.REDIRECT_PUBLIC, SettingsEntryGate.decide(ConfigScope.DISGUISE, false, null, false))
        assertEquals(Decision.REDIRECT_PUBLIC, SettingsEntryGate.decide(ConfigScope.DISGUISE, false, "surface", false))
        assertEquals(Decision.REDIRECT_PUBLIC, SettingsEntryGate.decide(ConfigScope.DISGUISE, false, "tools", true))
        // 表盘页但带返回栈（会退到完整设置首页）。
        assertEquals(Decision.REDIRECT_PUBLIC, SettingsEntryGate.decide(ConfigScope.DISGUISE, false, wf, false))
    }

    @Test
    fun settingsGateDestinationMatchesSettingsActivity() {
        assertEquals(com.flue.launcher.SETTINGS_DESTINATION_WATCH_FACES, SettingsEntryGate.WATCH_FACES_DESTINATION)
    }

    // ---- 伪装媒体备份 ----

    @Test
    fun disguiseBackupNamesAndIconEntries() {
        assertEquals(
            setOf("fflue.disguise::builtin_photo_path"),
            DisguiseBackup.localOnlyPhysicalNames(listOf("builtin_photo_path")),
        )
        assertEquals("app_order", DisguiseBackup.logicalName("fflue.disguise::app_order"))
        assertEquals("app_order", DisguiseBackup.logicalName("app_order"))
        val entry = DisguiseBackup.publicIconEntry("icon_1.png")!!
        assertEquals("icon_1.png", DisguiseBackup.publicIconName(entry))
        assertNull(DisguiseBackup.publicIconEntry("../x.png"))
        assertNull(DisguiseBackup.publicIconName("disguise/public_icons/../x.png"))
        assertNull(DisguiseBackup.publicIconName("other/icon_1.png"))
        assertEquals("jpg", DisguiseBackup.extensionOf("a.JPG", "png"))
        assertEquals("png", DisguiseBackup.extensionOf("noext", "png"))
        assertEquals("png", DisguiseBackup.extensionOf("a.p/g", "png"))
    }
}
