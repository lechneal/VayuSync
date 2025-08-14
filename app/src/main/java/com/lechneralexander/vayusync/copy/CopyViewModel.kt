package com.lechneralexander.vayusync.copy

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechneralexander.vayusync.ImageInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

data class CopyProgress(
    val copiedBytes: Long,
    val totalBytes: Long,
    val elapsedSeconds: Int,
    val etaSeconds: Int,
    val speed: Double,
    val completed: Boolean
)
data class ImageToCopy(
    val info: ImageInfo,
    val sourceUri: Uri,
    val destinationUri: Uri
)

class CopyViewModel(
    private val fileCopier: FileCopier
) : ViewModel() {
    private val progress = MutableStateFlow(CopyProgress(0, 0, 0, 0, 0.0, false))
    private val copiedImage = MutableSharedFlow<ImageInfo>(extraBufferCapacity = 10)

    private val queue = ConcurrentLinkedQueue<ImageToCopy>()
    private val totalBytes = AtomicLong(0)
    private val copiedBytes = AtomicLong(0)

    @Volatile
    private var isCopying = false

    @Volatile
    private var startTime = 0L

    private var copyJob: Job? = null

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
        startTime = System.currentTimeMillis()
        totalBytes.set(queue.sumOf {it.info.fileSize})
        copiedBytes.set(0)
        progress.value = CopyProgress(0, totalBytes.get(), 0, 0, 0.0, completed = false)

        copyJob = viewModelScope.launch {
            while (true) {
                val image = queue.poll() ?: break
                fileCopier.copy(image.sourceUri, image.destinationUri) { copied ->
                    updateProgress(copiedBytes.get() + copied)
                }
                copiedBytes.addAndGet(image.info.fileSize)
                copiedImage.emit(image.info)
            }
            isCopying = false
            progress.value = progress.value.copy(completed = true)
        }
    }

    private fun updateProgress(copied: Long) {
        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        val speed = if (elapsedSec > 0) copied / elapsedSec else 0.0
        val remainingBytes = totalBytes.get() - copied
        val eta = if (speed > 0) (remainingBytes / speed).roundToInt() else 0

        progress.value = CopyProgress(copied, totalBytes.get(), elapsedSec.toInt(),eta, speed,false)
    }

    fun cancelCopy() {
        //TODO currently copied image
        copyJob?.cancel()
        isCopying = false
        queue.clear()
    }
}
