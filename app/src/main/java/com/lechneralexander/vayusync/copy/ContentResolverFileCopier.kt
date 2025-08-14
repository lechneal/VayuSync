package com.lechneralexander.vayusync.copy

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface FileCopier {
    suspend fun copy(sourceUri: Uri, destinationUri: Uri, onProgress: (bytesCopied: Long) -> Unit)
}

class ContentResolverFileCopier(private val contentResolver: ContentResolver) : FileCopier {
    private val buffer = ByteArray(64 * 1024)

    override suspend fun copy(
        sourceUri: Uri,
        destinationUri: Uri,
        onProgress: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(destinationUri)?.use { output ->
                    var read: Int
                    var totalCopied = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalCopied += read
                        onProgress(totalCopied)
                    }
                }
            }
        }
    }
}