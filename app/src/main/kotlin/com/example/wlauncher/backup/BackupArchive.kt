package com.flue.launcher.backup

import com.flue.launcher.data.SizeLimitExceededException
import com.flue.launcher.data.copyToBounded
import com.flue.launcher.data.readBytesBounded
import com.flue.launcher.watchface.standard.WatchArchiveLimits
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** 当前写出的备份格式版本；导入只接受不高于它的版本。 */
internal const val BACKUP_SCHEMA_VERSION = 1

internal const val BACKUP_MANIFEST_ENTRY = "manifest.json"

/**
 * 备份包解包上限（R37：限制实际解包字节和条目）。全部按实际流过的字节计数。
 * 单条目上限要容下一个 500MB 的内嵌 .watch 或视频表盘源文件。
 */
internal data class BackupArchiveLimits(
    val maxEntries: Int = 4_096,
    val maxEntryNameLength: Int = 512,
    val maxEntryBytes: Long = 600L * 1024L * 1024L,
    val maxTotalBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxManifestBytes: Long = 16L * 1024L * 1024L,
    /** 解包过程中目标分区至少保留的可用空间，避免把 /data 写满。 */
    val minFreeBytes: Long = 64L * 1024L * 1024L,
)

/** 备份包本身不可用。[reason] 供 UI 映射成本地化文案。 */
internal class FlueBackupFormatException(
    val reason: Reason,
    message: String,
    val foundSchemaVersion: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        NOT_A_BACKUP,
        NEWER_SCHEMA,
        TOO_LARGE,
        TOO_MANY_ENTRIES,
        PATH_OUTSIDE,
        DUPLICATE_ENTRY,
        INSUFFICIENT_SPACE,
    }
}

/** 规范化条目名：反斜杠当分隔符，去掉开头的 `/`。 */
internal fun normalizeBackupEntryName(raw: String): String = raw.replace('\\', '/').trimStart('/')

/**
 * 只读出 manifest.json（预览用），此前跳过的条目也按实际展开字节限流。不检查重复条目——预览不扫完整个包，
 * 重复条目由 [extractBackupArchive] 在真正导入时拒绝，所以预览看到的就是会被导入的那份。
 */
internal fun readBackupManifest(input: InputStream, limits: BackupArchiveLimits = BackupArchiveLimits()): JSONObject {
    ZipInputStream(input.buffered()).use { zip ->
        var count = 0
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            count += 1
            if (count > limits.maxEntries) {
                throw FlueBackupFormatException(
                    FlueBackupFormatException.Reason.TOO_MANY_ENTRIES,
                    "备份条目过多（上限 ${limits.maxEntries}）",
                )
            }
            val entryLimit = minOf(limits.maxEntryBytes, limits.maxTotalBytes - total)
            if (!entry.isDirectory && normalizeBackupEntryName(entry.name) == BACKUP_MANIFEST_ENTRY) {
                return parseBackupManifest(zip.readBoundedManifest(minOf(limits.maxManifestBytes, entryLimit)))
            }
            // closeEntry 会解压剩余内容，先显式限流读取才能约束被跳过的条目。
            total += zip.copyBackupEntryTo(discardedEntryOutput, entryLimit, "备份预览条目")
            zip.closeEntry()
        }
    }
    throw FlueBackupFormatException(FlueBackupFormatException.Reason.NOT_A_BACKUP, "不是有效的 Flue 备份")
}

internal fun parseBackupManifest(bytes: ByteArray): JSONObject {
    val manifest = try {
        JSONObject(bytes.toString(Charsets.UTF_8))
    } catch (error: Exception) {
        throw FlueBackupFormatException(
            FlueBackupFormatException.Reason.NOT_A_BACKUP,
            "备份清单无法解析",
            cause = error,
        )
    }
    checkBackupSchema(manifest)
    return manifest
}

/** 拒绝更新版本写出的备份：旧版本按自己的理解去读，只会静默丢数据或写坏配置。 */
internal fun checkBackupSchema(manifest: JSONObject) {
    val version = manifest.optInt("schemaVersion", 1)
    if (version > BACKUP_SCHEMA_VERSION) {
        throw FlueBackupFormatException(
            FlueBackupFormatException.Reason.NEWER_SCHEMA,
            "备份格式版本 $version 高于当前支持的 $BACKUP_SCHEMA_VERSION，请先升级应用",
            foundSchemaVersion = version,
        )
    }
}

private fun ZipInputStream.readBoundedManifest(limitBytes: Long): ByteArray = try {
    readBytesBounded(limitBytes, "备份清单")
} catch (error: SizeLimitExceededException) {
    throw FlueBackupFormatException(FlueBackupFormatException.Reason.TOO_LARGE, error.message.orEmpty(), cause = error)
}

private val discardedEntryOutput = object : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
}

private fun InputStream.copyBackupEntryTo(output: OutputStream, limitBytes: Long, what: String): Long = try {
    copyToBounded(output, limitBytes, what)
} catch (error: SizeLimitExceededException) {
    throw FlueBackupFormatException(FlueBackupFormatException.Reason.TOO_LARGE, error.message.orEmpty(), cause = error)
}

/**
 * 把整个备份包解到 [targetRoot]，返回其中的 manifest。
 *
 * - 条目路径必须落在 [targetRoot] 内（拒绝 `../`）；
 * - 条目名不得重复（尤其 manifest：预览读第一份、旧解包逻辑保留最后一份，两者会不一致）；
 * - 条目数、单条目实际字节、总实际字节都有上限，并保留最低可用空间。
 */
