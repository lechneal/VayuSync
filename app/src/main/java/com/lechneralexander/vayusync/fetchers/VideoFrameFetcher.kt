package com.lechneralexander.vayusync.fetchers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import androidx.core.graphics.drawable.toDrawable
import coil.request.Options
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class VideoFrameFetcher(
    private val context: Context,
    private val uri: Uri
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            // Bail out before the (potentially expensive) decode if we were cancelled.
            currentCoroutineContext().ensureActive()

            val frame = retriever.getFrameAtIndex(5)
                ?: throw IllegalStateException("Could not extract a video frame from $uri")

            return DrawableResult(
                drawable = frame.toDrawable(context.resources),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } finally {
            retriever.release()
        }
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val mimeType = options.context.contentResolver.getType(data) ?: return null

            return if (mimeType.startsWith("video/")) {
                VideoFrameFetcher(options.context, data)
            } else null
        }
    }
}