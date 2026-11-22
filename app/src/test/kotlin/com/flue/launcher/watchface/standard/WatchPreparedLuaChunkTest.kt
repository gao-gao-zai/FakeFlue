package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R36：初始脚本的 LuaJ 编译产物可以来自私有准备缓存，但只跳过编译——每个运行时仍是全新的
 * Globals、白名单、指令预算与字符串预算，脚本仍在里面执行一遍。
 */
class WatchPreparedLuaChunkTest {

    private val tags: (String) -> WatchTagResolution = { tag ->
        when (tag) {
            "dh24" -> WatchTagResolution.Resolved("13")
            "bl" -> WatchTagResolution.Resolved("80")
            else -> WatchTagResolution.Unsupported(tag)
        }
    }

    @Test
    fun cachedChunkProducesTheSameInitialStateAndBehaviourAsSourceCompilation() {
        val scripts = listOf(
            sample("watchface-samples/nexus-2.2-script.lua"),
            sample("watchface-samples/flue-watch-script.lua"),
            """
                var_n = 2^31
                var_m = -2147483648
                var_f = 0.1 + 0.2
                var_s = 'x' .. {bl} .. '中'
                var_t = { 1, 2, a = 'b' }
                var_h = {dh24} * 2
                function var_fn(v) return v * var_n end
            """.trimIndent(),
        )
        val probes = listOf(
            "var_n", "var_m", "var_f", "var_s", "var_h", "var_t.a", "#var_t", "var_fn(2)",
            "type(var_fn)", "tostring(var_f)", "var_frame", "var_TP and #var_TP",
        )
        scripts.forEach { script ->
            val chunk = WatchFaceLuaRuntime.compileInitialScriptForCache(script)
            assertNotNull(chunk)
            WatchFaceLuaRuntime.create(script, tags).use { source ->
                WatchFaceLuaRuntime.create(script, tags, cachedInitialScriptChunk = chunk).use { cached ->
                    probes.forEach { probe ->
                        assertEquals(probe, source.evaluateText(probe, tags), cached.evaluateText(probe, tags))
                    }
                    for (step in listOf(5.0, 5.1, 8.0, 9.5)) {
                        assertEquals(source.stepMilliseconds(step).isSuccess, cached.stepMilliseconds(step).isSuccess)
                    }
                    probes.forEach { probe ->
                        assertEquals(probe, source.evaluateText(probe, tags), cached.evaluateText(probe, tags))
                    }
                    assertEquals(source.needsFrame(), cached.needsFrame())
                }
            }
        }
    }

    @Test
    fun runtimeErrorsKeepTheirSourcePositions() {
        val script = "var_a = 1\nvar_b = 2\nerror('boom')\n"
        val chunk = WatchFaceLuaRuntime.compileInitialScriptForCache(script)
        assertNotNull(chunk)
        val expected = failureMessage { WatchFaceLuaRuntime.create(script) }
        val actual = failureMessage { WatchFaceLuaRuntime.create(script, cachedInitialScriptChunk = chunk) }
        assertTrue(expected, expected.contains("watch_script:3"))
        assertEquals(expected, actual)
    }

    @Test
    fun chunkForAnotherSourceOrGarbageFallsBackToSourceCompilation() {
        val chunk = WatchFaceLuaRuntime.compileInitialScriptForCache("var_value = 1")
        WatchFaceLuaRuntime.create("var_value = 2", cachedInitialScriptChunk = chunk).use { runtime ->
            assertEquals(2.0, runtime.evaluateNumber("var_value"), 0.0)
        }
        val garbage = ByteArray(64) { it.toByte() }
        WatchFaceLuaRuntime.create("var_value = 3", cachedInitialScriptChunk = garbage).use { runtime ->
            assertEquals(3.0, runtime.evaluateNumber("var_value"), 0.0)
        }
        val truncated = WatchFaceLuaRuntime.compileInitialScriptForCache("var_value = 4")!!.let {
            it.copyOf(it.size - 5)
        }
        WatchFaceLuaRuntime.create("var_value = 4", cachedInitialScriptChunk = truncated).use { runtime ->
            assertEquals(4.0, runtime.evaluateNumber("var_value"), 0.0)
        }
        assertNull(WatchFaceLuaRuntime.compileInitialScriptForCache(null))
        assertNull(WatchFaceLuaRuntime.compileInitialScriptForCache("   "))
        assertNull(WatchFaceLuaRuntime.compileInitialScriptForCache("var_x = = 1"))
    }

    @Test
    fun instructionLimitStillTripsOnACachedChunk() {
        val script = "var_before = 1\nwhile true do end\n"
        val chunk = WatchFaceLuaRuntime.compileInitialScriptForCache(script)
        assertNotNull(chunk)
        val message = failureMessage { WatchFaceLuaRuntime.create(script, cachedInitialScriptChunk = chunk) }
        assertTrue(message, message.contains("instruction limit"))
        val swallowed = "pcall(function() while true do end end)\nvar_after = 1\n"
        val swallowedMessage = failureMessage {
            WatchFaceLuaRuntime.create(
                swallowed,
                cachedInitialScriptChunk = WatchFaceLuaRuntime.compileInitialScriptForCache(swallowed),
            )
        }
        assertTrue(swallowedMessage, swallowedMessage.contains("instruction limit"))
    }

    @Test
    fun stringAllocationBudgetStillTripsOnACachedChunk() {
        val script = "var_large = string.rep('abcd', 2000000)"
        val message = failureMessage {
            WatchFaceLuaRuntime.create(
                script,
                cachedInitialScriptChunk = WatchFaceLuaRuntime.compileInitialScriptForCache(script),
            )
        }
        assertTrue(message, message.contains("string result limit"))
    }

    @Test
    fun runtimesSharingOneChunkNeverShareGlobalsClosuresOrLibraries() {
        val script = """
            var_count = 0
            function bump() var_count = var_count + 1 return var_count end
        """.trimIndent()
        val chunk = WatchFaceLuaRuntime.compileInitialScriptForCache(script)
        WatchFaceLuaRuntime.create(script, cachedInitialScriptChunk = chunk).use { first ->
            WatchFaceLuaRuntime.create(script, cachedInitialScriptChunk = chunk).use { second ->
                assertTrue(first.runSnippet("bump(); bump(); math.floor = nil; var_extra = 9").isSuccess)
                assertEquals(2.0, first.evaluateNumber("var_count"), 0.0)
                assertEquals(0.0, second.evaluateNumber("var_count"), 0.0)
                // evaluateNumber 会为探测纯度连算两次，这里用 runSnippet 只执行一次。
                assertTrue(second.runSnippet("bump()").isSuccess)
                assertEquals(1.0, second.evaluateNumber("var_count"), 0.0)
                assertEquals(2.0, first.evaluateNumber("var_count"), 0.0)
                assertNull(first.evaluateText("math.floor"))
                assertEquals("function", second.evaluateText("type(math.floor)"))
                assertNull(second.evaluateText("var_extra"))
                for (name in listOf("io", "package", "require", "debug", "load", "loadstring", "dofile", "luajava")) {
                    assertNull(name, second.globalType(name))
                }
            }
        }
    }

    private fun failureMessage(block: () -> WatchFaceLuaRuntime): String {
        try {
            block().close()
        } catch (error: Throwable) {
            return generateSequence(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        }
        fail("expected the initial script to fail")
        return ""
    }

    private fun sample(relativePath: String): String =
        checkNotNull(javaClass.classLoader?.getResource(relativePath)) {
            "missing watch-face sample resource: $relativePath"
        }.readText(Charsets.UTF_8)
}
