package com.flue.launcher.surface.startup

import com.flue.launcher.watchface.jbwatch.JbWatchFaceStorage
import com.flue.launcher.watchface.standard.WatchInstallMetadata
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceStorage
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StartupTimelineTest {
    private var now = 0L
    private val lines = mutableListOf<String>()
    private val timeline = StartupTimeline.Recorder(clock = { now }, processStart = { 1_000L }, sink = { lines += it })

    @Test
    fun faceAndGuardAreTimedSeparatelyFromProcessStart() {
        now = 1_120L; timeline.mark(StartupTimeline.Mark.APP_CREATE)
        now = 1_400L; timeline.mark(StartupTimeline.Mark.FIRST_FRAME)
        now = 1_900L; timeline.mark(StartupTimeline.Mark.FACE_INTERACTIVE)
        assertTrue("导航限制还没结论时不输出", lines.isEmpty())
        now = 3_500L; timeline.mark(StartupTimeline.Mark.GUARD_ACTIVE)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line, line.contains("app_create=120ms"))
        assertTrue(line, line.contains("face_interactive=900ms"))
        assertTrue(line, line.contains("guard_active=2500ms"))
        assertTrue(line, line.contains("face_selected=-"))
    }

    @Test
    fun onlyTheColdStartIsReportedAndFirstMarkWins() {
        now = 1_200L; timeline.mark(StartupTimeline.Mark.FACE_INTERACTIVE)
        now = 1_300L; timeline.guardNotNeeded()
        now = 5_000L; timeline.mark(StartupTimeline.Mark.FACE_INTERACTIVE)
        timeline.mark(StartupTimeline.Mark.GUARD_ACTIVE)
        timeline.flushIfPending()

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("face_interactive=200ms"))
        assertTrue(lines.single().endsWith("guard=none"))
    }

    @Test
    fun lockPageCountsAsTheFirstUsablePageAndPendingGuardIsFlushed() {
        now = 1_600L; timeline.mark(StartupTimeline.Mark.LOCK_SHOWN)
        assertTrue(lines.isEmpty())
        timeline.flushIfPending()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("lock_shown=600ms"))
        assertTrue(lines.single().endsWith("guard=pending"))
    }

    @Test
    fun quickDescribeMatchesTheFullScanAndRejectsOutsideNames() {
        val context = RuntimeEnvironment.getApplication()
        val directory = WatchmakerWatchFaceStorage.rootDirectory(context)
            .resolve("watch_quick_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            assertTrue(directory.mkdirs())
            directory.resolve("watch.xml").writeText("<Watch name=\"Quick\"><Layer type=\"text\" /></Watch>")
            WatchInstallMetadata.readOrCreate(directory, 1234L)
            val id = "watchmaker:${directory.name}"
            val quick = WatchmakerWatchFaceStorage.describeInstalled(context, id)
            val scanned = WatchmakerWatchFaceStorage.scan(context).single { it.id == id }
            assertEquals(scanned, quick)

            assertNull(WatchmakerWatchFaceStorage.describeInstalled(context, "watchmaker:../${directory.name}"))
            assertNull(WatchmakerWatchFaceStorage.describeInstalled(context, "watchmaker:.install-x"))
            assertNull(WatchmakerWatchFaceStorage.describeInstalled(context, "watchmaker:missing_face"))
            assertNull(WatchmakerWatchFaceStorage.describeInstalled(context, "jbwatch:${directory.name}"))
            assertNull(JbWatchFaceStorage.describeInstalled(context, "jbwatch:.."))
            assertNull(JbWatchFaceStorage.describeInstalled(context, "jbwatch:a/b"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun quickDescribeOfJbFaceMatchesTheFullScan() {
        val context = RuntimeEnvironment.getApplication()
        val dir = JbWatchFaceStorage.rootDir(context).resolve("jb_quick_${UUID.randomUUID().toString().take(8)}")
        try {
            assertTrue(dir.mkdirs())
            dir.resolve("watch.xml").writeText("<Watch name=\"Jb\" features=\"x\" />")
            dir.resolve("watch.pxml").writeText("<Watch />")
            val id = "jbwatch:${dir.name}"
            assertEquals(JbWatchFaceStorage.scan(context).single { it.id == id }, JbWatchFaceStorage.describeInstalled(context, id))
        } finally {
            dir.deleteRecursively()
        }
    }
}
