package com.flue.launcher.watchface.standard.prepared

import com.flue.launcher.watchface.standard.ExpressionValue
import com.flue.launcher.watchface.standard.WatchArithOp
import com.flue.launcher.watchface.standard.WatchAttr
import com.flue.launcher.watchface.standard.WatchBitmapFont
import com.flue.launcher.watchface.standard.WatchBitmapGlyph
import com.flue.launcher.watchface.standard.WatchCompareOp
import com.flue.launcher.watchface.standard.WatchExprNode
import com.flue.launcher.watchface.standard.WatchSvgDocument
import com.flue.launcher.watchface.standard.WatchSvgPathData
import com.flue.launcher.watchface.standard.WatchmakerDefinition
import com.flue.launcher.watchface.standard.WatchmakerDefinitionVersion
import com.flue.launcher.watchface.standard.WatchmakerLayer
import com.flue.launcher.watchface.standard.WatchmakerTheme
import com.flue.launcher.watchface.standard.WatchmakerXmlElement
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** 缓存条目不可信：任何越界、截断、类型不符都抛它，调用方删除条目并走原加载路径。 */
internal class WatchPreparedFormatException(message: String) : Exception(message)

/** 编码时遇到不能无损表示的值（孤立代理字符、过深结构）：本次不写缓存。 */
internal class WatchPreparedUnencodableException(message: String) : Exception(message)

/** 已通过完整性校验的条目头；负载按需再解（见 [WatchPreparedCodec.decodePayload]）。 */
internal class WatchPreparedHeader(
    val engineIdentity: String,
    val installKey: String,
    val validation: ByteArray,
    val manifest: List<WatchPreparedManifestEntry>,
    internal val payloadStart: Int,
    internal val payloadEnd: Int,
)

/**
 * R36 准备缓存的内部二进制格式。
 *
 * 手写、带版本、处处有界：魔数 + 格式版本 + 引擎身份 + 安装身份 + 校验摘要 + 文件清单 +
 * 负载，末尾是前面全部字节的 SHA-256。**不用** Java 对象序列化；读取端对每个字符串、
 * 列表、映射、递归深度都设上限，并且任何长度都不得超过剩余字节数。
 *
 * 引擎行为（XML 解析、表达式编译、SVG/BMFont 解析、Lua 编译）有变化时提升
 * [ENGINE_VERSION]；另外引擎身份还拼上 APK 的安装时间与版本号，升级后自动全部失效。
 */
internal object WatchPreparedCodec {
    const val FORMAT_VERSION: Int = 1
    const val ENGINE_VERSION: Int = 1

    private const val MAGIC: Int = 0x46575043 // "FWPC"
    private const val DIGEST_BYTES = 32
    private const val MAX_XML_DEPTH = 256
    internal const val MAX_EXPRESSION_DEPTH = 512
    private const val MAX_IDENTITY_BYTES = 4 * 1024
    private const val MAX_LUA_CHUNK_BYTES = 4 * 1024 * 1024

    fun encode(
        engineIdentity: String,
        installKey: String,
        validation: ByteArray,
        manifest: List<WatchPreparedManifestEntry>,
        value: WatchPreparedStatic,
    ): ByteArray {
        require(validation.size == DIGEST_BYTES) { "validation digest must be SHA-256" }
        val out = Writer()
        out.int(MAGIC)
        out.int(FORMAT_VERSION)
        out.string(engineIdentity)
        out.string(installKey)
        out.raw(validation)
        out.int(manifest.size)
        manifest.forEach { entry ->
            out.string(entry.relativePath)
            out.long(entry.length)
            out.long(entry.lastModifiedEpochMs)
        }
        val payload = Writer().also { writePayload(it, value) }.toByteArray()
        out.int(payload.size)
        out.raw(payload)
        val body = out.toByteArray()
        val trailer = MessageDigest.getInstance("SHA-256").digest(body)
        return body + trailer
    }

