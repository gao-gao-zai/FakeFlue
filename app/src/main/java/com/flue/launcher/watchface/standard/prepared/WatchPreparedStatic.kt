package com.flue.launcher.watchface.standard.prepared

import com.flue.launcher.watchface.standard.WatchAttr
import com.flue.launcher.watchface.standard.WatchBitmapFont
import com.flue.launcher.watchface.standard.WatchExpressionScope
import com.flue.launcher.watchface.standard.WatchSvgDocument
import com.flue.launcher.watchface.standard.WatchmakerDefinition
import java.security.MessageDigest

/**
 * R36：一张已安装 Watchmaker 表盘的**静态**准备结果。
 *
 * 只装与本次运行环境无关、可由表盘文件内容完全决定的东西：
 *
 * - [definition]：watch.xml 解析出的 XML 模型与图层结构（渲染计划由它现场重建，
 *   计划里的条目引用图层对象，必须与本次的 definition 同源）；
 * - [svgDocuments] / [bitmapFonts]：SVG 的路径文本与 `.fnt` 位图字体定义，键为表盘内
 *   小写相对路径——绝不存绝对路径，命中后按本次安装目录重新解析；
 * - [compiledExpressions]：属性表达式语法树与 tag 依赖，只在 [expressionScopeDigest]
 *   与本次静态作用域内容一致时使用；
 * - [initialScriptChunk]：初始脚本的 LuaJ 编译产物（只跳过编译，每次仍在全新沙箱里执行）。
 *
 * 位图、字体、Path、Lua 运行时、实时数据、DPI/安全区/语言与显示坐标一律不在这里。
 */
internal class WatchPreparedStatic(
    val definition: WatchmakerDefinition,
    val svgDocuments: Map<String, WatchSvgDocument>,
    val bitmapFonts: Map<String, WatchBitmapFont>,
    val expressionScopeDigest: String,
    val compiledExpressions: Map<String, WatchAttr>,
    val initialScriptChunk: ByteArray?,
) {
    /** 作用域内容不同（或缓存里没有）就返回 null：调用方照常现编译。 */
    fun expressionsFor(scope: WatchExpressionScope): Map<String, WatchAttr>? =
        compiledExpressions.takeIf {
            it.isNotEmpty() && expressionScopeDigest == watchPreparedScopeDigest(scope)
        }
}

/** 命中检查清单的一项：表盘内相对路径（原大小写）、字节数、修改时间。 */
internal data class WatchPreparedManifestEntry(
    val relativePath: String,
    val length: Long,
    val lastModifiedEpochMs: Long,
)

/** 静态作用域（`var_* = 字面量`）的内容摘要：表达式编译结果只对同一内容有效。 */
internal fun watchPreparedScopeDigest(scope: WatchExpressionScope): String {
    val digest = MessageDigest.getInstance("SHA-256")
    scope.variables.forEach { (name, value) ->
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().toHex()
}

/**
 * 命中校验摘要。
 *
 * [definitionRevision] 已覆盖：每个文件的相对路径/大小/时间、watch.xml 全文、初始脚本全文
 * 与参与渲染的 SVG 全文（见 `WatchmakerWatchFaceView.prepareSource`）。这里再补上位图字体
 * 定义（`.fnt`）全文，于是同大小、同时间的内容修改也会落空。
 */
internal fun watchPreparedValidationDigest(
    definitionRevision: String,
    bitmapFontSources: List<Pair<String, String>>,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("r36-validation".toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(definitionRevision.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    bitmapFontSources.forEach { (relative, text) ->
        digest.update(relative.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest()
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = HEX[value ushr 4]
        chars[index * 2 + 1] = HEX[value and 0x0f]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
