package com.lechneralexander.vayusync.cache

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CacheHelper {
    companion object {
        suspend fun saveBitmapToCache(context: Context, uri: Uri, bitmap: Bitmap): File? = withContext(
            Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "image_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val fileName = uri.lastPathSegment?.replace("/", "_") ?: "cache_${System.currentTimeMillis()}.jpg"
                val file = File(cacheDir, fileName)

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }


    }
}