    /**
     * 校验末尾 SHA-256 并读出文件头（引擎/安装身份、摘要、清单），不解负载。
     * 截断、校验和不符、魔数或格式版本不符都抛 [WatchPreparedFormatException]。
     */
    fun decodeHeader(bytes: ByteArray): WatchPreparedHeader {
        if (bytes.size < DIGEST_BYTES + 8) throw WatchPreparedFormatException("truncated")
        val bodyLength = bytes.size - DIGEST_BYTES
        val expected = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, bodyLength) }.digest()
        if (!MessageDigest.isEqual(expected, bytes.copyOfRange(bodyLength, bytes.size))) {
            throw WatchPreparedFormatException("checksum mismatch")
        }
        val input = Reader(bytes, 0, bodyLength)
        if (input.int() != MAGIC) throw WatchPreparedFormatException("magic")
        if (input.int() != FORMAT_VERSION) throw WatchPreparedFormatException("format version")
        val engineIdentity = input.string(MAX_IDENTITY_BYTES)
        val installKey = input.string(MAX_IDENTITY_BYTES)
        val validation = input.raw(DIGEST_BYTES)
        val manifestSize = input.count()
        val manifest = ArrayList<WatchPreparedManifestEntry>(minOf(manifestSize, 1024))
        repeat(manifestSize) {
            manifest += WatchPreparedManifestEntry(
                relativePath = input.string(),
                length = input.long(),
                lastModifiedEpochMs = input.long(),
            )
        }
        val payloadLength = input.int()
        if (payloadLength < 0 || payloadLength != input.remaining) {
            throw WatchPreparedFormatException("payload length")
        }
        return WatchPreparedHeader(
            engineIdentity = engineIdentity,
            installKey = installKey,
            validation = validation,
            manifest = manifest,
            payloadStart = input.position,
            payloadEnd = input.position + payloadLength,
        )
    }

    fun decodePayload(bytes: ByteArray, header: WatchPreparedHeader): WatchPreparedStatic =
        readPayload(Reader(bytes, header.payloadStart, header.payloadEnd))

    // ── payload ────────────────────────────────────────────────────────────

    private fun writePayload(out: Writer, value: WatchPreparedStatic) {
        writeDefinition(out, value.definition)
        out.int(value.svgDocuments.size)
        value.svgDocuments.forEach { (relative, document) ->
            out.string(relative)
            writeSvg(out, document)
        }
        out.int(value.bitmapFonts.size)
        value.bitmapFonts.forEach { (relative, font) ->
            out.string(relative)
            writeBitmapFont(out, font)
        }
        out.string(value.expressionScopeDigest)
        // 过深的语法树不进缓存（读取端要递归还原），这些表达式命中后照常现编译。
        val expressions = value.compiledExpressions.filterValues(::isEncodableAttr)
        out.int(expressions.size)
        expressions.forEach { (source, attr) ->
            out.string(source)
            writeAttr(out, attr)
        }
        val chunk = value.initialScriptChunk
        if (chunk == null) {
            out.int(-1)
        } else {
            if (chunk.size > MAX_LUA_CHUNK_BYTES) throw WatchPreparedUnencodableException("lua chunk")
            out.int(chunk.size)
            out.raw(chunk)
        }
    }

    private fun readPayload(input: Reader): WatchPreparedStatic {
        val definition = readDefinition(input)
        val svgCount = input.count()
        val svg = LinkedHashMap<String, WatchSvgDocument>(minOf(svgCount, 64))
        repeat(svgCount) { svg[input.string()] = readSvg(input) }
        val fontCount = input.count()
        val fonts = LinkedHashMap<String, WatchBitmapFont>(minOf(fontCount, 64))
        repeat(fontCount) { fonts[input.string()] = readBitmapFont(input) }
        val scopeDigest = input.string(MAX_IDENTITY_BYTES)
        val expressionCount = input.count()
        val expressions = HashMap<String, WatchAttr>(minOf(expressionCount, 4096))
        repeat(expressionCount) { expressions[input.string()] = readAttr(input) }
        val chunkLength = input.int()
        val chunk = when {
            chunkLength == -1 -> null
            chunkLength in 1..MAX_LUA_CHUNK_BYTES -> input.raw(chunkLength)
            else -> throw WatchPreparedFormatException("lua chunk length")
        }
        if (input.remaining != 0) throw WatchPreparedFormatException("trailing payload bytes")
        return WatchPreparedStatic(
            definition = definition,
            svgDocuments = svg,
            bitmapFonts = fonts,
            expressionScopeDigest = scopeDigest,
            compiledExpressions = expressions,
            initialScriptChunk = chunk,
        )
    }

    // ── watch.xml model ────────────────────────────────────────────────────

    private fun writeDefinition(out: Writer, definition: WatchmakerDefinition) {
        out.int(definition.version.ordinal)
        out.map(definition.rootAttributes)
        out.int(definition.themes.size)
        definition.themes.forEach { theme ->
            out.string(theme.name)
            out.map(theme.attributes)
        }
        out.int(definition.layers.size)
        definition.layers.forEach { layer ->
            out.nullableString(layer.id)
            out.string(layer.type)
            out.nullableString(layer.parentId)
            out.map(layer.attributes)
            writeElements(out, layer.children, depth = 1)
        }
        writeElements(out, definition.auxiliaryElements, depth = 1)
    }

    private fun readDefinition(input: Reader): WatchmakerDefinition {
        val versions = WatchmakerDefinitionVersion.values()
        val versionOrdinal = input.int()
        if (versionOrdinal !in versions.indices) throw WatchPreparedFormatException("definition version")
        val rootAttributes = input.map()
        val themeCount = input.count()
        val themes = ArrayList<WatchmakerTheme>(minOf(themeCount, 64))
        repeat(themeCount) { themes += WatchmakerTheme(name = input.string(), attributes = input.map()) }
        val layerCount = input.count()
        val layers = ArrayList<WatchmakerLayer>(minOf(layerCount, 1024))
        repeat(layerCount) {
            layers += WatchmakerLayer(
                id = input.nullableString(),
                type = input.string(),
                parentId = input.nullableString(),
                attributes = input.map(),
                children = readElements(input, depth = 1),
            )
        }
        val auxiliary = readElements(input, depth = 1)
        return WatchmakerDefinition(
            version = versions[versionOrdinal],
            rootAttributes = rootAttributes,
            themes = themes,
            layers = layers,
            auxiliaryElements = auxiliary,
        )
    }

    private fun writeElements(out: Writer, elements: List<WatchmakerXmlElement>, depth: Int) {
        if (depth > MAX_XML_DEPTH) throw WatchPreparedUnencodableException("xml depth")
        out.int(elements.size)
        elements.forEach { element ->
            out.string(element.name)
            out.map(element.attributes)
            out.nullableString(element.text)
            writeElements(out, element.children, depth + 1)
        }
    }

    private fun readElements(input: Reader, depth: Int): List<WatchmakerXmlElement> {
        if (depth > MAX_XML_DEPTH) throw WatchPreparedFormatException("xml depth")
        val count = input.count()
        if (count == 0) return emptyList()
        val elements = ArrayList<WatchmakerXmlElement>(minOf(count, 256))
        repeat(count) {
            elements += WatchmakerXmlElement(
                name = input.string(),
                attributes = input.map(),
                text = input.nullableString(),
                children = readElements(input, depth + 1),
            )
        }
        return elements
    }

    // ── SVG / BMFont ───────────────────────────────────────────────────────

    private fun writeSvg(out: Writer, document: WatchSvgDocument) {
        out.int(document.viewBox.size)
        document.viewBox.forEach(out::float)
        out.nullableFloat(document.width)
        out.nullableFloat(document.height)
        out.int(document.paths.size)
        document.paths.forEach { path ->
            out.string(path.pathData)
            out.nullableString(path.fill)
        }
    }

    private fun readSvg(input: Reader): WatchSvgDocument {
        val viewBoxSize = input.count()
        if (viewBoxSize > 16) throw WatchPreparedFormatException("svg viewBox")
        val viewBox = List(viewBoxSize) { input.float() }
        val width = input.nullableFloat()
        val height = input.nullableFloat()
        val pathCount = input.count()
        val paths = ArrayList<WatchSvgPathData>(minOf(pathCount, 128))
        repeat(pathCount) { paths += WatchSvgPathData(input.string(), input.nullableString()) }
        return WatchSvgDocument(viewBox, width, height, paths)
    }

    private fun writeBitmapFont(out: Writer, font: WatchBitmapFont) {
        out.string(font.face)
        out.int(font.nominalSize)
        out.int(font.lineHeight)
        out.int(font.baseline)
        out.int(font.atlasWidth)
        out.int(font.atlasHeight)
        out.int(font.pages.size)
        font.pages.forEach(out::string)
        out.int(font.glyphs.size)
        font.glyphs.forEach { (codePoint, glyph) ->
            out.int(codePoint)
            out.int(glyph.id)
            out.int(glyph.x)
            out.int(glyph.y)
            out.int(glyph.width)
            out.int(glyph.height)
            out.int(glyph.xOffset)
            out.int(glyph.yOffset)
            out.int(glyph.xAdvance)
            out.int(glyph.page)
        }
        val kernings = font.kerningTable
        out.int(kernings.size)
        kernings.forEach { (key, amount) ->
            out.long(key)
            out.int(amount)
        }
    }

    private fun readBitmapFont(input: Reader): WatchBitmapFont {
        val face = input.string()
        val nominalSize = input.int()
        val lineHeight = input.int()
        val baseline = input.int()
        val atlasWidth = input.int()
        val atlasHeight = input.int()
        val pageCount = input.count()
        val pages = List(pageCount) { input.string() }
        val glyphCount = input.count()
        val glyphs = LinkedHashMap<Int, WatchBitmapGlyph>(minOf(glyphCount, 4096))
        repeat(glyphCount) {
            val codePoint = input.int()
            glyphs[codePoint] = WatchBitmapGlyph(
                id = input.int(),
                x = input.int(),
                y = input.int(),
                width = input.int(),
                height = input.int(),
                xOffset = input.int(),
                yOffset = input.int(),
                xAdvance = input.int(),
                page = input.int(),
            )
        }
        val kerningCount = input.count()
        val kernings = HashMap<Long, Int>(minOf(kerningCount, 8192))
        repeat(kerningCount) { kernings[input.long()] = input.int() }
        if (glyphs.values.any { it.page !in pages.indices }) throw WatchPreparedFormatException("bmfont page")
        return WatchBitmapFont(
            face = face,
            nominalSize = nominalSize,
            lineHeight = lineHeight,
            baseline = baseline,
            atlasWidth = atlasWidth,
            atlasHeight = atlasHeight,
            pages = pages,
            glyphs = glyphs,
            kernings = kernings,
        )
    }

    // ── expressions ────────────────────────────────────────────────────────

    private fun isEncodableAttr(attr: WatchAttr): Boolean =
        attr !is WatchAttr.Dynamic || expressionDepth(attr.node) <= MAX_EXPRESSION_DEPTH

    /** 迭代求深度：左结合长链可达数千层，不能在这里递归。 */
    internal fun expressionDepth(root: WatchExprNode): Int {
        val nodes = ArrayDeque<WatchExprNode>()
        val depths = ArrayDeque<Int>()
        nodes.addLast(root)
        depths.addLast(1)
        var maximum = 0
        while (nodes.isNotEmpty()) {
            val node = nodes.removeLast()
            val depth = depths.removeLast()
            if (depth > maximum) maximum = depth
            if (maximum > MAX_EXPRESSION_DEPTH) return maximum
            children(node).forEach { child ->
                nodes.addLast(child)
                depths.addLast(depth + 1)
            }
        }
        return maximum
    }

    private fun children(node: WatchExprNode): List<WatchExprNode> = when (node) {
        is WatchExprNode.Literal, is WatchExprNode.TagValue,
        is WatchExprNode.Template, is WatchExprNode.ScopeRef -> emptyList()
        is WatchExprNode.Arith -> listOf(node.left, node.right)
        is WatchExprNode.Power -> listOf(node.left, node.right)
        is WatchExprNode.Negate -> listOf(node.value)
        is WatchExprNode.Compare -> listOf(node.left, node.right)
        is WatchExprNode.Concat -> listOf(node.left, node.right)
        is WatchExprNode.And -> listOf(node.left, node.right)
        is WatchExprNode.Or -> listOf(node.left, node.right)
        is WatchExprNode.Not -> listOf(node.value)
        is WatchExprNode.Call -> node.arguments
    }

    private fun writeAttr(out: Writer, attr: WatchAttr) {
        when (attr) {
            WatchAttr.Unsupported -> out.byte(0)
            is WatchAttr.Const -> {
                out.byte(1)
                writeValue(out, attr.value)
            }
            is WatchAttr.Dynamic -> {
                out.byte(2)
                writeNode(out, attr.node)
                out.int(attr.tagDependencies.size)
                attr.tagDependencies.forEach(out::string)
                out.bool(attr.hasScopeRef)
                out.int(attr.nodeCount)
            }
        }
    }

    private fun readAttr(input: Reader): WatchAttr = when (input.byte()) {
        0 -> WatchAttr.Unsupported
        1 -> WatchAttr.Const(readValue(input))
        2 -> {
            val node = readNode(input, depth = 1)
            val dependencyCount = input.count()
            val dependencies = LinkedHashSet<String>(minOf(dependencyCount, 64))
            repeat(dependencyCount) { dependencies += input.string() }
            val hasScopeRef = input.bool()
            val nodeCount = input.int()
            if (nodeCount != node.nodeCount) throw WatchPreparedFormatException("node count")
            WatchAttr.Dynamic(node, dependencies, hasScopeRef, nodeCount)
        }
        else -> throw WatchPreparedFormatException("attr tag")
    }

    private fun writeValue(out: Writer, value: ExpressionValue) {
        when (value) {
            ExpressionValue.Nil -> out.byte(0)
            is ExpressionValue.Numeric -> {
                out.byte(1)
                out.long(java.lang.Double.doubleToRawLongBits(value.value))
            }
            is ExpressionValue.Text -> {
                out.byte(2)
                out.string(value.value)
            }
            is ExpressionValue.Logical -> {
                out.byte(3)
                out.bool(value.value)
            }
        }
    }

    private fun readValue(input: Reader): ExpressionValue = when (input.byte()) {
        0 -> ExpressionValue.Nil
        1 -> ExpressionValue.Numeric(java.lang.Double.longBitsToDouble(input.long()))
        2 -> ExpressionValue.Text(input.string())
        3 -> ExpressionValue.Logical(input.bool())
        else -> throw WatchPreparedFormatException("value tag")
    }

    private fun writeNode(out: Writer, node: WatchExprNode) {
        when (node) {
            is WatchExprNode.Literal -> {
                out.byte(0)
                writeValue(out, node.value)
            }
            is WatchExprNode.TagValue -> {
                out.byte(1)
                out.string(node.tag)
            }
            is WatchExprNode.Template -> {
                out.byte(2)
                out.int(node.parts.size)
                node.parts.forEach { part ->
                    when (part) {
                        is WatchExprNode.Template.Part.Chunk -> {
                            out.byte(0)
                            out.string(part.text)
                        }
                        is WatchExprNode.Template.Part.Tag -> {
                            out.byte(1)
                            out.string(part.tag)
                        }
                    }
                }
            }
            is WatchExprNode.ScopeRef -> {
                out.byte(3)
                out.string(node.name)
            }
            is WatchExprNode.Arith -> {
                out.byte(4)
                out.int(node.op.ordinal)
                writeNode(out, node.left)
                writeNode(out, node.right)
            }
            is WatchExprNode.Power -> {
                out.byte(5)
                writeNode(out, node.left)
                writeNode(out, node.right)
                out.bool(node.compensateNegativeBase)
            }
            is WatchExprNode.Negate -> {
                out.byte(6)
                writeNode(out, node.value)
            }
            is WatchExprNode.Compare -> {
                out.byte(7)
                out.int(node.op.ordinal)
                writeNode(out, node.left)
                writeNode(out, node.right)
            }
            is WatchExprNode.Concat -> {
                out.byte(8)
                writeNode(out, node.left)
                writeNode(out, node.right)
            }
            is WatchExprNode.And -> {
                out.byte(9)
                writeNode(out, node.left)
                writeNode(out, node.right)
            }
            is WatchExprNode.Or -> {
                out.byte(10)
                writeNode(out, node.left)
                writeNode(out, node.right)
            }
            is WatchExprNode.Not -> {
                out.byte(11)
                writeNode(out, node.value)
            }
            is WatchExprNode.Call -> {
                out.byte(12)
                out.string(node.name)
                out.int(node.arguments.size)
                node.arguments.forEach { writeNode(out, it) }
            }
        }
    }

    private fun readNode(input: Reader, depth: Int): WatchExprNode {
        if (depth > MAX_EXPRESSION_DEPTH) throw WatchPreparedFormatException("expression depth")
        val next = depth + 1
        return when (input.byte()) {
            0 -> WatchExprNode.Literal(readValue(input))
            1 -> WatchExprNode.TagValue(input.string())
            2 -> {
                val count = input.count()
                val parts = ArrayList<WatchExprNode.Template.Part>(minOf(count, 64))
                repeat(count) {
                    parts += when (input.byte()) {
                        0 -> WatchExprNode.Template.Part.Chunk(input.string())
                        1 -> WatchExprNode.Template.Part.Tag(input.string())
                        else -> throw WatchPreparedFormatException("template part")
                    }
                }
                WatchExprNode.Template(parts)
            }
            3 -> WatchExprNode.ScopeRef(input.string())
            4 -> {
                val op = enumAt(WatchArithOp.values(), input.int())
                WatchExprNode.Arith(op, readNode(input, next), readNode(input, next))
            }
            5 -> {
                val left = readNode(input, next)
                val right = readNode(input, next)
                WatchExprNode.Power(left, right, input.bool())
            }
            6 -> WatchExprNode.Negate(readNode(input, next))
            7 -> {
                val op = enumAt(WatchCompareOp.values(), input.int())
                WatchExprNode.Compare(op, readNode(input, next), readNode(input, next))
            }
            8 -> WatchExprNode.Concat(readNode(input, next), readNode(input, next))
            9 -> WatchExprNode.And(readNode(input, next), readNode(input, next))
            10 -> WatchExprNode.Or(readNode(input, next), readNode(input, next))
            11 -> WatchExprNode.Not(readNode(input, next))
            12 -> {
                val name = input.string()
                val count = input.count()
                if (count > MAX_CALL_ARGUMENTS) throw WatchPreparedFormatException("call arity")
                WatchExprNode.Call(name, List(count) { readNode(input, next) })
            }
            else -> throw WatchPreparedFormatException("node tag")
        }
    }

    private const val MAX_CALL_ARGUMENTS = 256

    private fun <T> enumAt(values: Array<T>, ordinal: Int): T =
        values.getOrNull(ordinal) ?: throw WatchPreparedFormatException("enum ordinal")

    // ── primitives ─────────────────────────────────────────────────────────

    private class Writer {
        private val buffer = ByteArrayOutputStream(16 * 1024)

        fun byte(value: Int) = buffer.write(value)

        fun bool(value: Boolean) = buffer.write(if (value) 1 else 0)

        fun int(value: Int) {
            buffer.write(value ushr 24)
            buffer.write(value ushr 16)
            buffer.write(value ushr 8)
            buffer.write(value)
        }

        fun long(value: Long) {
            int((value ushr 32).toInt())
            int(value.toInt())
        }

        fun float(value: Float) = int(java.lang.Float.floatToRawIntBits(value))

        fun nullableFloat(value: Float?) {
            bool(value != null)
            if (value != null) float(value)
        }

        fun raw(bytes: ByteArray) = buffer.write(bytes, 0, bytes.size)

        fun string(value: String) {
            if (hasUnpairedSurrogate(value)) throw WatchPreparedUnencodableException("unpaired surrogate")
            val bytes = value.toByteArray(Charsets.UTF_8)
            int(bytes.size)
            raw(bytes)
        }

        fun nullableString(value: String?) {
            bool(value != null)
            if (value != null) string(value)
        }

        fun map(values: Map<String, String>) {
            int(values.size)
            values.forEach { (key, value) ->
                string(key)
                string(value)
            }
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    private class Reader(private val bytes: ByteArray, start: Int, private val end: Int) {
        var position: Int = start
            private set

        val remaining: Int get() = end - position

        private fun need(count: Int) {
            if (count < 0 || count > remaining) throw WatchPreparedFormatException("truncated")
        }

        fun byte(): Int {
            need(1)
            return bytes[position++].toInt() and 0xff
        }

        fun bool(): Boolean = when (byte()) {
            0 -> false
            1 -> true
            else -> throw WatchPreparedFormatException("boolean")
        }

        fun int(): Int {
            need(4)
            val value = ((bytes[position].toInt() and 0xff) shl 24) or
                ((bytes[position + 1].toInt() and 0xff) shl 16) or
                ((bytes[position + 2].toInt() and 0xff) shl 8) or
                (bytes[position + 3].toInt() and 0xff)
            position += 4
            return value
        }

        fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xffff_ffffL)

        fun float(): Float = java.lang.Float.intBitsToFloat(int())

        fun nullableFloat(): Float? = if (bool()) float() else null

        /** 元素个数：非负，且每个元素至少占一个字节，所以不可能超过剩余字节数。 */
        fun count(): Int {
            val value = int()
            if (value < 0 || value > remaining) throw WatchPreparedFormatException("count")
            return value
        }

        fun raw(length: Int): ByteArray {
            need(length)
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun string(maxBytes: Int = Int.MAX_VALUE): String {
            val length = int()
            if (length < 0 || length > maxBytes) throw WatchPreparedFormatException("string length")
            need(length)
            return String(bytes, position, length, Charsets.UTF_8).also { position += length }
        }

        fun nullableString(): String? = if (bool()) string() else null

        fun map(): Map<String, String> {
            val count = count()
            if (count == 0) return emptyMap()
            val result = LinkedHashMap<String, String>(minOf(count, 64) * 2)
            repeat(count) { result[string()] = string() }
            return result
        }
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                index += 2
                continue
            }
            if (Character.isLowSurrogate(char)) return true
            index++
        }
        return false
    }
}
