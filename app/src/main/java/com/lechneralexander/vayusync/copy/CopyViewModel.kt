package com.lechneralexander.vayusync.copy

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

data class CopyProgress(
    val copiedBytes: Long,
    val totalBytes: Long,
    val elapsedSeconds: Int,
    val etaSeconds: Int,
    val speed: Double,
    val paused: Boolean,
    val completed: Boolean,
)
@Parcelize
data class ImageToCopy(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val destinationFolder: String
) : Parcelable

class CopyViewModel(
    private val application: Application
) : AndroidViewModel(application) {
    private val _progress = MutableStateFlow(CopyProgress(0, 0, 0, 0, 0.0, false, false))
    val progress = _progress.asStateFlow()

    private val _copiedImage = MutableSharedFlow<ImageToCopy>(extraBufferCapacity = 10)
    val copiedImage = _copiedImage.asSharedFlow()

    private val _activeCopyQueue = MutableStateFlow<List<Uri>>(emptyList()) // Or List<Uri>
    val activeCopyQueue = _activeCopyQueue.asStateFlow()

    private var copyService: CopyService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d("CopyViewModel", "Service Connected") // <--- ADD THIS LOG
            val binder = service as CopyService.LocalBinder
            copyService = binder.getService()
            isBound = true
            viewModelScope.launch {
                copyService?.progressFlow?.collect {
                    _progress.value = it
                }
            }
            viewModelScope.launch {
                copyService?.copiedImageFlow?.collect {
                    _copiedImage.tryEmit(it)
                }
            }
            viewModelScope.launch {
                copyService?.activeCopyQueueFlow?.collect {
                    _activeCopyQueue.value = it
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d("CopyViewModel", "Service Disconnected") // <--- ADD THIS LOG
            isBound = false
            copyService = null
        }
    }

    init {
        bindToService()
    }

    private fun bindToService() {
        if (!isBound && copyService == null) {
            Log.d("CopyViewModel", "Attempting to bind to CopyService")
            Intent(application, CopyService::class.java).also { intent ->
                application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            application.unbindService(serviceConnection)
        }
    }
}
