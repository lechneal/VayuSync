package com.lechneralexander.vayusync.copy

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tests the copy loop of ContentResolverFileCopier: correct byte transfer, progress
 * reporting, pause handling and mid-copy cancellation (which must delete the partial
 * destination and throw). Robolectric provides real Uri parsing; the ContentResolver
 * and the static DocumentsContract calls are mocked with MockK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentResolverFileCopierTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var output: ByteArrayOutputStream
    private val destinationUri: Uri = Uri.parse("content://dest/document/new.jpg")

    private fun image() = ImageToCopy(
        uri = "content://src/document/photo.jpg",
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        fileSize = 0,
        destinationFolder = "content://dest/tree/primary"
    )

    private fun setUpStreams(sourceBytes: ByteArray) {
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(sourceBytes)
        output = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(destinationUri) } returns output
    }

    @Before
    fun setUp() {
        contentResolver = mockk(relaxed = true)
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "primary:"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            Uri.parse("content://dest/tree/primary/document/primary%3A")
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns destinationUri
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun copy_transfersAllBytes_andReportsCumulativeProgress() = runBlocking {
        val data = ByteArray(200_000) { (it % 251).toByte() } // > 64KB buffer -> multiple reads
        setUpStreams(data)
        val copier = ContentResolverFileCopier(contentResolver)
        val progress = mutableListOf<Long>()

        copier.copy(image(), onProgress = { progress.add(it) }, shouldPause = { false }, shouldCancel = { false })

        assertThat(output.toByteArray()).isEqualTo(data)
        assertThat(progress).isNotEmpty()
        assertThat(progress.last()).isEqualTo(200_000L)
        assertThat(progress).isInStrictOrder() // cumulative, monotonically increasing
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun copy_resumesAfterPause() = runBlocking {
        val data = ByteArray(100_000) { it.toByte() }
        setUpStreams(data)
        val copier = ContentResolverFileCopier(contentResolver)
        var pauseChecks = 0
        // report "paused" for the first few polls, then resume
        val shouldPause = { pauseChecks++ < 3 }

        copier.copy(image(), onProgress = {}, shouldPause = shouldPause, shouldCancel = { false })

        assertThat(output.toByteArray()).isEqualTo(data)
        assertThat(pauseChecks).isAtLeast(3)
    }

    @Test
    fun copy_cancelMidway_deletesDestinationAndThrows() = runBlocking {
        val data = ByteArray(200_000) { it.toByte() }
        setUpStreams(data)
        val copier = ContentResolverFileCopier(contentResolver)
        var cancelChecks = 0
        // allow the first chunk, cancel on the second
        val shouldCancel = { cancelChecks++ >= 1 }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                copier.copy(image(), onProgress = {}, shouldPause = { false }, shouldCancel = shouldCancel)
            }
        }

        // partial data was written before the cancel, then destination removed
        assertThat(output.size()).isEqualTo(64 * 1024)
        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), destinationUri) }
    }
}
