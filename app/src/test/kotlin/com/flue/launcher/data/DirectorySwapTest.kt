package com.flue.launcher.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R37：目录替换失败回滚，以及进程被杀后的启动恢复。纯 JVM。 */
class DirectorySwapTest {
    private val root: File = Files.createTempDirectory("flue-swap-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun face(name: String, content: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "watch.xml").writeText(content)
        }

    @Test
    fun `replace swaps in the new tree and leaves no transaction dirs`() {
        val target = face("face", "old")
        val staged = File(root, ".face.install-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(staged, "watch.xml").writeText("new")

        DirectorySwap.replace(staged, target)

        assertEquals("new", File(target, "watch.xml").readText())
        assertEquals(listOf("face"), root.list()!!.toList())
    }

    @Test
    fun `failed swap restores the original directory`() {
        val target = face("face", "old")
        val staged = File(root, ".face.install-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(staged, "watch.xml").writeText("new")
        var calls = 0

        assertThrows(IOException::class.java) {
            DirectorySwap.replace(staged, target) { source, destination ->
                calls += 1
                if (calls == 2) throw IOException("simulated failure")
                DirectorySwap.moveDirectory(source, destination)
            }
        }

        assertEquals("old", File(target, "watch.xml").readText())
        assertFalse(root.list()!!.any { DirectorySwap.isTransactionDirName(it) && it.contains(".backup-") })
    }

    @Test
    fun `recovery restores a backup whose target is missing`() {
        // 编辑器 saveCurrentRevision 在两次改名之间被杀：原表盘只剩备份。
        val backup = face(".watch_abc.backup-${java.util.UUID.randomUUID()}", "original")
        val revision = face(".watch_abc.revision-${java.util.UUID.randomUUID()}", "half")
        revision.setLastModified(System.currentTimeMillis() - 60L * 60L * 1000L)

        val report = DirectorySwap.recover(root)

        assertEquals(listOf("watch_abc"), report.restored)
        assertFalse(backup.exists())
        assertEquals("original", File(root, "watch_abc/watch.xml").readText())
        assertTrue(revision.name in report.removedStaging)
        assertFalse(revision.exists())
    }

    @Test
    fun `recovery keeps backups whose target exists and fresh staging dirs`() {
        face("watch_abc", "current")
        val backup = face(".watch_abc.backup-${java.util.UUID.randomUUID()}", "older")
        val freshInstall = File(root, ".install-${java.util.UUID.randomUUID()}").apply { mkdirs() }

        val report = DirectorySwap.recover(root, nowEpochMs = System.currentTimeMillis())

        assertTrue(report.restored.isEmpty())
        assertEquals(listOf(backup.name), report.keptBackups)
        assertTrue(backup.isDirectory)
        assertEquals("current", File(root, "watch_abc/watch.xml").readText())
        assertTrue("进行中的安装暂存目录不能被清掉", freshInstall.isDirectory)
    }

    @Test
    fun `recovery ignores ordinary faces and unrelated dot dirs`() {
        face("watch_1", "a")
        val unrelated = File(root, ".flue-cache").apply { mkdirs() }
        unrelated.setLastModified(0L)

        val report = DirectorySwap.recover(root)

        assertEquals(DirectorySwap.RecoveryReport(), report)
        assertTrue(unrelated.isDirectory)
        assertTrue(File(root, "watch_1").isDirectory)
    }
}
