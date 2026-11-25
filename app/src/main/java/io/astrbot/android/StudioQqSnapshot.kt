package io.astrbot.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Writes only the user's requested frame. Failed pending gallery entries are removed. */
internal object StudioQqSnapshot {
    fun write(context: Context, target: Uri, bitmap: Bitmap) {
        requireNotNull(context.contentResolver.openOutputStream(target, "w")) { "无法打开保存位置" }.use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存登录画面" }
        }
    }
    fun gallery(context: Context, bitmap: Bitmap): Uri {
        check(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "WhaleTalk-QQ-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WhaleTalk")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建相册图片" }
        try {
            write(context, uri, bitmap)
            check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            return uri
        } catch (failure: Exception) { context.contentResolver.delete(uri, null, null); throw failure }
    }
}
