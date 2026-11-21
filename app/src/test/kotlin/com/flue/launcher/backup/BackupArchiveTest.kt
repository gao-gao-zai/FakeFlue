package com.flue.launcher.backup

import com.flue.launcher.watchface.standard.WatchArchiveLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R37：备份包解包的越界 / 炸弹 / 重复条目 / 版本防护。Robolectric 只为提供真实 org.json。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupArchiveTest {
    private val root: File = Files.createTempDirectory("flue-backup-archive-test").toFile()
    private val target = File(root, "extract").apply { mkdirs() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun ByteArray.replaceAscii(from: String, to: String): ByteArray {
        require(from.length == to.length)
        val pattern = from.toByteArray(Charsets.US_ASCII)
        val replacement = to.toByteArray(Charsets.US_ASCII)
        val out = copyOf()
        var index = 0
        while (index <= out.size - pattern.size) {
            if ((pattern.indices).all { out[index + it] == pattern[it] }) {
                replacement.copyInto(out, index)
                index += pattern.size
            } else {
                index += 1
            }
        }
        return out
    }

    private fun manifest(schema: Int = 1) = """{"schemaVersion":$schema,"appVersion":"t","settings":{}}""".toByteArray()

    private fun extract(bytes: ByteArray, limits: BackupArchiveLimits = BackupArchiveLimits()) =
        extractBackupArchive(ByteArrayInputStream(bytes), target, limits)

    private fun reasonOf(block: () -> Unit): FlueBackupFormatException.Reason =
        assertThrows(FlueBackupFormatException::class.java) { block() }.reason

    @Test
    fun `valid backup extracts and returns its manifest`() {
        val manifest = extract(zip("manifest.json" to manifest(), "watchfaces/photo/media.png" to byteArrayOf(1, 2)))
        assertEquals("t", manifest.getString("appVersion"))
        assertTrue(File(target, "watchfaces/photo/media.png").isFile)
    }

    @Test
    fun `zip slip entry is rejected and nothing is written outside`() {
        val reason = reasonOf { extract(zip("manifest.json" to manifest(), "../evil.txt" to byteArrayOf(1))) }
        assertEquals(FlueBackupFormatException.Reason.PATH_OUTSIDE, reason)
        assertFalse(File(root, "evil.txt").exists())
    }

    @Test
    fun `entry larger than the actual byte limit is rejected`() {
        // 1MB 零字节压缩后很小，按实际展开字节计数才能拦住。
        val bomb = zip("manifest.json" to manifest(), "big.bin" to ByteArray(1024 * 1024))
        assertTrue(bomb.size < 64 * 1024)
        val reason = reasonOf { extract(bomb, BackupArchiveLimits(maxEntryBytes = 64L * 1024L)) }
        assertEquals(FlueBackupFormatException.Reason.TOO_LARGE, reason)
    }

    @Test
    fun `total expanded bytes are capped across entries`() {
        val archive = zip(
            "manifest.json" to manifest(),
            "a.bin" to ByteArray(40 * 1024),
            "b.bin" to ByteArray(40 * 1024),
        )
        val reason = reasonOf {
            extract(archive, BackupArchiveLimits(maxEntryBytes = 64L * 1024L, maxTotalBytes = 64L * 1024L))
        }
        assertEquals(FlueBackupFormatException.Reason.TOO_LARGE, reason)
    }

    @Test
    fun `entry count is capped`() {
        val entries = arrayOf("manifest.json" to manifest()) + Array(20) { "f$it.txt" to byteArrayOf(1) }
        val reason = reasonOf { extract(zip(*entries), BackupArchiveLimits(maxEntries = 10)) }
        assertEquals(FlueBackupFormatException.Reason.TOO_MANY_ENTRIES, reason)
    }

    @Test
    fun `duplicate manifest entries are rejected`() {
        // 预览读到第一份、旧解包逻辑保留最后一份：两者不一致，必须拒绝。
        // ZipOutputStream 不允许写重名条目：先用等长的占位名写，再把字节里的名字改回来。
        val archive = zip(
            "manifest.json" to manifest(),
            "manifest.jsoX" to """{"schemaVersion":1,"settings":{"evil":{"type":"string","value":"x"}}}""".toByteArray(),
        ).replaceAscii("manifest.jsoX", "manifest.json")
        assertEquals(FlueBackupFormatException.Reason.DUPLICATE_ENTRY, reasonOf { extract(archive) })
    }

    @Test
    fun `backslash duplicate of the manifest is also rejected`() {
        val archive = zip("manifest.json" to manifest(), "\\manifest.json" to manifest())
        assertEquals(FlueBackupFormatException.Reason.DUPLICATE_ENTRY, reasonOf { extract(archive) })
    }

    @Test
    fun `newer schema version is refused by preview and import`() {
        val archive = zip("manifest.json" to manifest(schema = BACKUP_SCHEMA_VERSION + 1))
        val previewError = assertThrows(FlueBackupFormatException::class.java) {
            readBackupManifest(ByteArrayInputStream(archive))
        }
        assertEquals(FlueBackupFormatException.Reason.NEWER_SCHEMA, previewError.reason)
        assertEquals(BACKUP_SCHEMA_VERSION + 1, previewError.foundSchemaVersion)
        assertEquals(FlueBackupFormatException.Reason.NEWER_SCHEMA, reasonOf { extract(archive) })
    }

    @Test
    fun `manifest without schema version is treated as version 1`() {
        val archive = zip("manifest.json" to """{"appVersion":"old"}""".toByteArray())
        assertEquals("old", readBackupManifest(ByteArrayInputStream(archive)).getString("appVersion"))
    }

    @Test
    fun `oversized manifest is not read into memory`() {
        val archive = zip("manifest.json" to ByteArray(64 * 1024) { ' '.code.toByte() })
        val reason = reasonOf {
            readBackupManifest(ByteArrayInputStream(archive), BackupArchiveLimits(maxManifestBytes = 1024L))
        }
        assertEquals(FlueBackupFormatException.Reason.TOO_LARGE, reason)
    }

    @Test
    fun `archive without manifest is not a backup`() {
        assertEquals(
            FlueBackupFormatException.Reason.NOT_A_BACKUP,
            reasonOf { extract(zip("other.txt" to byteArrayOf(1))) },
        )
    }

    @Test
    fun `manifest references cannot escape the extraction root`() {
        File(root, "secret.txt").writeText("secret")
        File(target, "ok.bin").writeText("ok")
        assertNull(resolveBackupEntry(target, "../secret.txt"))
        assertNull(resolveBackupEntry(target, "null"))
        assertNull(resolveBackupEntry(target, ""))
        assertNull(resolveBackupEntry(target, "missing.bin"))
        assertEquals(File(target, "ok.bin").canonicalFile, resolveBackupEntry(target, "ok.bin"))
    }

    @Test
    fun `nested watch archive is re-inflated with a byte limit`() {
        val nested = File(root, "nested.watch")
        nested.writeBytes(zip("watch.xml" to "<Watch/>".toByteArray(), "big.bin" to ByteArray(256 * 1024)))
        val limits = WatchArchiveLimits(maximumExpandedBytes = 64L * 1024L)
        val reason = reasonOf { sanitizeBackupWatchArchive(nested, File(root, "out.watch"), limits) }
        assertEquals(FlueBackupFormatException.Reason.TOO_LARGE, reason)
    }
}
