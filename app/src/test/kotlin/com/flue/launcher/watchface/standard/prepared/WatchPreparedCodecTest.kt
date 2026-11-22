package com.flue.launcher.watchface.standard.prepared

import com.flue.launcher.watchface.standard.SecureWatchXmlParser
import com.flue.launcher.watchface.standard.WatchAttr
import com.flue.launcher.watchface.standard.WatchExpressionScope
import com.flue.launcher.watchface.standard.WatchFaceLuaRuntime
import com.flue.launcher.watchface.standard.WatchmakerDefinitionParser
import com.flue.launcher.watchface.standard.compileWatchAttr
import com.flue.launcher.watchface.standard.parseWatchBitmapFont
import com.flue.launcher.watchface.standard.parseWatchSvgDocument
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WatchPreparedCodecTest {

    @Test
    fun roundTripPreservesEveryStaticArtifactExactly() {
        val value = sampleStatic()
        val bytes = encode(value)
        val header = WatchPreparedCodec.decodeHeader(bytes)
        assertEquals(ENGINE, header.engineIdentity)
        assertEquals(INSTALL_KEY, header.installKey)
        assertArrayEquals(VALIDATION, header.validation)
        assertEquals(MANIFEST, header.manifest)
        val decoded = WatchPreparedCodec.decodePayload(bytes, header)

        assertEquals(value.definition, decoded.definition)
        // 属性顺序也要一致：表达式预编译、图层属性遍历都按文档顺序。
        assertEquals(
            value.definition.layers.map { it.attributes.keys.toList() },
            decoded.definition.layers.map { it.attributes.keys.toList() },
        )
        assertEquals(value.svgDocuments, decoded.svgDocuments)
        assertEquals(value.bitmapFonts, decoded.bitmapFonts)
        assertEquals(value.expressionScopeDigest, decoded.expressionScopeDigest)
        assertEquals(value.compiledExpressions, decoded.compiledExpressions)
        assertArrayEquals(value.initialScriptChunk, decoded.initialScriptChunk)
        // 解码结果与现编译逐项相等，命中前后表达式产物一致。
        decoded.compiledExpressions.forEach { (source, attr) ->
            assertEquals(source, compileWatchAttr(source, SCOPE), attr)
        }
    }

    @Test
    fun expressionsAreOnlyOfferedForTheSameStaticScopeContent() {
        val decoded = roundTrip(sampleStatic())
        assertNotNull(decoded.expressionsFor(WatchExpressionScope.fromInitialScript(SCRIPT)))
        assertEquals(
            null,
            decoded.expressionsFor(WatchExpressionScope.fromInitialScript("var_a = 4\nvar_label = 'x'")),
        )
        assertEquals(null, decoded.expressionsFor(WatchExpressionScope.EMPTY))
    }

    @Test
    fun everyTruncationAndEveryByteFlipIsRejected() {
        val bytes = encode(sampleStatic())
        for (length in 0 until bytes.size) {
            assertRejected("truncated to $length") { decodeAll(bytes.copyOf(length)) }
        }
        val step = maxOf(1, bytes.size / 4096)
        for (index in bytes.indices step step) {
            val corrupted = bytes.copyOf()
            corrupted[index] = (corrupted[index].toInt() xor 0x5a).toByte()
            assertRejected("flipped byte $index") { decodeAll(corrupted) }
        }
    }

    @Test
    fun correctlySignedButMalformedPayloadIsStillRejected() {
        val bytes = encode(sampleStatic())
        val header = WatchPreparedCodec.decodeHeader(bytes)
        val body = bytes.copyOf(bytes.size - 32)
        // 负载第一个 int 是 definition.version 序号；改成越界值并重新签名。
        val badVersion = body.copyOf().also { writeInt(it, header.payloadStart, 99) }
        assertRejected("version ordinal") { decodeAll(sign(badVersion)) }
        // 根属性个数改成远超剩余字节。
        val badCount = body.copyOf().also { writeInt(it, header.payloadStart + 4, Int.MAX_VALUE) }
        assertRejected("count") { decodeAll(sign(badCount)) }
        // 负载长度字段与实际不符。
        val badLength = body.copyOf().also { writeInt(it, header.payloadStart - 4, 3) }
        assertRejected("payload length") { decodeAll(sign(badLength)) }
        // 未来格式版本。
        val future = body.copyOf().also { writeInt(it, 4, WatchPreparedCodec.FORMAT_VERSION + 1) }
        assertRejected("format version") { decodeAll(sign(future)) }
    }

    @Test
    fun overlyDeepExpressionTreesAreLeftOutAndCompiledLive() {
        val deep = (1..(WatchPreparedCodec.MAX_EXPRESSION_DEPTH + 10)).joinToString("+") { "{bl}" }
        val attr = compileWatchAttr(deep, SCOPE)
        assertTrue(attr is WatchAttr.Dynamic)
        val value = sampleStatic(extra = mapOf(deep to attr))
        val decoded = roundTrip(value)
        assertFalse(deep in decoded.compiledExpressions)
        assertTrue(decoded.compiledExpressions.isNotEmpty())
    }

    @Test
    fun valuesThatCannotRoundTripLosslesslyAreNotEncoded() {
        val definition = WatchmakerDefinitionParser().parse(
            """<Watch name="x"><Layer type="text" text="ok"/></Watch>""".byteInputStream(),
            SecureWatchXmlParser.DEFAULT_MAX_BYTES,
        )
        val broken = WatchPreparedStatic(
            definition = definition,
            svgDocuments = emptyMap(),
            bitmapFonts = emptyMap(),
            expressionScopeDigest = "s",
            compiledExpressions = mapOf("a\uD800" to WatchAttr.Unsupported),
            initialScriptChunk = null,
        )
        try {
            encode(broken)
            fail("unpaired surrogate must not be encoded")
        } catch (_: WatchPreparedUnencodableException) {
        }
    }

    private fun decodeAll(bytes: ByteArray): WatchPreparedStatic {
        val header = WatchPreparedCodec.decodeHeader(bytes)
        return WatchPreparedCodec.decodePayload(bytes, header)
    }

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("$label must be rejected")
        } catch (_: WatchPreparedFormatException) {
        }
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun sign(body: ByteArray): ByteArray =
        body + MessageDigest.getInstance("SHA-256").digest(body)

    internal companion object {
        const val ENGINE = "engine-test"
        const val INSTALL_KEY = "/data/watchmaker_faces/sample"
        val VALIDATION = ByteArray(32) { it.toByte() }
        val MANIFEST = listOf(
            WatchPreparedManifestEntry("watch.xml", 100L, 1_000L),
            WatchPreparedManifestEntry("Fonts_BM/Digits.fnt", 50L, 2_000L),
        )
        const val SCRIPT = "var_a = 3\nvar_label = '温度'\nfunction f()\n  return var_a * 2\nend\n"
        val SCOPE: WatchExpressionScope = WatchExpressionScope.fromInitialScript(SCRIPT)

        val XML = """
            <Watch name="Sample 表盘" shape="R" build_source="studio" author="A&amp;B">
              <ThemeSets><ThemeSet name="night" color="ff0000"/></ThemeSets>
              <Layer id="g" type="group" x="1">
                <Layer id="t" parentId="g" type="text" text="{dh}:{dm}" opacity="{bl} &gt; 50 and 100 or 0"
                  color="ffffff" font="bm:digits" x="var_a * 2"/>
                <Layer id="s" type="svg" path="icon.svg"><Extra k="v">body text<Inner/></Extra></Layer>
              </Layer>
              <Sounds><Sound file="tick.ogg"/></Sounds>
            </Watch>
        """.trimIndent()

        val SVG = """<svg viewBox="0 0 24 24" width="24" fill="#fff"><path d="M0 0L10 10Z"/><path d="M1 1h2" fill="none"/></svg>"""

        val FNT = """
            info face="digits" size=32
            common lineHeight=32 base=26 scaleW=128 scaleH=64 pages=1
            page id=0 file="digits.png"
            char id=48 x=0 y=0 width=16 height=32 xoffset=0 yoffset=0 xadvance=17 page=0
            char id=49 x=16 y=0 width=10 height=32 xoffset=1 yoffset=0 xadvance=12 page=0
            kerning first=48 second=49 amount=-2
        """.trimIndent()

        val SOURCES = listOf(
            "100", "'text'", "{bl} > 50 and 100 or 0", "var_a * 2", "{dh}:{dm}",
            "math.floor({ds}/4) % 6 == 1 and 100 or 0", "'今天是{ddw}'", "-{x}^2",
            "string.format('%02d', {dh})", "not ({bl} < 10)", "{a} .. 'b'", "(", "1/0",
        )

        fun sampleStatic(extra: Map<String, WatchAttr> = emptyMap()): WatchPreparedStatic {
            val definition = WatchmakerDefinitionParser().parse(
                XML.byteInputStream(),
                SecureWatchXmlParser.DEFAULT_MAX_BYTES,
            )
            val expressions = LinkedHashMap<String, WatchAttr>()
            SOURCES.forEach { expressions[it] = compileWatchAttr(it, SCOPE) }
            expressions += extra
            return WatchPreparedStatic(
                definition = definition,
                svgDocuments = mapOf("icon.svg" to requireNotNull(parseWatchSvgDocument(SVG))),
                bitmapFonts = mapOf("fonts_bm/digits.fnt" to requireNotNull(parseWatchBitmapFont(FNT))),
                expressionScopeDigest = watchPreparedScopeDigest(SCOPE),
                compiledExpressions = expressions,
                initialScriptChunk = WatchFaceLuaRuntime.compileInitialScriptForCache(SCRIPT),
            )
        }

        fun encode(value: WatchPreparedStatic): ByteArray =
            WatchPreparedCodec.encode(ENGINE, INSTALL_KEY, VALIDATION, MANIFEST, value)

        fun roundTrip(value: WatchPreparedStatic): WatchPreparedStatic {
            val bytes = encode(value)
            return WatchPreparedCodec.decodePayload(bytes, WatchPreparedCodec.decodeHeader(bytes))
        }
    }
}
