package com.flue.launcher.surface.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 公开图标文件（R10）：用户选的图片居中裁成正方形、缩到 [SIZE] 后存成 PNG。
 * 先写临时文件再改名，失败时不留半个文件，也不动旧图标。
 */
object PublicIconStore {
    const val SIZE = 256

    /** 返回新文件名；读不出图片或写入失败返回 null。 */
    fun import(context: Context, uri: Uri, dir: File): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
        val name = "icon_${UUID.randomUUID().toString().replace("-", "").take(16)}.png"
        val temp = File(dir, ".$name.tmp")
        val ok = temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val target = File(dir, name)
        if (ok && temp.renameTo(target)) name else null.also { temp.delete() }
    }.getOrNull()
}
