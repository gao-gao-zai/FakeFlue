package com.flue.launcher.surface

import com.flue.launcher.surface.access.AccessDecision
import com.flue.launcher.surface.access.FeatureAccessPolicy
import com.flue.launcher.surface.access.SurfaceActivityGuard
import com.flue.launcher.surface.access.SurfaceFeature
import com.flue.launcher.surface.config.WatchFaceCopyOnWrite
import com.flue.launcher.surface.mode.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R08 功能访问规则表与 R06 写时复制判断。 */
class FeatureAccessPolicyTest {

    @Test
    fun fullAndExternalAllowEverything() {
        for (mode in listOf(SurfaceMode.FULL, SurfaceMode.EXTERNAL)) {
            for (feature in SurfaceFeature.entries) {
                assertEquals("$mode/$feature", AccessDecision.ALLOWED, FeatureAccessPolicy.decide(mode, feature))
            }
        }
    }

    @Test
    fun disguiseMatchesR08Table() {
        val d = SurfaceMode.DISGUISE
        assertEquals(AccessDecision.ALLOWED, FeatureAccessPolicy.decide(d, SurfaceFeature.WATCH_FACE_BASIC))
        assertEquals(AccessDecision.ALLOWED, FeatureAccessPolicy.decide(d, SurfaceFeature.WATCH_FACE_EDITOR))
        assertEquals(AccessDecision.ALLOWED, FeatureAccessPolicy.decide(d, SurfaceFeature.TOOLS))
        assertEquals(AccessDecision.REQUIRES_MANAGEMENT, FeatureAccessPolicy.decide(d, SurfaceFeature.ADVANCED_SETTINGS))
        assertEquals(AccessDecision.ALLOWED, FeatureAccessPolicy.decide(d, SurfaceFeature.ADVANCED_SETTINGS, inManagement = true))
        for (feature in listOf(
            SurfaceFeature.SCRIPT_EDITOR, SurfaceFeature.WATCH_FACE_EXPORT, SurfaceFeature.FILE_MANAGER,
            SurfaceFeature.ADVANCED_CANVAS, SurfaceFeature.WATCH_STORE, SurfaceFeature.AGENT,
        )) {
            assertEquals(AccessDecision.DENIED, FeatureAccessPolicy.decide(d, feature))
            assertEquals("管理访问也不能打开 $feature", AccessDecision.DENIED, FeatureAccessPolicy.decide(d, feature, inManagement = true))
        }
    }

    @Test
    fun guardedActivitiesExist() {
        for (name in SurfaceActivityGuard.ACTIVITY_FEATURES.keys) {
            Class.forName(name, false, javaClass.classLoader)
        }
    }

    @Test
    fun copyOnWriteOnlyWhenOtherModeUsesTheFace() {
        assertTrue(WatchFaceCopyOnWrite.mustCopy("face_a", "face_a"))
        assertFalse(WatchFaceCopyOnWrite.mustCopy("face_a", "face_b"))
        assertFalse(WatchFaceCopyOnWrite.mustCopy("face_a", null))
        assertFalse(WatchFaceCopyOnWrite.mustCopy(null, null))
        assertFalse(WatchFaceCopyOnWrite.mustCopy("", ""))
    }
}
