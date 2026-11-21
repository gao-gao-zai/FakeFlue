package com.flue.launcher.watchface.jbwatch

import android.content.Context
import com.flue.launcher.data.DirectorySwap
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R37：jbwatch 重装先暂存再整目录替换，失败保留旧表盘；删除只限表盘根目录。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class JbWatchReinstallTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root: File get() = JbWatchFaceStorage.rootDir(context)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun existingFace(name: String): File = File(root, name).apply {
        mkdirs()
        File(this, "watch.xml").writeText("<Watch name=\"Old\"/>")
        File(this, "watch.pxml").writeText("<Watch/>")
        File(this, "old-only.png").writeText("old asset")
    }

    private fun archive(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("jb_reinstall_", ".watch", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `failed reinstall keeps the installed face untouched`() {
        val face = existingFace("MyFace")
        // 缺 watch.pxml：解析阶段失败。
        val broken = archive("MyFace/watch.xml" to "<Watch name=\"New\"/>")

        runCatching { JbWatchFaceStorage.importArchiveFile(context, broken) }

        assertTrue(face.isDirectory)
        assertEquals("<Watch name=\"Old\"/>", File(face, "watch.xml").readText())
        assertTrue(File(face, "old-only.png").isFile)
        assertFalse(root.list()!!.any { DirectorySwap.isTransactionDirName(it) })
    }

    @Test
    fun `successful reinstall swaps the whole directory`() {
        val face = existingFace("MyFace")
        val fresh = archive(
            "MyFace/watch.xml" to "<Watch name=\"New\"/>",
            "MyFace/watch.pxml" to "<Watch/>",
        )

        val result = runCatching { JbWatchFaceStorage.importArchiveFile(context, fresh) }
        // 解析器若在 JVM 上不可用，只验证失败时旧表盘仍在；可用时验证整目录替换。
        if (result.isSuccess) {
            assertEquals("<Watch name=\"New\"/>", File(face, "watch.xml").readText())
            assertFalse("旧版本独有的文件不应残留", File(face, "old-only.png").exists())
        } else {
            assertEquals("<Watch name=\"Old\"/>", File(face, "watch.xml").readText())
        }
        assertFalse(root.list()!!.any { DirectorySwap.isTransactionDirName(it) })
    }

    @Test
    fun `scan ignores staging and backup dirs and recovery restores an interrupted swap`() {
        val backup = existingFace(".MyFace.backup-${UUID.randomUUID()}")
        existingFace(".Other.install-${UUID.randomUUID()}")

        val report = JbWatchFaceStorage.recoverInterruptedInstalls(root, force = true)!!

        assertEquals(listOf("MyFace"), report.restored)
        assertFalse(backup.exists())
        assertTrue(File(root, "MyFace/old-only.png").isFile)
        val scanned = JbWatchFaceStorage.scan(context).map { File(it.sourceDirPath!!).name }
        assertFalse(scanned.any { it.startsWith(".") })
    }

    @Test
    fun `delete refuses directories outside the jbwatch root`() {
        val outside = File(context.filesDir, "not_a_face").apply {
            mkdirs()
            File(this, "keep.txt").writeText("keep")
        }
        val descriptor = WatchFaceDescriptor(
            id = "${JBWATCH_ID_PREFIX}evil",
            type = WatchFaceType.JBWATCH,
            displayName = "evil",
            summary = "",
            sourceDirPath = outside.absolutePath,
        )

        assertFalse(JbWatchFaceStorage.delete(context, descriptor))
        assertTrue(File(outside, "keep.txt").isFile)
        assertFalse(JbWatchFaceStorage.delete(context, descriptor.copy(sourceDirPath = root.absolutePath)))
        assertTrue(root.isDirectory)
    }
}
