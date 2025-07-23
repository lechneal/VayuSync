package com.lechneralexander.vayusync

import android.app.Application
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.util.DebugLogger
import com.lechneralexander.vayusync.fetchers.ThumbnailFetcher
import com.lechneralexander.vayusync.fetchers.VideoFrameFetcher
import java.io.File

class VayuApp: Application() {
    private val diskCacheName = "image_cache"
    private lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()

        imageLoader = ImageLoader.Builder(this)
            .logger(DebugLogger())
            .components {
                add(VideoFrameFetcher.Factory())
                add(ThumbnailFetcher.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of available memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(this.cacheDir, diskCacheName))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            .build()
    }

    fun getImageLoader(): ImageLoader {
        return imageLoader
    }

    fun getDiskCache(): File {
        return File(this.cacheDir, diskCacheName)
    }
}