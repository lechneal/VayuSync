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

class VideoFrameFetcher(
    private val context: Context,
    private val uri: Uri
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val frame = retriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST)
        retriever.release()

        return DrawableResult(
            drawable = frame!!.toDrawable(context.resources),
            isSampled = false,
            dataSource = DataSource.DISK
        )
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