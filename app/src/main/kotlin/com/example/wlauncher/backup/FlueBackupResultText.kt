package com.flue.launcher.backup

import android.content.Context
import com.flue.launcher.R

/** 导入结果 / 备份错误 → 本地化文案（R37）。分区名映射由调用方传入，与勾选列表共用一处定义。 */
internal object FlueBackupResultText {

    fun importTitle(context: Context, result: FlueBackupImportResult): String = when {
        result.isCompleteSuccess -> context.getString(R.string.progress_import_done)
        else -> context.getString(R.string.backup_import_partial_title)
    }

    fun importDetail(
        context: Context,
        result: FlueBackupImportResult,
        sectionLabel: (String) -> String,
    ): String = buildList {
        val restored = result.restoredSections.joinToString(" · ", transform = sectionLabel)
        if (result.isCompleteSuccess) {
            add(restored.ifBlank { context.getString(R.string.backup_summary_none) })
        } else {
            if (restored.isNotBlank()) add(context.getString(R.string.backup_import_detail_restored_fmt, restored))
            val failed = result.failedSections.joinToString(" · ", transform = sectionLabel)
            if (failed.isNotBlank()) add(context.getString(R.string.backup_import_detail_failed_fmt, failed))
        }
        if (result.failedWatchFaces > 0) {
            add(
                context.getString(
                    R.string.backup_import_detail_watch_faces_fmt,
                    result.restoredWatchFaces,
                    result.failedWatchFaces,
                ),
            )
        }
        // 表盘以外、仍算恢复了的分区里的失败项（背景图、伪装媒体、公开图标等）。
        val otherItems = result.issues.count {
            it.item != null && it.section != FlueBackupOptions.SECTION_WATCH_FILES && it.section in result.restoredSections
        }
        if (otherItems > 0) add(context.getString(R.string.backup_import_detail_items_failed_fmt, otherItems))
        if (result.skippedSettings > 0) {
            add(context.getString(R.string.backup_import_detail_skipped_settings_fmt, result.skippedSettings))
        }
    }.joinToString("\n")

    /** 「零成功」时的失败提示内容。 */
    fun nothingRestoredMessage(context: Context, result: FlueBackupImportResult): String {
        val first = result.issues.firstOrNull()?.let { issue ->
            listOfNotNull(issue.item, issue.message).joinToString(": ")
        }
        return listOfNotNull(context.getString(R.string.backup_import_nothing_restored), first).joinToString(" · ")
    }

    fun errorMessage(context: Context, error: Throwable): String = when (error) {
        is FlueBackupFormatException -> when (error.reason) {
            FlueBackupFormatException.Reason.NEWER_SCHEMA ->
                context.getString(R.string.backup_error_newer_schema_fmt, error.foundSchemaVersion ?: 0)
            FlueBackupFormatException.Reason.NOT_A_BACKUP -> context.getString(R.string.backup_error_not_a_backup)
            FlueBackupFormatException.Reason.TOO_LARGE,
            FlueBackupFormatException.Reason.TOO_MANY_ENTRIES -> context.getString(R.string.backup_error_too_large)
            FlueBackupFormatException.Reason.PATH_OUTSIDE,
            FlueBackupFormatException.Reason.DUPLICATE_ENTRY -> context.getString(R.string.backup_error_unsafe_entry)
            FlueBackupFormatException.Reason.INSUFFICIENT_SPACE -> context.getString(R.string.backup_error_no_space)
        }
        else -> error.message ?: error.javaClass.simpleName
    }
}
