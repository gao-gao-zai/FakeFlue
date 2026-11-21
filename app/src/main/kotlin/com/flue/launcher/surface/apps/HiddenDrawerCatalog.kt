package com.flue.launcher.surface.apps

/**
 * 隐藏完整抽屉的纯规则（R10、R14）：按首字母分组、检索、置顶。
 *
 * 条目复用 [PublicAppCandidate]（名称、包名、拉丁转写和首字母检索）。置顶项单独排在最前，
 * 不在字母分组里重复出现；已卸载应用的置顶记录不显示。
 */
data class HiddenDrawerSection(val letter: Char, val entries: List<PublicAppCandidate>)

data class HiddenDrawerLayout(
    val pinned: List<PublicAppCandidate>,
    val sections: List<HiddenDrawerSection>,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && sections.isEmpty()
    val letters: List<Char> get() = sections.map { it.letter }
}

object HiddenDrawerCatalog {
    /** 随模式键：伪装配置里的置顶集合（componentKey）。 */
    const val PINNED_KEY = "hidden_drawer_pinned"

    /** 数字、符号和无法转写的名称归到这一组，排在最后。 */
    const val OTHER = '#'

    fun letterOf(candidate: PublicAppCandidate): Char {
        val source = candidate.latin ?: candidate.label
        val first = source.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return OTHER
        return if (first in 'A'..'Z') first else OTHER
    }

    /**
     * @param nameOrder 组内按名称排序用的比较器（Android 上是系统 Collator）。
     */
    fun arrange(
        candidates: List<PublicAppCandidate>,
        query: String,
        pinned: Set<String>,
        nameOrder: Comparator<String>,
    ): HiddenDrawerLayout {
        val byName = compareBy<PublicAppCandidate, String>(nameOrder) { it.label }.thenBy { it.key }
        val matched = candidates.distinctBy { it.key }.filter { it.matches(query) }
        val (top, rest) = matched.partition { it.key in pinned }
        val sections = rest.groupBy(::letterOf)
            .toSortedMap(compareBy<Char> { it == OTHER }.thenBy { it })
            .map { (letter, entries) -> HiddenDrawerSection(letter, entries.sortedWith(byName)) }
        return HiddenDrawerLayout(top.sortedWith(byName), sections)
    }

    fun togglePin(pinned: Set<String>, key: String): Set<String> = if (key in pinned) pinned - key else pinned + key

    /**
     * 字母索引跳转：[letter] 对应分组在列表里的位置。列表依次是置顶项、每组一个标题加组内条目。
     * 没有该字母时跳到其后最近的一组；都没有返回 null。
     */
    fun indexOfLetter(layout: HiddenDrawerLayout, letter: Char): Int? {
        var index = layout.pinned.size
        layout.sections.forEach { section ->
            val passed = if (letter == OTHER) section.letter == OTHER else section.letter == OTHER || section.letter >= letter
            if (passed) return index
            index += 1 + section.entries.size
        }
        return null
    }
}
