package com.flue.launcher.surface.apps

import android.os.Build

/**
 * 系统提供的汉字转拼音（R10 检索）。Android 10 起有 `android.icu.text.Transliterator`；
 * 更早的系统没有，[create] 返回 null，选择器退回名称、英文首字母和包名检索并提示范围。
 */
object HanLatin {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 返回转写函数：结果为小写 ASCII、按字分词，例如“微信” → “wei xin”。 */
    fun create(): ((String) -> String?)? {
        if (!supported) return null
        val transliterator = runCatching {
            android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        }.getOrNull() ?: return null
        return { text ->
            runCatching { transliterator.transliterate(text).trim().replace(Regex("\\s+"), " ") }.getOrNull()
        }
    }
}
