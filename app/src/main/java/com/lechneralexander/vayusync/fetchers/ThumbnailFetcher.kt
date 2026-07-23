package com.lechneralexander.vayusync.fetchers
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

class ThumbnailFetcher(
    private val contentResolver: ContentResolver,
    private val options: Options,
    private val data: Uri
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // Get the target size from the request's parameters.
        val targetSize = options.parameters.value("target_size") as? Int ?: 512

        // Wire a CancellationSignal to coroutine cancellation so the blocking
        // system thumbnail generation is aborted the moment the request is cancelled.
        val signal = CancellationSignal()
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) signal.cancel()
        }

        val bitmap = try {
            // Request a higher-res thumbnail from the system to get better quality.
            contentResolver.loadThumbnail(data, Size(targetSize, targetSize), signal)
        } finally {
            cancellationHandle?.dispose()
        }

        return DrawableResult(
            drawable = bitmap.toDrawable(options.context.resources),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val useThumbnail = options.parameters.value("use_thumbnail") as? Boolean ?: false

            return if (useThumbnail && ContentResolver.SCHEME_CONTENT == data.scheme) {
                ThumbnailFetcher(options.context.contentResolver, options, data)
            } else {
                null // Let Coil fall back to its default fetcher
            }
        }
    }
}