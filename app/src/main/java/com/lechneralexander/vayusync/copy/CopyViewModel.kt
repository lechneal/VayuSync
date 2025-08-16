package com.lechneralexander.vayusync.copy

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechneralexander.vayusync.FileInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class CopyProgress(
    val copiedBytes: Long,
    val totalBytes: Long,
    val elapsedSeconds: Int,
    val etaSeconds: Int,
    val speed: Double,
    val paused: Boolean,
    val completed: Boolean,
)
data class ImageToCopy(
    val info: FileInfo,
    val destinationFolder: Uri
)

class CopyViewModel(
    private val fileCopier: FileCopier
) : ViewModel() {
    private val progress = MutableStateFlow(CopyProgress(0, 0, 0, 0, 0.0, false, false))
    private val copiedImage = MutableSharedFlow<FileInfo>(extraBufferCapacity = 10)

    private val queue = ConcurrentLinkedQueue<ImageToCopy>()
    private val totalBytes = AtomicLong(0)
    private val copiedBytes = AtomicLong(0)

    @Volatile
    private var isCopying = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var isCancelled = false

    @Volatile
    private var startTime = 0L

    @Volatile
    private var activeCopyMillis = 0L

    @Volatile
    private var lastResumeTime = 0L

    fun enqueueFiles(images: List<ImageToCopy>) {
        images.forEach(queue::offer)
        totalBytes.addAndGet(images.sumOf { it.info.fileSize })

        if (!isCopying) {
            startCopyLoop()
        }
    }

    fun getProgress() = progress.asStateFlow()

    fun getCopiedImage() = copiedImage.asSharedFlow()

    private fun startCopyLoop() {
        isCopying = true
        isCancelled = false
        isPaused = false

        startTime = System.currentTimeMillis()
        lastResumeTime = startTime
        activeCopyMillis = 0L

        totalBytes.set(queue.sumOf {it.info.fileSize})
        copiedBytes.set(0)
        progress.value = CopyProgress(0, totalBytes.get(), 0, 0, 0.0, false, completed = false)

        viewModelScope.launch {
            while (isCopying) {
                val image = queue.poll() ?: break
                try {
                    fileCopier.copy(
                        image.info,
                        image.destinationFolder,
                        this@CopyViewModel::updateProgress,
                        { isPaused },
                        { isCancelled }
                    )
                } catch (_: CancellationException) {
                    return@launch
                }
                copiedBytes.addAndGet(image.info.fileSize)
                copiedImage.emit(image.info)
            }
            isCopying = false
            progress.value = progress.value.copy(completed = true)
        }
    }

    private fun updateProgress(copiedCurrentFile: Long) {
        val totalBytesCopied = copiedBytes.get() + copiedCurrentFile

        val activeMillis = if (!isPaused) {
            activeCopyMillis + (System.currentTimeMillis() - lastResumeTime)
        } else {
            activeCopyMillis
        }

        val elapsedSec = activeMillis / 1000.0
        val speed = if (elapsedSec > 0) totalBytesCopied / elapsedSec else 0.0
        val remainingBytes = totalBytes.get() - totalBytesCopied
        val eta = if (speed > 0) (remainingBytes / speed).roundToInt() else 0

        progress.value = CopyProgress(totalBytesCopied, totalBytes.get(), elapsedSec.toInt(),eta, speed,isPaused, false)
    }

    fun pauseCopy(): Unit {
        if (!isPaused) {
            activeCopyMillis += (System.currentTimeMillis() - lastResumeTime)
            isPaused = true
        }
        progress.value = progress.value.copy(paused = isPaused)
    }

    fun resumeCopy() {
        if (isPaused) {
            lastResumeTime = System.currentTimeMillis()
            isPaused = false
        }
        progress.value = progress.value.copy(paused = isPaused)
    }

    fun cancelCopy() {
        isCancelled = true
        isCopying = false
        isPaused = false

        queue.clear()
    }
}
