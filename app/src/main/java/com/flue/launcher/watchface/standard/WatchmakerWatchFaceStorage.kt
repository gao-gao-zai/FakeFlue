package com.flue.launcher.watchface.standard

import android.content.Context
import android.net.Uri
import com.flue.launcher.data.DirectorySwap
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

const val WATCHMAKER_ID_PREFIX = "watchmaker:"

data class WatchmakerWatchFaceScanFailure(
    val directoryName: String,
    val message: String,
)

data class WatchmakerWatchFaceScanResult(
    val descriptors: List<WatchFaceDescriptor>,
    val failures: List<WatchmakerWatchFaceScanFailure>,
) {
    val isComplete: Boolean get() = failures.isEmpty()
}

object WatchmakerWatchFaceStorage {
    private const val ROOT_DIRECTORY = "watchmaker_faces"
    private const val MAXIMUM_IMPORT_BYTES = 500L * 1024L * 1024L
    private val recoveryDone = AtomicBoolean(false)

    fun rootDirectory(context: Context): File =
        File(context.filesDir, ROOT_DIRECTORY).apply {
            // mkdirs() may return false when another import/scan wins the
            // creation race. Re-check the filesystem before treating that as
            // an install failure; otherwise the first startup can fail even
            // though the directory is already usable.
            if (!exists()) mkdirs()
            if (!isDirectory) {
                throw WatchArchiveException(
                    WatchArchiveFailureReason.INSTALL_FAILED,
                    "无法创建 .watch 表盘目录",
                )
            }
        }

