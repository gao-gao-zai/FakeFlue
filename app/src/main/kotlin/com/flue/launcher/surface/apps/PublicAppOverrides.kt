package com.flue.launcher.surface.apps

import org.json.JSONObject

/**
 * 伪装模式里公开应用单独显示的名称和图标（R10）。
 *
 * 存于随模式键 [KEY]（伪装模式实际落在 `fflue.disguise::public_app_overrides`），完整模式不读，
 * 所以不会改到完整桌面。键是应用列表条目的 componentKey；图标只存伪装媒体目录
 * [ICON_DIR] 下的文件名，不存绝对路径，备份恢复后仍然有效。
 */
data class PublicAppOverride(
    val label: String? = null,
    val iconFile: String? = null,
) {
    val isEmpty: Boolean get() = label.isNullOrBlank() && iconFile.isNullOrBlank()
}

object PublicAppOverrides {
    const val KEY = "public_app_overrides"

    /** 伪装媒体根目录下的图标子目录。 */
    const val ICON_DIR = "public_icons"

    const val MAX_LABEL_LENGTH = 40

    private const val FIELD_LABEL = "label"
    private const val FIELD_ICON = "icon"

    fun parse(raw: String?): Map<String, PublicAppOverride> {
        if (raw.isNullOrBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                val entry = json.optJSONObject(key) ?: return@forEach
                val override = PublicAppOverride(
                    label = entry.optString(FIELD_LABEL).let(::normalizeLabel),
                    iconFile = entry.optString(FIELD_ICON).takeIf(::isSafeIconFile),
                )
                if (key.isNotBlank() && !override.isEmpty) put(key, override)
            }
        }
    }

    fun serialize(overrides: Map<String, PublicAppOverride>): String {
        val json = JSONObject()
        overrides.toSortedMap().forEach { (key, value) ->
            val label = normalizeLabel(value.label)
            val icon = value.iconFile?.takeIf(::isSafeIconFile)
            if (key.isBlank() || (label == null && icon == null)) return@forEach
            json.put(
                key,
                JSONObject().apply {
                    label?.let { put(FIELD_LABEL, it) }
                    icon?.let { put(FIELD_ICON, it) }
                },
            )
        }
        return json.toString()
    }

    /** 原始存储值非空却不是合法 JSON：[parse] 会把它当成空表，调用方不能据此判断图标已无人引用。 */
    fun isUnreadable(raw: String?): Boolean =
        !raw.isNullOrBlank() && runCatching { JSONObject(raw) }.isFailure

    /**
     * 需要保留的图标：既包括 [overrides] 引用的，也包括出现在读不出的旧值备份 [unreadableBackups]
     * 里的文件名（旧值解析不了，只能按文本包含判断，宁可多留不误删）。
     */
    fun orphanIcons(
        overrides: Map<String, PublicAppOverride>,
        files: Collection<String>,
        unreadableBackups: Collection<String>,
    ): Set<String> = orphanIcons(overrides, files)
        .filterTo(HashSet()) { name -> unreadableBackups.none { it.contains(name) } }

    /** 去掉首尾空白和换行，空串视为不覆盖，过长截断。 */
    fun normalizeLabel(raw: String?): String? =
        raw?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(MAX_LABEL_LENGTH)?.takeIf { it.isNotEmpty() && it != "null" }

    /** 只接受本目录下的普通文件名，拒绝路径穿越。 */
    fun isSafeIconFile(name: String?): Boolean =
        !name.isNullOrBlank() && name != "null" && name.matches(Regex("[A-Za-z0-9._-]{1,96}")) && !name.startsWith(".")

    /** 写入或清除一项；结果为空的项直接移除。 */
    fun update(
        overrides: Map<String, PublicAppOverride>,
        key: String,
        transform: (PublicAppOverride) -> PublicAppOverride,
    ): Map<String, PublicAppOverride> {
        val next = transform(overrides[key] ?: PublicAppOverride())
            .let { it.copy(label = normalizeLabel(it.label), iconFile = it.iconFile?.takeIf(::isSafeIconFile)) }
        return if (next.isEmpty) overrides - key else overrides + (key to next)
    }

    /** 目录里不再被任何覆盖引用的图标文件名，用于清理。 */
    fun orphanIcons(overrides: Map<String, PublicAppOverride>, files: Collection<String>): Set<String> {
        val referenced = overrides.values.mapNotNullTo(HashSet()) { it.iconFile }
        return files.filterTo(HashSet()) { it !in referenced }
    }
}
