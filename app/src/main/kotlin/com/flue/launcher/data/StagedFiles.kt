package com.flue.launcher.data

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 「先写新文件、确认成功再删旧文件」的单文件替换（R37）。
 *
 * 以前图片 / 字体 / 视频 / 备份媒体都是先清空目录再写新文件：解码失败、空间不足、
 * 字体校验失败或进程被杀，旧文件已经没了，配置却还指着它。这里的约定：
 *
 * 1. [writeNew] 先写到目录里的点号临时文件，写完并通过 `validate` 后才改名成正式文件名；
 *    任何一步失败都删掉临时文件并抛出，目录里原有文件一个不动。
 * 2. 调用方确认新路径已经生效（例如配置已提交）后，再用 [pruneExcept] 清掉旧文件。
 */
object StagedFiles {
    private const val TEMP_PREFIX = ".staged-"

    fun writeNew(
        directory: File,
        baseName: String,
        extension: String,
        write: (OutputStream) -> Unit,
        validate: (File) -> Unit = {},
    ): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建目录：${directory.name}")
        }
        val unique = UUID.randomUUID().toString().replace("-", "").take(12)
        val temp = File(directory, "$TEMP_PREFIX$unique.tmp")
        val target = File(directory, "${baseName}_${System.currentTimeMillis()}_$unique.$extension")
        try {
            temp.outputStream().use { output ->
                write(output)
                output.flush()
                runCatching { output.fd.sync() }
            }
            validate(temp)
            moveReplacing(temp, target)
            return target
        } catch (error: Throwable) {
            temp.delete()
            target.delete()
            throw error
        }
    }

    /** 删除 [directory] 里除 [keep] 之外的普通文件（含遗留的临时文件）。不进子目录。 */
    fun pruneExcept(directory: File, keep: Collection<File>) {
        val keepPaths = keep.mapTo(HashSet()) { it.absoluteFile.normalize().path }
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.absoluteFile.normalize().path !in keepPaths) file.delete()
        }
    }

    fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