    fun importArchive(context: Context, uri: Uri): WatchFaceDescriptor {
        val temporary = File.createTempFile("watchmaker_import_", ".watch", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAXIMUM_IMPORT_BYTES) {
                            throw WatchArchiveException(
                                WatchArchiveFailureReason.ARCHIVE_TOO_LARGE,
                                ".watch 表盘包超过 500MB 限制",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw WatchArchiveException(
                WatchArchiveFailureReason.ARCHIVE_NOT_FOUND,
                "无法读取 .watch 表盘包",
            )
            return importArchiveFile(context, temporary)
        } finally {
            temporary.delete()
        }
    }

    fun importArchiveFile(
        context: Context,
        archive: File,
        forceNewInstall: Boolean = false,
    ): WatchFaceDescriptor {
        val installed = WatchArchiveInstaller().install(
            archive,
            rootDirectory(context),
            forceNewInstall = forceNewInstall,
        )
        return installed.toDescriptor()
    }

    fun scan(context: Context): List<WatchFaceDescriptor> =
        scanWithDiagnostics(context).descriptors

    fun scanWithDiagnostics(context: Context): WatchmakerWatchFaceScanResult {
        val metadataReader = WatchmakerMetadataReader(File(context.cacheDir, "watchmaker-metadata"))
        val installer = WatchArchiveInstaller()
        val ordered = ArrayList<WatchInstallOrder<WatchFaceDescriptor>>()
        val failures = ArrayList<WatchmakerWatchFaceScanFailure>()
        recoverInterruptedTransactions(rootDirectory(context))
        rootDirectory(context)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { directory ->
                try {
                    val (descriptor, installedAt) = describeDirectory(directory, metadataReader, installer)
                    ordered += WatchInstallOrder(
                        value = descriptor,
                        installedAtEpochMs = installedAt,
                        displayName = descriptor.displayName,
                        stableId = descriptor.id,
                    )
                } catch (error: Exception) {
                    failures += WatchmakerWatchFaceScanFailure(
                        directoryName = directory.name.take(128),
                        message = error.message
                            ?.trim()
                            ?.take(512)
                            ?.takeIf(String::isNotEmpty)
                            ?: error.javaClass.simpleName,
                    )
                }
            }
        return WatchmakerWatchFaceScanResult(
            descriptors = orderWatchInstalls(ordered),
            failures = failures,
        )
    }

    /**
     * R37：编辑器 `saveCurrentRevision` 在「原目录 → 备份」与「暂存 → 原目录」两次改名之间
     * 被杀时，表盘只剩 `.<id>.backup-*`，以前没有任何地方把它找回来。进程内第一次扫描时
     * （此时不会有进行中的编辑保存）把目标缺失的备份还原，并清掉超过 10 分钟的
     * `.install-*` / `.<id>.revision-*` 暂存目录。保存事务本身不变。
     */
    internal fun recoverInterruptedTransactions(
        root: File,
        force: Boolean = false,
    ): DirectorySwap.RecoveryReport? {
        if (!force && !recoveryDone.compareAndSet(false, true)) return null
        return runCatching { DirectorySwap.recover(root) }.getOrNull()
    }

    /**
     * R35：只读取一个已安装表盘，得到和 [scan] 完全相同的描述，让冷启动的当前表盘不必等全部表盘扫完。
     * 不是本存储里的普通安装目录（含点开头的临时目录、越界名称）时返回 null，调用方退回全量扫描。
     */
    fun describeInstalled(context: Context, id: String): WatchFaceDescriptor? {
        if (!id.startsWith(WATCHMAKER_ID_PREFIX)) return null
        val stableId = id.removePrefix(WATCHMAKER_ID_PREFIX)
        if (stableId.isEmpty() || stableId.startsWith(".") || stableId.contains('/') || stableId.contains('\\')) return null
        val root = rootDirectory(context).canonicalFile
        // 与全量扫描同一套中断恢复（进程内只跑一次）：表盘只剩备份目录时也能读到。
        recoverInterruptedTransactions(root)
        val directory = File(root, stableId)
        if (!directory.isDirectory || directory.canonicalFile.parentFile != root) return null
        val metadataReader = WatchmakerMetadataReader(File(context.cacheDir, "watchmaker-metadata"))
        return describeDirectory(directory, metadataReader, WatchArchiveInstaller()).first
    }

    private fun describeDirectory(
        directory: File,
        metadataReader: WatchmakerMetadataReader,
        installer: WatchArchiveInstaller,
    ): Pair<WatchFaceDescriptor, Long> {
        val legacyInstalledAt = directory.lastModified()
            .takeIf { it > 0L }
            ?: System.currentTimeMillis()
        // Persist before repair creates decoded files so migration
        // cannot move a legacy face to the top of the list.
        val installedAt = WatchInstallMetadata.readOrCreate(directory, legacyInstalledAt)
        val watchXml = installer.repairInstalledPackage(directory)
        val metadata = metadataReader.read(watchXml)
        val descriptor = metadata.toDescriptor(
            stableId = directory.name,
            rootDirectory = directory,
            preferredPreview = previewNextTo(watchXml),
        )
        return descriptor to installedAt
    }

    fun delete(context: Context, descriptor: WatchFaceDescriptor): Boolean {
        if (!descriptor.isWatchmaker) return false
        val root = rootDirectory(context).canonicalFile
        val source = descriptor.sourceDirPath?.let(::File)?.canonicalFile ?: return false
        val installDirectory = generateSequence(source) { it.parentFile }
            .firstOrNull { it.parentFile?.canonicalFile == root }
            ?: return false
        if (!installDirectory.exists() || installDirectory.parentFile?.canonicalFile != root) return false
        return installDirectory.deleteRecursively()
    }
}

private fun InstalledWatchArchive.toDescriptor(): WatchFaceDescriptor =
    WatchmakerMetadata.from(definition).toDescriptor(stableId, rootDirectory, preview)

private fun WatchmakerMetadata.toDescriptor(
    stableId: String,
    rootDirectory: File,
    preferredPreview: File? = null,
): WatchFaceDescriptor {
    val preview = preferredPreview ?: listOf("preview.jpg", "preview_dim.jpg")
        .asSequence()
        .map { File(rootDirectory, it) }
        .firstOrNull(File::isFile)
    val versionLabel = when (version) {
        WatchmakerDefinitionVersion.FORMAT_1 -> "Watchmaker 1.0"
        WatchmakerDefinitionVersion.FORMAT_2 -> "Watchmaker Studio 2.0"
    }
    return WatchFaceDescriptor(
        id = "$WATCHMAKER_ID_PREFIX$stableId",
        type = WatchFaceType.WATCHMAKER,
        displayName = name.ifBlank { stableId },
        summary = "$versionLabel · $layerCount 个图层",
        previewFilePath = preview?.absolutePath,
        sourceDirPath = rootDirectory.absolutePath,
        author = author,
        supportsSettings = true,
    )
}

private fun previewNextTo(watchXml: File): File? =
    listOf("preview.jpg", "preview_dim.jpg")
        .asSequence()
        .map { File(watchXml.parentFile, it) }
        .firstOrNull(File::isFile)
