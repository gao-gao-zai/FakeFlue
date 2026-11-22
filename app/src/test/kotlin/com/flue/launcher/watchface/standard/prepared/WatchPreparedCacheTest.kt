package com.flue.launcher.watchface.standard.prepared

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchPreparedCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val value = WatchPreparedCodecTest.sampleStatic()
    private val manifest = WatchPreparedCodecTest.MANIFEST
    private var now = 1_700_000_000_000L

    /** 手动泵：测试决定后台写入何时、按什么顺序执行。 */
    private class ManualExecutor : Executor {
        val queue = ArrayList<Runnable>()
        override fun execute(command: Runnable) {
            queue += command
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeAt(0).run()
        }
        fun runLastFirst() {
            while (queue.isNotEmpty()) queue.removeAt(queue.lastIndex).run()
        }
    }

    private fun cache(
        directory: File,
        executor: Executor = Executor { it.run() },
        engine: String = "engine-a",
        maxEntryBytes: Long = WatchPreparedCache.MAX_ENTRY_BYTES,
        maxTotalBytes: Long = WatchPreparedCache.MAX_TOTAL_BYTES,
    ) = WatchPreparedCache(directory, engine, executor, maxEntryBytes, maxTotalBytes, clock = { now })

    private fun validation(text: String): ByteArray =
        watchPreparedValidationDigest(text, listOf("fonts_bm/digits.fnt" to "info face=digits"))

    private fun storeNow(cache: WatchPreparedCache, key: String, digest: ByteArray) {
        assertNull(cache.load(key, manifest, digest))
        cache.storeAsync(key, manifest, digest) { value }
    }

    @Test
    fun entryWrittenByOneInstanceIsHitByAnotherInstance() {
        val directory = temporaryFolder.newFolder()
        val digest = validation("rev-1")
        storeNow(cache(directory), KEY, digest)
        val reopened = cache(directory)
        val hit = reopened.load(KEY, manifest, digest)
        assertNotNull(hit)
        assertEquals(value.definition, hit!!.definition)
        assertEquals(value.compiledExpressions, hit.compiledExpressions)
        assertArrayEquals(value.initialScriptChunk, hit.initialScriptChunk)
        assertEquals(1, reopened.hitCount.get())
        // 每次命中都是独立解码出来的新对象，不同加载之间不共享可变状态。
        val again = reopened.load(KEY, manifest, digest)!!
        assertFalse(hit.definition === again.definition)
    }

    @Test
    fun manifestOrContentChangesMiss() {
        val directory = temporaryFolder.newFolder()
        val cache = cache(directory)
        val digest = validation("rev-1")
        storeNow(cache, KEY, digest)
        // 清单（大小/时间）不同。
        val touched = manifest.map { it.copy(lastModifiedEpochMs = it.lastModifiedEpochMs + 1) }
        assertNull(cache.load(KEY, touched, digest))
        // 同大小、同时间，但内容不同：摘要不同即落空。
        assertNull(cache.load(KEY, manifest, validation("rev-2")))
        // 位图字体定义同大小改动也会改变摘要。
        val fontChanged = watchPreparedValidationDigest(
            "rev-1",
            listOf("fonts_bm/digits.fnt" to "info face=digitz"),
        )
        assertFalse(digest.contentEquals(fontChanged))
        assertNull(cache.load(KEY, manifest, fontChanged))
        // 另一个安装身份不会读到这一条。
        assertNull(cache.load("/other/face", manifest, digest))
        assertNotNull(cache.load(KEY, manifest, digest))
    }

    @Test
    fun incompatibleEngineCorruptAndTruncatedEntriesFallBackAndAreRemoved() {
        val directory = temporaryFolder.newFolder()
        val digest = validation("rev-1")
        val first = cache(directory)
        storeNow(first, KEY, digest)
        val file = first.entryFile(KEY)
        assertTrue(file.isFile)

        assertNull(cache(directory, engine = "engine-b").load(KEY, manifest, digest))
        assertFalse("incompatible entry is removed", file.exists())

        storeNow(first, KEY, digest)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(cache(directory).load(KEY, manifest, digest))
        assertFalse("truncated entry is removed", file.exists())

        storeNow(first, KEY, digest)
        val corrupted = file.readBytes().also { it[it.size / 3] = (it[it.size / 3].toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        assertNull(cache(directory).load(KEY, manifest, digest))
        assertFalse("corrupted entry is removed", file.exists())

        // 被系统清理掉整个目录：同样只是未命中，之后照常重建。
        storeNow(first, KEY, digest)
        assertTrue(directory.deleteRecursively())
        assertNull(first.load(KEY, manifest, digest))
        first.storeAsync(KEY, manifest, digest) { value }
        assertNotNull(cache(directory).load(KEY, manifest, digest))
    }

    @Test
    fun writeFailuresAndBuildFailuresLeaveNoEntry() {
        val blocker = temporaryFolder.newFile("not-a-directory")
        val unusable = cache(File(blocker, "child"))
        val digest = validation("rev-1")
        storeNow(unusable, KEY, digest)
        assertNull(unusable.load(KEY, manifest, digest))

        val directory = temporaryFolder.newFolder()
        val cache = cache(directory)
        assertNull(cache.load(KEY, manifest, digest))
        cache.storeAsync(KEY, manifest, digest) { error("build failed") }
        cache.storeAsync(KEY, manifest, digest) { null }
        assertFalse(cache.entryFile(KEY).exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun entriesOverThePerEntryLimitAreNotCached() {
        val directory = temporaryFolder.newFolder()
        val cache = cache(directory, maxEntryBytes = 256)
        val digest = validation("rev-1")
        storeNow(cache, KEY, digest)
        assertFalse(cache.entryFile(KEY).exists())
        assertNull(cache.load(KEY, manifest, digest))
    }

    @Test
    fun totalLimitEvictsTheLeastRecentlyUsedEntry() {
        val directory = temporaryFolder.newFolder()
        val probe = cache(temporaryFolder.newFolder())
        storeNow(probe, "probe", validation("p"))
        val entrySize = probe.entryFile("probe").length()
        val cache = cache(directory, maxTotalBytes = entrySize * 2 + entrySize / 2)
        val digest = validation("rev-1")

        storeNow(cache, "a", digest)
        now += 10_000
        storeNow(cache, "b", digest)
        now += 10_000
        assertNotNull(cache.load("a", manifest, digest)) // a 成为最近使用
        now += 10_000
        storeNow(cache, "c", digest)

        assertTrue(cache.entryFile("a").exists())
        assertFalse("least recently used entry is evicted", cache.entryFile("b").exists())
        assertTrue(cache.entryFile("c").exists())
        val total = directory.listFiles().orEmpty().sumOf { it.length() }
        assertTrue(total <= entrySize * 2 + entrySize / 2)
    }

    @Test
    fun olderLoadNeverOverwritesANewerRevision() {
        val directory = temporaryFolder.newFolder()
        val executor = ManualExecutor()
        val cache = cache(directory, executor)
        val old = validation("rev-old")
        val new = validation("rev-new")
        assertNull(cache.load(KEY, manifest, old))
        cache.storeAsync(KEY, manifest, old) { value }
        assertNull(cache.load(KEY, manifest, new))
        cache.storeAsync(KEY, manifest, new) { value }

        // 新修订先写完，旧任务后到：旧任务必须放弃。
        executor.runLastFirst()
        assertEquals(1, cache.publishCount.get())
        assertNotNull(cache.load(KEY, manifest, new))
        assertNull(cache.load(KEY, manifest, old))

        // 反过来按提交顺序执行：同样只留下新修订。
        val other = cache(temporaryFolder.newFolder(), executor)
        assertNull(other.load(KEY, manifest, old))
        other.storeAsync(KEY, manifest, old) { value }
        assertNull(other.load(KEY, manifest, new))
        other.storeAsync(KEY, manifest, new) { value }
        executor.runAll()
        assertEquals(1, other.publishCount.get())
        assertNotNull(other.load(KEY, manifest, new))
    }

    @Test
    fun concurrentRefreshesOfTheSameRevisionNeverTearTheEntry() {
        val directory = temporaryFolder.newFolder()
        val cache = cache(directory, executor = Executor { Thread(it).start() })
        val digest = validation("rev-1")
        assertNull(cache.load(KEY, manifest, digest))
        repeat(8) { cache.storeAsync(KEY, manifest, digest) { value } }
        val deadline = System.currentTimeMillis() + 10_000
        while (cache.publishCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(200)
        val hit = cache.load(KEY, manifest, digest)
        assertNotNull(hit)
        assertEquals(value.definition, hit!!.definition)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun invalidateRemovesTheEntryAndCancelsPendingOlderWrites() {
        val directory = temporaryFolder.newFolder()
        val executor = ManualExecutor()
        val cache = cache(directory, executor)
        val digest = validation("rev-1")
        assertNull(cache.load(KEY, manifest, digest))
        cache.storeAsync(KEY, manifest, digest) { value }
        executor.runAll()
        assertTrue(cache.entryFile(KEY).exists())

        cache.storeAsync(KEY, manifest, digest) { value }
        cache.invalidate(KEY)
        assertFalse(cache.entryFile(KEY).exists())
        executor.runAll()
        assertFalse("write queued before invalidate must not resurrect the entry", cache.entryFile(KEY).exists())

        // 失效后的新扫描照常建立条目。
        assertNull(cache.load(KEY, manifest, digest))
        cache.storeAsync(KEY, manifest, digest) { value }
        executor.runAll()
        assertNotNull(cache.load(KEY, manifest, digest))
    }

    private companion object {
        const val KEY = WatchPreparedCodecTest.INSTALL_KEY
    }
}
