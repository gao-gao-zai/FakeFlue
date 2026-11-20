package com.flue.launcher.surface.apps

/**
 * 公开应用选择器的纯规则（R10）：排序、检索。界面在 [com.flue.launcher.surface.ui.PublicAppPickerPage]。
 *
 * 筛选和排序只影响显示，不改选中结果，也不改桌面排序。
 */
data class PublicAppCandidate(
    val key: String,
    val label: String,
    val packageName: String,
    /** 在当前桌面里的位置，用于“桌面顺序”。 */
    val desktopIndex: Int,
    /** 名称的拉丁转写（小写、空格分词），系统不支持时为 null。 */
    val latin: String? = null,
) {
    /** 检索用的首字母：拉丁转写或名称本身里每个词的首字母。 */
    val initials: String = initialsOf(latin ?: label)

    private val compactLatin: String? = latin?.replace(" ", "")

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val compact = q.replace(" ", "")
        return label.lowercase().contains(q) ||
            packageName.lowercase().contains(q) ||
            initials.startsWith(compact) ||
            compactLatin?.contains(compact) == true
    }

    companion object {
        fun initialsOf(text: String): String = text.lowercase()
            .split(' ', '-', '_', '.', '·')
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
            .joinToString("")
    }
}

enum class PublicAppSort { DESKTOP, NAME, SELECTED_FIRST }

object PublicAppCatalog {
    /**
     * @param selectedSnapshot “已选优先”按进入该排序时的选中集合排，勾选过程中列表不跳动。
     * @param nameOrder 名称排序用的比较器（Android 上是系统 Collator，中文按拼音）。
     */
    fun arrange(
        candidates: List<PublicAppCandidate>,
        query: String,
        sort: PublicAppSort,
        selectedSnapshot: Set<String>,
        nameOrder: Comparator<String>,
    ): List<PublicAppCandidate> {
        val filtered = candidates.filter { it.matches(query) }
        val byName = compareBy<PublicAppCandidate, String>(nameOrder) { it.label }
        return when (sort) {
            PublicAppSort.DESKTOP -> filtered.sortedBy { it.desktopIndex }
            PublicAppSort.NAME -> filtered.sortedWith(byName.thenBy { it.key })
            PublicAppSort.SELECTED_FIRST -> filtered.sortedWith(
                compareBy<PublicAppCandidate> { it.key !in selectedSnapshot }.thenBy { it.desktopIndex },
            )
        }
    }

}
