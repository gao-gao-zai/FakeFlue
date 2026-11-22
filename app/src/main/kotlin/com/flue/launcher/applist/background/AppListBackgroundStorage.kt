package com.flue.launcher.applist.background

import android.content.Context
import com.flue.launcher.surface.modeMediaFilesDir
import android.net.Uri
import com.flue.launcher.data.StagedFiles
import com.flue.launcher.watchface.editor.WatchEditorImageCropper
import com.flue.launcher.watchface.editor.WatchEditorSourceRect
import java.io.File

/** 裁剪并写入新文件；调用方在 IO 上执行，配置提交后再清理旧文件。 */
internal fun cropAndStoreAppListBackground(
    context: Context,
    uri: Uri,
    sourceRect: WatchEditorSourceRect,
    mediaRoot: File = context.modeMediaFilesDir(),
): String? = runCatching {
    val bitmap = WatchEditorImageCropper.cropRegion(context.contentResolver, uri, sourceRect)
    try {
        val directory = File(mediaRoot, APP_LIST_BACKGROUND_DIRECTORY)
        StagedFiles.writeNew(directory, "bg", "png", write = { output ->
            WatchEditorImageCropper.toPngStream(bitmap).use { input -> input.copyTo(output) }
        }).absolutePath
    } finally {
        bitmap.recycle()
    }
}.getOrNull()

/** 自定义背景图落盘的子目录名。 */
internal const val APP_LIST_BACKGROUND_DIRECTORY = "app_list_background"
