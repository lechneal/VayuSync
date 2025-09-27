package com.lechneralexander.vayusync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.util.DebugLogger
import com.lechneralexander.vayusync.fetchers.ThumbnailFetcher
import com.lechneralexander.vayusync.fetchers.VideoFrameFetcher
import java.io.File

class VayuApp: Application() {
    companion object {
        const val COPY_NOTIFICATION_CHANNEL_ID = "copy_channel_id"
    }

    private val diskCacheName = "image_cache"
    private lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        createCopyNotificationChannel()
        imageLoader = createImageLoader()
    }

    private fun createCopyNotificationChannel() {
        val name = getString(R.string.copy_channel_name) // You'll need to add this string resource
        val descriptionText = getString(R.string.copy_channel_description) // And this one
        val importance = NotificationManager.IMPORTANCE_DEFAULT

        val channel = NotificationChannel(COPY_NOTIFICATION_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }

        // Register the channel with the system
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
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
//            .diskCache {
//                DiskCache.Builder()
//                    .directory(File(this.cacheDir, diskCacheName))
//                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
//                    .build()
//            }
            .build()
    }

    fun getImageLoader(): ImageLoader {
        return imageLoader
    }

    fun getDiskCache(): File {
        return File(this.cacheDir, diskCacheName)
    }
}