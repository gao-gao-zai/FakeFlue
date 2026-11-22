package com.flue.launcher.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R37：媒体替换「先写新、成功后删旧」与按实际字节限流。纯 JVM。 */
class StagedFilesTest {
    private val dir: File = Files.createTempDirectory("flue-staged-test").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `failed validation keeps the old file and leaves no new file`() {
        val old = File(dir, "current_1.ttf").apply { writeText("old font") }

        assertThrows(IllegalStateException::class.java) {
            StagedFiles.writeNew(dir, "current", "ttf", write = { it.write("broken".toByteArray()) }) {
                error("not a font")
            }
        }

        assertEquals(listOf(old.name), dir.list()!!.toList())
        assertEquals("old font", old.readText())
    }

    @Test
    fun `failed write keeps the old file`() {
        val old = File(dir, "current_1.mp4").apply { writeText("old video") }

        assertThrows(SizeLimitExceededException::class.java) {
            StagedFiles.writeNew(dir, "current", "mp4", write = { output ->
                ByteArrayInputStream(ByteArray(2048)).copyToBounded(output, 1024, "视频文件")
            })
        }

        assertEquals(listOf(old.name), dir.list()!!.toList())
    }

    @Test
    fun `successful write then prune replaces the old file`() {
        val old = File(dir, "current_1.png").apply { writeText("old") }

        val created = StagedFiles.writeNew(dir, "current", "png", write = { it.write("new".toByteArray()) })
        assertTrue("提交前旧文件必须还在", old.isFile)
        StagedFiles.pruneExcept(dir, listOf(created))

        assertFalse(old.exists())
        assertEquals(listOf(created.name), dir.list()!!.toList())
        assertEquals("new", created.readText())
        assertTrue(created.name.startsWith("current_") && created.name.endsWith(".png"))
    }

    @Test
    fun `bounded copy counts actual bytes`() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, ByteArrayInputStream(ByteArray(10) { it.toByte() }).copyToBounded(out, 10, "x"))
        assertArrayEquals(ByteArray(10) { it.toByte() }, out.toByteArray())
        assertThrows(IOException::class.java) {
            ByteArrayInputStream(ByteArray(11)).readBytesBounded(10, "x")
        }
    }
}
