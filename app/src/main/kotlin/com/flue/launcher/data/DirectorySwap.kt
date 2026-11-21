package com.flue.launcher.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 目录级「暂存 → 备份 → 换上 → 失败回滚」，以及进程被杀后的启动恢复（R37）。
 *
 * 命名与表盘编辑器 `saveCurrentRevision` 已有的事务一致：
 * - 备份：`.<目标名>.backup-<uuid>`
 * - 暂存：`.<目标名>.revision-<uuid>`（编辑器）、`.<目标名>.install-<uuid>`（本类）、
 *   `.install-<uuid>`（WatchArchiveInstaller）
 *
 * 点号开头，扫描表盘时一律跳过，不会被当成表盘显示。
 */
object DirectorySwap {
    /** 暂存目录在这个时长之后才视为崩溃遗留，避免误删进行中的安装。 */
    const val DEFAULT_STALE_STAGING_MS: Long = 10L * 60L * 1000L

    private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val backupPattern = Regex("^\\.(.+)\\.backup-$UUID_PATTERN$")
    private val stagingPattern = Regex("^(?:\\.install-$UUID_PATTERN|\\..+\\.(?:revision|install)-$UUID_PATTERN)$")

    data class RecoveryReport(
        /** 目标缺失、已从备份还原的目录名。 */
        val restored: List<String> = emptyList(),
        /** 已清理的过期暂存目录名。 */
        val removedStaging: List<String> = emptyList(),
        /** 目标已存在、原样保留的备份目录名（不自动删除，留给人工判断）。 */
        val keptBackups: List<String> = emptyList(),
        /** 还原失败的备份目录名。 */
        val failed: List<String> = emptyList(),
    )

    /** 备份或暂存目录（不是表盘本身）。 */
    fun isTransactionDirName(name: String): Boolean =
        backupPattern.matches(name) || stagingPattern.matches(name)

    fun stagingDirFor(target: File): File =
        File(target.parentFile, ".${target.name}.install-${UUID.randomUUID()}")

    fun backupDirFor(target: File): File =
        File(target.parentFile, ".${target.name}.backup-${UUID.randomUUID()}")

    /**
     * 用已经完整写好的 [staged] 替换 [target]。旧目录先改名成备份，新目录换上后才删除备份；
     * 换上失败则把备份改回原名。抛出时 [target] 保持原内容（或原本就不存在）。
     */
    fun replace(staged: File, target: File) = replace(staged, target, ::moveDirectory)

    /** [move] 仅供测试注入故障。 */
    internal fun replace(staged: File, target: File, move: (File, File) -> Unit) {
        require(staged.isDirectory) { "暂存目录不存在" }
        val backup = if (target.exists()) backupDirFor(target) else null
        if (backup != null) move(target, backup)
        try {
            move(staged, target)
        } catch (error: Throwable) {
            if (backup != null && !target.exists()) {
                runCatching { moveDirectory(backup, target) }
            }
            throw error
        }
        backup?.deleteRecursively()
    }

    /**
     * 扫描 [root] 的直接子目录：目标缺失的备份还原回去；过期的暂存目录删除。
     * 目标仍在的备份不动。只应在没有进行中的事务时调用（调用方负责：进程内首次扫描）。
     */
    fun recover(
        root: File,
        nowEpochMs: Long = System.currentTimeMillis(),
        staleStagingAfterMs: Long = DEFAULT_STALE_STAGING_MS,
    ): RecoveryReport {
        val children = root.listFiles()?.filter { it.isDirectory } ?: return RecoveryReport()
        val restored = ArrayList<String>()
        val kept = ArrayList<String>()
        val failed = ArrayList<String>()
        val removed = ArrayList<String>()
        children
            .mapNotNull { dir -> backupPattern.matchEntire(dir.name)?.let { dir to it.groupValues[1] } }
            // 同一目标有多份备份时取最新的一份还原，其余保留。
            .sortedByDescending { (dir, _) -> dir.lastModified() }
            .forEach { (backup, targetName) ->
                val target = File(root, targetName)
                if (target.exists() || targetName.startsWith(".") || targetName.contains('/')) {
                    kept += backup.name
                    return@forEach
                }
                try {
                    moveDirectory(backup, target)
                    restored += targetName
                } catch (_: Exception) {
                    failed += backup.name
                }
            }
        children
            .filter { stagingPattern.matches(it.name) && it.exists() }
            .filter { nowEpochMs - it.lastModified() > staleStagingAfterMs }
            .forEach { staging ->
                if (staging.deleteRecursively()) removed += staging.name
            }
        return RecoveryReport(restored, removed, kept, failed)
    }

    /** [candidate] 的规范路径是否位于 [root] 之内（不含 root 本身）。 */
    fun isStrictlyInside(root: File, candidate: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return canonical.path.startsWith(canonicalRoot.path + File.separator)
    }

    fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        } catch (error: java.nio.file.FileSystemException) {
            throw IOException("目录移动失败：${source.name} → ${target.name}", error)
        }
    }
}
