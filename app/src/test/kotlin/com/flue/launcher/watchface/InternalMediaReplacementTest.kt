package com.flue.launcher.watchface

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.flue.launcher.surface.modeMediaFilesDir
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InternalMediaReplacementTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `copying a new video keeps the old file until its settings are committed`() {
        val directory = File(context.modeMediaFilesDir(), "internal_watchfaces/video").apply { mkdirs() }
        val previous = File(directory, "old.mp4").apply { writeText("previous video") }
        val source = File(context.cacheDir, "source.mp4").apply { writeText("replacement video") }

        val path = InternalWatchFaceStorage.copyVideo(context, Uri.fromFile(source))

        assertNotNull(path)
        assertTrue("旧配置尚未提交，原视频必须保留", previous.isFile)
        assertTrue(File(path!!).isFile)
    }

    @Test
    fun `copying a new photo keeps the old file until its settings are committed`() {
        val directory = File(context.modeMediaFilesDir(), "internal_watchfaces/photo").apply { mkdirs() }
        val previous = File(directory, "old.png").apply { writeText("previous photo") }
        val source = File(context.cacheDir, "source.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val path = InternalWatchFaceStorage.copyPhoto(context, Uri.fromFile(source))

        assertNotNull(path)
        assertTrue("旧配置尚未提交，原照片必须保留", previous.isFile)
        assertTrue(File(path!!).isFile)
    }
}
