package com.lechneralexander.vayusync.copy

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.lechneralexander.vayusync.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

interface FileCopier {
    suspend fun copy(
        image: FileInfo,
        destinationFolder: Uri,
        onProgress: (bytesCopied: Long) -> Unit,
        shouldPause: () -> Boolean,
        shouldCancel: () -> Boolean
    )
}

class ContentResolverFileCopier(
    private val contentResolver: ContentResolver
) : FileCopier {
    private val buffer = ByteArray(64 * 1024)

    override suspend fun copy(
        image: FileInfo,
        destinationFolder: Uri,
        onProgress: (Long) -> Unit,
        shouldPause: () -> Boolean,
        shouldCancel: () -> Boolean
    ) {
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(image.uri)?.use { input ->
                val destinationUri = getDestinationUri(image, destinationFolder)!!
                contentResolver.openOutputStream(destinationUri)?.use { output ->
                    var read: Int
                    var totalCopied = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        // Handle pause
                        while (shouldPause()) {
                            delay(100)
                        }

                        // Handle cancel — stop immediately
                        if (shouldCancel()) {
                            output.close()
                            break
                        }

                        output.write(buffer, 0, read)
                        totalCopied += read
                        onProgress(totalCopied)
                    }
                }

                if (shouldCancel()) {
                    DocumentsContract.deleteDocument(contentResolver, destinationUri)
                    throw CancellationException("Copy cancelled")
                }
            }
        }
    }

    private fun getDestinationUri(image: FileInfo, destinationTreeUri: Uri): Uri? {
        val docId = DocumentsContract.getTreeDocumentId(destinationTreeUri)
        val destinationFolderDocUri = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, docId)
        val fileName = image.fileName ?: "file_${System.currentTimeMillis()}"

        // Now, use the corrected Document URI when creating the new file.
        return DocumentsContract.createDocument(
            contentResolver,
            destinationFolderDocUri, // Use the Document URI here
            image.mimeType,
            fileName
        )
    }
}