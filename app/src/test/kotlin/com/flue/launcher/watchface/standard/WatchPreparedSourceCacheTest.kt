package com.flue.launcher.watchface.standard

import android.graphics.Bitmap
import android.graphics.Color
import com.flue.launcher.watchface.standard.prepared.WatchmakerPreparedCache
import com.flue.launcher.watchface.standard.prepared.WatchPreparedCache
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R36 端到端：真实 prepareSource 的未命中、跨实例命中、同大小同时间修改与草稿排除。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchPreparedSourceCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val application get() = RuntimeEnvironment.getApplication()

    @Test
    fun installedFaceRendersIdenticallyOnMissAndCrossInstanceHit() {
        val root = installFace("r36-face", color = "ffffff")
        val cache = requireNotNull(WatchmakerPreparedCache.forInstalledRoot(application, root.canonicalFile))
        val missesBefore = cache.missCount.get()
        val hitsBefore = cache.hitCount.get()
        val published = cache.publishCount.get()

        val miss = render(root)
        assertEquals(missesBefore + 1, cache.missCount.get())
        awaitPublished(cache, published + 1)

        val hit = render(root)
        assertEquals("second View must hit the persistent cache", hitsBefore + 1, cache.hitCount.get())
        assertTrue("hit must render the same pixels as the miss", miss.sameAs(hit))
        assertTrue("the face must actually draw something", hasLitPixel(hit))

        // 同大小、同修改时间的内容改动：清单相同，但内容摘要不同 → 未命中并显示新内容。
        val watchXml = File(root, "watch.xml")
        val modified = watchXml.lastModified()
        val original = watchXml.readText()
        val edited = original.replace("color=\"ffffff\"", "color=\"00ff00\"")
        assertEquals(original.length, edited.length)
        watchXml.writeText(edited)
        assertTrue(watchXml.setLastModified(modified))
        val missesBeforeEdit = cache.missCount.get()
        val afterEdit = render(root)
        assertEquals(missesBeforeEdit + 1, cache.missCount.get())
        assertFalse("edited content must not be served from the stale entry", hit.sameAs(afterEdit))
        awaitPublished(cache, published + 2)
        assertTrue(render(root).sameAs(afterEdit))

        // 删除/替换后失效：条目被移除，下一次加载重新建立。
        WatchmakerPreparedCache.invalidate(application, root)
        assertFalse(cache.entryFile(root.canonicalFile.absolutePath).exists())
        listOf(miss, hit, afterEdit).forEach(Bitmap::recycle)
    }

    @Test
    fun bitmapFontSourcesAreReadLazilyAndAgainForEachIteration() {
        val root = temporaryFolder.newFolder("font-sources")
        val first = File(root, "first.fnt").apply { writeText("first") }
        val second = File(root, "second.fnt").apply { writeText("before") }
        val sources = bitmapFontSources(root, listOf(first, second))

        // Merely obtaining the iterable must not retain either file's source text.
        assertTrue(first.delete())
        second.writeText("after")
        assertEquals(listOf("second.fnt" to "after"), sources.toList())

        second.writeText("latest")
        assertEquals(listOf("second.fnt" to "latest"), sources.toList())
    }

    @Test
    fun bitmapFontSourcesEnforceTheActualReadLimitWhenLengthIsStale() {
        val root = temporaryFolder.newFolder("font-read-limit")
        val oversized = File(root, "oversized.fnt")
        RandomAccessFile(oversized, "rw").use { it.setLength(1024L * 1024L + 1L) }
        val reportedSmall = object : File(oversized.path) {
            override fun length(): Long = 1L
        }
        val valid = File(root, "valid.fnt").apply { writeText("valid font source") }

        assertEquals(
            listOf("valid.fnt"),
            bitmapFontSources(root, listOf(reportedSmall, valid)).map { it.first },
        )
    }

    @Test
    fun bitmapFontContentChangesWithTheSameMetadataInvalidateCachedDefinitions() {
        val root = installFace("r36-font-edit", color = "ffffff")
        val cache = requireNotNull(WatchmakerPreparedCache.forInstalledRoot(application, root.canonicalFile))
        val missesBefore = cache.missCount.get()
        val hitsBefore = cache.hitCount.get()
        val publishedBefore = cache.publishCount.get()
        val bitmaps = ArrayList<Bitmap>()
        try {
            val miss = render(root).also(bitmaps::add)
            assertEquals(missesBefore + 1, cache.missCount.get())
            awaitPublished(cache, publishedBefore + 1)
            val hit = render(root).also(bitmaps::add)
            assertEquals(hitsBefore + 1, cache.hitCount.get())
            assertTrue(miss.sameAs(hit))

            val font = File(root, "fonts_bm/fixture.fnt")
            val modified = font.lastModified()
            val original = font.readText()
            val edited = original.replace("xadvance=26", "xadvance=16")
            assertEquals(original.length, edited.length)
            assertFalse(original == edited)
            font.writeText(edited)
            assertTrue(font.setLastModified(modified))

            val afterEdit = render(root).also(bitmaps::add)
            assertEquals(missesBefore + 2, cache.missCount.get())
            assertFalse("font metrics must update after a same-size, same-time edit", hit.sameAs(afterEdit))
            awaitPublished(cache, publishedBefore + 2)
            val editedHit = render(root).also(bitmaps::add)
            assertEquals(hitsBefore + 2, cache.hitCount.get())
            assertTrue(afterEdit.sameAs(editedHit))
        } finally {
            bitmaps.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun fontSourcesChangedBetweenValidationAndParsingAreNotPublished() {
        val root = installFace("r36-font-read-race", color = "ffffff")
        val font = File(root, "fonts_bm/fixture.fnt")
        val original = font.readText()
        val edited = original.replace("xadvance=26", "xadvance=16")
        val modified = font.lastModified()
        var lengthChecks = 0
        val observedFont = object : File(font.path) {
            override fun length(): Long {
                lengthChecks++
                // Metadata is inspected once for each manifest, then once for each source pass.
                // Change the file just as the second pass is about to read its text.
                if (lengthChecks == 4) {
                    font.writeText(edited)
                    check(font.setLastModified(modified))
                }
                return super.length()
            }
        }
        val observedFontDirectory = object : File(font.parentFile!!.path) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { child ->
                if (child.name == font.name) observedFont else child
            }?.toTypedArray()
        }
        val observedRoot = object : File(root.path) {
            override fun getCanonicalFile(): File = this
            override fun listFiles(): Array<File>? = super.listFiles()?.map { child ->
                if (child.name == observedFontDirectory.name) observedFontDirectory else child
            }?.toTypedArray()
        }
        val previousCache = requireNotNull(WatchmakerPreparedCache.forInstalledRoot(application, root.canonicalFile))
        val cache = WatchPreparedCache(previousCache.cacheDirectory, "read-race-test", Executor { it.run() })
        val shared = WatchmakerPreparedCache::class.java.getDeclaredField("shared").apply { isAccessible = true }
        val bitmaps = ArrayList<Bitmap>()
        shared.set(null, cache)
        try {
            val afterEdit = render(observedRoot).also(bitmaps::add)
            assertTrue("the second read must have changed the font", lengthChecks >= 4)
            assertEquals(edited, font.readText())
            assertEquals("a changed source must not publish under the first pass's digest", 0, cache.publishCount.get())
            assertFalse(cache.entryFile(root.canonicalPath).exists())

            val stable = render(root).also(bitmaps::add)
            assertTrue("the uncached font must still render normally", afterEdit.sameAs(stable))
            assertEquals(1, cache.publishCount.get())

            font.writeText(original)
            assertTrue(font.setLastModified(modified))
            val missesBeforeRestore = cache.missCount.get()
            val restored = render(root).also(bitmaps::add)
            assertEquals(missesBeforeRestore + 1, cache.missCount.get())
            assertFalse("restoring the old font must not load the newer font model", stable.sameAs(restored))
        } finally {
            shared.set(null, previousCache)
            bitmaps.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun editorDraftsAndOtherDirectoriesAreNeverCached() {
        val draft = temporaryFolder.newFolder("draft")
        writeFace(draft, color = "ffffff")
        assertNull(WatchmakerPreparedCache.forInstalledRoot(application, draft.canonicalFile))
        render(draft).recycle()
        val directory = File(application.cacheDir, "watchmaker-prepared")
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".prepared") })
    }

    @Suppress("UNCHECKED_CAST")
    private fun bitmapFontSources(root: File, files: List<File>): Iterable<Pair<String, String>> {
        val view = WatchmakerWatchFaceView(application)
        try {
            val method = view.javaClass.getDeclaredMethod("readBitmapFontSources", File::class.java, List::class.java)
                .apply { isAccessible = true }
            return method.invoke(view, root, files) as Iterable<Pair<String, String>>
        } finally {
            view.dispose()
        }
    }

    private fun awaitPublished(
        cache: com.flue.launcher.watchface.standard.prepared.WatchPreparedCache,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + 15_000
        while (cache.publishCount.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("background write must finish", cache.publishCount.get() >= count)
    }

    private fun installFace(id: String, color: String): File {
        val faces = File(application.filesDir, WatchmakerWatchFaceStorage.ROOT_DIRECTORY)
        val root = File(faces, id)
        root.deleteRecursively()
        root.mkdirs()
        writeFace(root, color)
        return root
    }

    private fun writeFace(root: File, color: String) {
        val fonts = File(root, "fonts_bm").apply { mkdirs() }
        File(root, "scripts").mkdirs()
        File(root, "scripts/script.txt").writeText("var_a = 2\nvar_label = 'R36'\nfunction tick() return 1 end\n")
        File(root, "watch.xml").writeText(
            """
            <Watch name="R36 cache" shape="S" bg_color="000000">
                <Layer type="text" x="0" y="-60" width="400" height="120" font="bm:fixture" text="00"
                    text_size="60" alignment="cc" opacity="var_a * 50" color="$color"/>
                <Layer type="text" x="0" y="80" width="400" height="120" font="bm:fixture" text="0"
                    text_size="40" alignment="cc" opacity="(var_a &gt; 1) and 100 or 0" color="ffffff"/>
            </Watch>
            """.trimIndent(),
        )
        File(root, "icon.svg").writeText("""<svg viewBox="0 0 10 10"><path d="M0 0L10 10Z"/></svg>""")
        File(fonts, "fixture.fnt").writeText(
            """
            info face="fixture" size=40
            common lineHeight=40 base=0 scaleW=24 scaleH=40 pages=1
            page id=0 file="fixture.png"
            chars count=1
            char id=48 x=0 y=0 width=24 height=40 xoffset=0 yoffset=0 xadvance=26 page=0
            """.trimIndent(),
        )
        val atlas = Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888)
        atlas.eraseColor(Color.WHITE)
        File(fonts, "fixture.png").outputStream().use { atlas.compress(Bitmap.CompressFormat.PNG, 100, it) }
        atlas.recycle()
    }

    private fun render(root: File): Bitmap {
        val view = WatchmakerWatchFaceView(application)
        try {
            val prepared = view.javaClass.getDeclaredMethod(
                "prepareSource",
                File::class.java,
                Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(view, root, 0L)
            assertNotNull(prepared)
            view.javaClass.getDeclaredField("source").apply { isAccessible = true }.set(view, prepared)
            runBlocking { view.prewarmBitmapFontAtlases() }
            return requireNotNull(view.renderFaceBitmap(256))
        } finally {
            view.dispose()
        }
    }

    private fun hasLitPixel(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 128 || Color.green(pixel) > 128) return true
        }
        return false
    }
}
