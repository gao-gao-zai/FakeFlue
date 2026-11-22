package com.flue.launcher.backup

/**
 * 一项没恢复成功的内容。[section] 是 [FlueBackupOptions] 的分区 key，
 * [item] 是分区里的具体对象（表盘条目名、媒体角色等），整个分区失败时为 null。
 */
data class FlueBackupImportIssue(
    val section: String,
    val item: String?,
    val message: String,
)

/**
 * 导入的真实结果（R37：零成功或部分失败不能显示全部成功）。
 *
 * 以前 importBackup 返回 Unit，单个表盘、媒体文件失败都被吞掉，UI 按「勾选了什么」
 * 显示「导入完成」。现在逐分区、逐表盘记录成败，由 UI 如实呈现。
 */
data class FlueBackupImportResult(
    /** 本次实际尝试的分区（勾选 ∩ 备份里有的）。 */
    val requestedSections: List<String>,
    /** 已写入的分区。部分成功的分区（例如部分表盘失败）也算在内，失败项见 [issues]。 */
    val restoredSections: List<String>,
    val issues: List<FlueBackupImportIssue>,
    val restoredWatchFaces: Int = 0,
    val failedWatchFaces: Int = 0,
    /** 设置区段里因键名未知或类型不符而跳过的项数（不算失败，只提示）。 */
    val skippedSettings: Int = 0,
) {
    val nothingRestored: Boolean get() = restoredSections.isEmpty()

    val isCompleteSuccess: Boolean
        get() = issues.isEmpty() && restoredSections.toSet() == requestedSections.toSet()

    val failedSections: List<String>
        get() = requestedSections.filter { it !in restoredSections }
}