internal fun extractBackupArchive(
    input: InputStream,
    targetRoot: File,
    limits: BackupArchiveLimits = BackupArchiveLimits(),
): JSONObject {
    val canonicalRoot = targetRoot.canonicalFile
    val seen = HashSet<String>()
    var count = 0
    var total = 0L
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            count += 1
            if (count > limits.maxEntries) {
                throw FlueBackupFormatException(
                    FlueBackupFormatException.Reason.TOO_MANY_ENTRIES,
                    "备份条目过多（上限 ${limits.maxEntries}）",
                )
            }
            val name = normalizeBackupEntryName(entry.name)
            if (name.length > limits.maxEntryNameLength) {
                throw FlueBackupFormatException(FlueBackupFormatException.Reason.PATH_OUTSIDE, "备份条目名过长")
            }
            val target = File(canonicalRoot, name).canonicalFile
            val insideRoot = target.path == canonicalRoot.path ||
                target.path.startsWith(canonicalRoot.path + File.separator)
            if (!insideRoot) {
                throw FlueBackupFormatException(
                    FlueBackupFormatException.Reason.PATH_OUTSIDE,
                    "备份路径越界: ${entry.name.take(128)}",
                )
            }
            if (entry.isDirectory) {
                target.mkdirs()
                total += zip.copyBackupEntryTo(
                    discardedEntryOutput,
                    minOf(limits.maxEntryBytes, limits.maxTotalBytes - total),
                    "备份目录条目",
                )
                zip.closeEntry()
                continue
            }
            if (!seen.add(target.path)) {
                throw FlueBackupFormatException(
                    FlueBackupFormatException.Reason.DUPLICATE_ENTRY,
                    "备份包含重复条目: ${name.take(128)}",
                )
            }
            if (canonicalRoot.usableSpace in 1 until limits.minFreeBytes) {
                throw FlueBackupFormatException(
                    FlueBackupFormatException.Reason.INSUFFICIENT_SPACE,
                    "存储空间不足，无法解开备份",
                )
            }
            target.parentFile?.mkdirs()
            val remaining = limits.maxTotalBytes - total
            val entryLimit = minOf(limits.maxEntryBytes, remaining)
            val written = try {
                FileOutputStream(target).use { output -> zip.copyToBounded(output, entryLimit, "备份条目 ${name.take(64)}") }
            } catch (error: SizeLimitExceededException) {
                throw FlueBackupFormatException(
                    FlueBackupFormatException.Reason.TOO_LARGE,
                    if (entryLimit < limits.maxEntryBytes) "备份解包总量超过上限" else error.message.orEmpty(),
                    cause = error,
                )
            }
            total += written
            zip.closeEntry()
        }
    }
    val manifestFile = File(canonicalRoot, BACKUP_MANIFEST_ENTRY)
    if (!manifestFile.isFile) {
        throw FlueBackupFormatException(FlueBackupFormatException.Reason.NOT_A_BACKUP, "不是有效的 Flue 备份")
    }
    if (manifestFile.length() > limits.maxManifestBytes) {
        throw FlueBackupFormatException(FlueBackupFormatException.Reason.TOO_LARGE, "备份清单过大")
    }
    return parseBackupManifest(manifestFile.readBytes())
}

/**
 * manifest 里引用的条目（媒体、内嵌 .watch 等）解析成解包目录里的文件。
 * 空值、`null`、越界路径（`../`、绝对路径逃逸）或不存在的文件都返回 null。
 */
internal fun resolveBackupEntry(root: File, entry: String?): File? {
    if (entry.isNullOrBlank() || entry == "null") return null
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(canonicalRoot, normalizeBackupEntryName(entry)).canonicalFile }.getOrNull() ?: return null
    if (!file.path.startsWith(canonicalRoot.path + File.separator)) return null
    return file.takeIf { it.isFile }
}

/**
 * 去掉内嵌 .watch 里的 `.flue/` 安装元数据后重新打包。内层同样按实际字节与条目数限流
 * （沿用 .watch 导入的上限），并拒绝重复条目。
 */
internal fun sanitizeBackupWatchArchive(
    source: File,
    target: File,
    limits: WatchArchiveLimits = WatchArchiveLimits(),
) {
    val seen = HashSet<String>()
    var count = 0
    var total = 0L
    ZipInputStream(source.inputStream().buffered()).use { input ->
        ZipOutputStream(target.outputStream().buffered()).use { output ->
            while (true) {
                val entry = input.nextEntry ?: break
                count += 1
                if (count > limits.maximumEntryCount) {
                    throw FlueBackupFormatException(
                        FlueBackupFormatException.Reason.TOO_MANY_ENTRIES,
                        "表盘条目过多（上限 ${limits.maximumEntryCount}）",
                    )
                }
                val omitted = isFlueInstallMetadataPath(entry.name)
                if (!omitted) {
                    if (!seen.add(entry.name)) {
                        throw FlueBackupFormatException(
                            FlueBackupFormatException.Reason.DUPLICATE_ENTRY,
                            "表盘包含重复条目: ${entry.name.take(128)}",
                        )
                    }
                    output.putNextEntry(ZipEntry(entry.name))
                }
                val discarded = omitted || entry.isDirectory
                val destination = if (discarded) discardedEntryOutput else output
                val entryLimit = if (discarded) limits.maximumOtherFileBytes else limits.maximumExpandedBytes
                total += input.copyBackupEntryTo(
                    destination,
                    minOf(entryLimit, limits.maximumExpandedBytes - total),
                    "表盘展开总量",
                )
                if (!omitted) output.closeEntry()
                input.closeEntry()
            }
        }
    }
}

internal fun isFlueInstallMetadataPath(path: String): Boolean =
    path.replace('\\', '/').trimStart('/').substringBefore('/').equals(".flue", ignoreCase = true)
