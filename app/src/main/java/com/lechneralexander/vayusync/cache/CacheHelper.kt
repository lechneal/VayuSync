package com.lechneralexander.vayusync.cache

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CacheHelper {
    companion object {
        suspend fun saveBitmapToCache(context: Context, uri: Uri, bitmap: Bitmap): File? = withContext(
            Dispatchers.IO) {
            try {
                if (!isActive) {
                    return@withContext null
                }

                val cacheDir = File(context.cacheDir, "image_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val key = getDiskCacheKey(uri)
                if (key == null) {
                    Log.w("CacheHelper", "No valid key: $key")
                    return@withContext null
                }

                val file = File(cacheDir, key)
                if (file.exists()) {
                    Log.d("CacheHelper", "File already exists: $file")
                    return@withContext file
                }

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                Log.i("CacheHelper", "Saved bitmap to cache: $file, size = ${file.length()} ${bitmap.height}x${bitmap.width}")
                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun getCachedFile(diskCache: File, imageUri: Uri): File? {
            val key = getDiskCacheKey(imageUri)
            if (key == null) {
                Log.w("CacheHelper", "No valid key: $key")
                return null
            }

            Log.i("CacheHelper", "Getting cached file: $diskCache/$key")

            return File(diskCache, getDiskCacheKey(imageUri))
        }

        fun getThumbnailCacheKey(uri: Uri): String {
            return "thumb_$uri"
        }

        fun getPreviewCacheKey(uri: Uri): String {
            return "prev_$uri"
        }

        fun getFullViewCacheKey(uri: Uri): String {
            return "full_$uri"
        }

        fun getDiskCacheKey(uri: Uri): String? {
            return uri.lastPathSegment?.replace("/", "_")
        }
    }
}