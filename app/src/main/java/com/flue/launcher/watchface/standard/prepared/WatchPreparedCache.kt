package com.flue.launcher.watchface.standard.prepared

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R36：Watchmaker 表盘静态准备结果的私有磁盘缓存。
 *
 * - 键：安装身份（规范化后的安装目录）+ 格式版本 + 引擎身份；
 * - 命中：先比文件清单（相对路径、大小、时间），再比内容摘要
 *   （[watchPreparedValidationDigest]，覆盖 XML、脚本、SVG、位图字体定义全文）；
 * - 写入：后台单线程、临时文件 + fsync + 原子改名；单项上限 [maxEntryBytes]，总量上限
 *   [maxTotalBytes]，按最近使用（文件时间）淘汰；
 * - 修订顺序：每次扫描都登记「本键最新看到的摘要」，写入时摘要已不是最新就放弃，
 *   旧加载任务永远覆盖不了新修订；[invalidate] 之后旧任务同样作废。
 *
 * 任何读写失败都只意味着「这次没有缓存」，调用方继续走原加载路径。
 */
internal class WatchPreparedCache(
    private val directory: File,
    private val engineIdentity: String,
    private val writer: Executor = newDefaultWriter(),
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** 诊断/测试用计数：命中、未命中、成功发布的写入。 */
    internal val hitCount = AtomicInteger()
    internal val missCount = AtomicInteger()
    internal val publishCount = AtomicInteger()

    internal val cacheDirectory: File get() = directory

    /** installKey → 最近一次扫描到的校验摘要；[INVALIDATED] 表示已失效、尚无新扫描。 */
    private val latestValidation = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_TRACKED_KEYS
    }

    /**
     * 读取命中的静态准备结果；不命中、条目缺失/损坏/截断/不兼容一律返回 null。
     * 调用本方法同时登记 [validation] 为该键的最新修订。
     */
    fun load(
        installKey: String,
        manifest: List<WatchPreparedManifestEntry>,
        validation: ByteArray,
    ): WatchPreparedStatic? {
        synchronized(lock) { latestValidation[installKey] = validation.copyOf() }
        return loadEntry(installKey, manifest, validation)
            .also { if (it != null) hitCount.incrementAndGet() else missCount.incrementAndGet() }
    }

    private fun loadEntry(
        installKey: String,
        manifest: List<WatchPreparedManifestEntry>,
        validation: ByteArray,
    ): WatchPreparedStatic? {
        val file = entryFile(installKey)
        val length = file.length()
        val modified = file.lastModified()
        if (!file.isFile) return null
        if (length <= 0L || length > maxEntryBytes) {
            deleteIfUnchanged(file, length, modified)
            return null
        }
        val bytes = runCatching { readFully(file, length.toInt()) }.getOrNull() ?: return null
        val header = try {
            WatchPreparedCodec.decodeHeader(bytes)
        } catch (_: Exception) {
            deleteIfUnchanged(file, length, modified)
            return null
        }
        if (
            header.engineIdentity != engineIdentity ||
            header.installKey != installKey
        ) {
            deleteIfUnchanged(file, length, modified)
            return null
        }
        // 清单或摘要不同：只是旧修订，后台写入会原子替换它，这里不抢着删。
        if (header.manifest != manifest || !MessageDigest.isEqual(header.validation, validation)) return null
        val value = try {
            WatchPreparedCodec.decodePayload(bytes, header)
        } catch (_: Exception) {
            deleteIfUnchanged(file, length, modified)
            return null
        } catch (_: StackOverflowError) {
            deleteIfUnchanged(file, length, modified)
            return null
        }
        // 最近使用：淘汰按文件时间从旧到新。
        runCatching { file.setLastModified(clock()) }
        return value
    }

    /**
     * 后台写入。[build] 在写线程上执行，返回 null 表示这次不缓存。
     * 摘要已被更新的扫描或 [invalidate] 取代时放弃写入。
     */
    fun storeAsync(
        installKey: String,
        manifest: List<WatchPreparedManifestEntry>,
        validation: ByteArray,
        build: () -> WatchPreparedStatic?,
    ) {
        val expected = validation.copyOf()
        val manifestCopy = manifest.toList()
        try {
            writer.execute { store(installKey, manifestCopy, expected, build) }
        } catch (_: RejectedExecutionException) {
            // 写队列已满：本次不缓存，下次加载再试。
        }
    }

    /** 导入、保存、替换、删除之后调用：删掉条目，并让进行中的旧写入作废。 */
    fun invalidate(installKey: String) {
        synchronized(lock) {
            latestValidation[installKey] = INVALIDATED
            runCatching { entryFile(installKey).delete() }
        }
    }

    internal fun entryFile(installKey: String): File =
        File(directory, sha256Hex(installKey) + ENTRY_SUFFIX)

    private fun isLatest(installKey: String, validation: ByteArray): Boolean {
        val latest = latestValidation[installKey] ?: return false
        return latest !== INVALIDATED && MessageDigest.isEqual(latest, validation)
    }

    private fun store(
        installKey: String,
        manifest: List<WatchPreparedManifestEntry>,
        validation: ByteArray,
        build: () -> WatchPreparedStatic?,
    ) {
        if (!synchronized(lock) { isLatest(installKey, validation) }) return
        val encoded = try {
            val value = build() ?: return
            WatchPreparedCodec.encode(engineIdentity, installKey, validation, manifest, value)
        } catch (_: Exception) {
            return
        } catch (_: StackOverflowError) {
            return
        } catch (_: OutOfMemoryError) {
            return
        }
        // 超过单项上限：不缓存，表盘照常走原加载路径。
        if (encoded.size > maxEntryBytes) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val target = entryFile(installKey)
        val temporary = File(directory, "${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            val published = synchronized(lock) {
                // 写盘期间出现了更新的修订或失效：丢弃这份旧结果。
                isLatest(installKey, validation) && temporary.renameTo(target)
            }
            if (!published) return
            publishCount.incrementAndGet()
            runCatching { target.setLastModified(clock()) }
        } catch (_: Exception) {
            return
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        evict(keep = target)
    }

    private fun evict(keep: File) {
        val files = directory.listFiles().orEmpty()
        val now = clock()
        files.filter { it.name.endsWith(TEMP_SUFFIX) && now - it.lastModified() > STALE_TEMP_MS }
            .forEach { runCatching { it.delete() } }
        val entries = files.filter { it.isFile && it.name.endsWith(ENTRY_SUFFIX) }
            .map { Triple(it, it.length(), it.lastModified()) }
            .sortedBy { it.third }
        var total = entries.sumOf { it.second }
        for ((file, length, _) in entries) {
            if (total <= maxTotalBytes) break
            if (file == keep) continue
            synchronized(lock) {
                if (file.delete()) total -= length
            }
        }
    }

    private fun deleteIfUnchanged(file: File, length: Long, modified: Long) {
        synchronized(lock) {
            if (file.length() == length && file.lastModified() == modified) runCatching { file.delete() }
        }
    }

    private fun readFully(file: File, length: Int): ByteArray {
        val bytes = ByteArray(length)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw java.io.EOFException("prepared cache entry truncated")
                offset += count
            }
            // 读的过程中文件被替换变长：本次不用。
            if (input.read() >= 0) throw java.io.IOException("prepared cache entry changed")
        }
        return bytes
    }

    companion object {
        const val MAX_ENTRY_BYTES: Long = 8L * 1024L * 1024L
        const val MAX_TOTAL_BYTES: Long = 32L * 1024L * 1024L
        private const val MAX_TRACKED_KEYS = 512
        private const val ENTRY_SUFFIX = ".prepared"
        private const val TEMP_SUFFIX = ".tmp"
        private const val STALE_TEMP_MS = 10L * 60L * 1000L
        private val INVALIDATED = ByteArray(0)

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

        private fun newDefaultWriter(): Executor = ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(4),
            { runnable ->
                Thread(runnable, "watch-prepared-cache").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
