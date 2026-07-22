package com.lechneralexander.vayusync.cache

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class CacheHelper {
    companion object {
        private val cachedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var indexLoaded = false

        suspend fun loadCacheIndex(diskCache: File) = withContext(Dispatchers.IO) {
            if (indexLoaded) return@withContext
            diskCache.list()?.let(cachedKeys::addAll)
            indexLoaded = true
            Log.d("CacheHelper", "Disk-cache index loaded: ${cachedKeys.size} entries")
        }

        // IO-free check
        fun isCached(uri: Uri): Boolean {
            val key = getDiskCacheKey(uri) ?: return false
            return cachedKeys.contains(key)
        }

        fun invalidateCached(uri: Uri) {
            getDiskCacheKey(uri)?.let { cachedKeys.remove(it) }
        }

        suspend fun saveBitmapToCache(
            context: Context,
            uri: Uri,
            bitmap: Bitmap,
            dispatcher: CoroutineDispatcher = Dispatchers.IO
        ): File? = withContext(dispatcher) {
            val key = getDiskCacheKey(uri)
            if (key == null) {
                Log.w("CacheHelper", "No valid key for uri: $uri")
                return@withContext null
            }

            val cacheDir = File(context.cacheDir, "image_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val file = File(cacheDir, key)
            if (file.exists()) {
                Log.d("CacheHelper", "File already exists: $file")
                cachedKeys.add(key)
                return@withContext file
            }

            val tmpFile = File(cacheDir, "$key.tmp")
            try {
                FileOutputStream(tmpFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (!isActive || !tmpFile.renameTo(file)) {
                    tmpFile.delete()
                    return@withContext null
                }
                cachedKeys.add(key)
                Log.i("CacheHelper", "Saved bitmap to cache: $file, size = ${file.length()} ${bitmap.height}x${bitmap.width}")
                file
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
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