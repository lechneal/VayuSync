package com.lechneralexander.vayusync.copy

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lechneralexander.vayusync.MainActivity
import com.lechneralexander.vayusync.R
import com.lechneralexander.vayusync.VayuApp
import com.lechneralexander.vayusync.activities.ConfirmationActivity
import com.lechneralexander.vayusync.extensions.formatBytes
import com.lechneralexander.vayusync.extensions.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class PersistableCopyState(
    val pendingImagesToCopy: List<ImageToCopy>,
    val isPaused: Boolean
)

class CopyService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val binder = LocalBinder()
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var fileCopier: ContentResolverFileCopier
    private val notificationId = 1 // Unique ID for the notification

    private val _progressFlow = MutableStateFlow(CopyProgress(0, 0, 0, 0, 0.0, false, false))
    val progressFlow = _progressFlow.asStateFlow()

    private val _copiedImageFlow = MutableSharedFlow<ImageToCopy>(replay = 0, extraBufferCapacity = 10)
    val copiedImageFlow = _copiedImageFlow.asSharedFlow()

    private val _activeCopyQueueFlow = MutableStateFlow<List<Uri>>(emptyList())
    val activeCopyQueueFlow = _activeCopyQueueFlow.asStateFlow()

    private val queue = ConcurrentLinkedQueue<ImageToCopy>()
    private var currentlyCopiedFile: ImageToCopy? = null
    private val totalBytes = AtomicLong(0)
    private val copiedBytes = AtomicLong(0)

    private var isCopying = false
    private var isPaused = false
    private var isCancelled = false
    private var startTime = 0L
    private var activeCopyMillis = 0L
    private var lastResumeTime = 0L
    private var lastNotificationUpdateTime = 0L
    private var currentCopiedBytes = 0L

    // SharedPreferences and Gson for state persistence
    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()

    // Companion object for starting/stopping the service
    companion object {
        const val ACTION_START_COPY = "com.lechneralexander.vayusync.action.START_COPY"
        const val ACTION_PAUSE_COPY = "com.lechneralexander.vayusync.action.PAUSE_COPY"
        const val ACTION_RESUME_COPY = "com.lechneralexander.vayusync.action.RESUME_COPY"
        const val ACTION_CANCEL_COPY = "com.lechneralexander.vayusync.action.CANCEL_COPY"
        const val EXTRA_IMAGES_TO_COPY = "com.lechneralexander.vayusync.extra.IMAGES_TO_COPY"

        const val NOTIFICATION_UPDATE_INTERVAL_MS = 500

        // SharedPreferences constants
        private const val PREFS_NAME = "CopyServicePrefs"
        private const val KEY_PERSISTED_STATE = "PersistedCopyState"

        fun startToCopy(context: Context, images: ArrayList<ImageToCopy>) {
            val intent = Intent(context, CopyService::class.java).apply {
                action = ACTION_START_COPY
                putParcelableArrayListExtra(EXTRA_IMAGES_TO_COPY, images)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseCopy(context: Context) {
            val intent = Intent(context, CopyService::class.java).setAction(ACTION_PAUSE_COPY)
            context.startService(intent)
        }

        fun resumeCopy(context: Context) {
            val intent = Intent(context, CopyService::class.java).setAction(ACTION_RESUME_COPY)
            context.startService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, CopyService::class.java).setAction(ACTION_CANCEL_COPY)
            context.startService(intent)
        }

        fun cancelRequest(context: Context) {
            val intent = ConfirmationActivity.newIntent(context, ConfirmationActivity.ACTION_CONFIRM_CANCEL_COPY)
            context.startActivity(intent)
        }
    }


    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        fileCopier = ContentResolverFileCopier(contentResolver)
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        loadStateAndStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COPY -> {
                val images = intent.getParcelableArrayListExtra(EXTRA_IMAGES_TO_COPY, ImageToCopy::class.java)
                enqueueAndStart(images ?: emptyList())
            }
            ACTION_PAUSE_COPY -> pauseCopyInternal()
            ACTION_RESUME_COPY -> resumeCopyInternal()
            ACTION_CANCEL_COPY -> cancelCopyInternal()
        }
        return START_NOT_STICKY
    }

    private fun saveStateToPreferences() {
        val state = PersistableCopyState(getPendingFiles(), isPaused)
        val stateJson = gson.toJson(state)
        sharedPreferences.edit {
            putString(KEY_PERSISTED_STATE, stateJson)
        }
        Log.d("CopyService", "State saved. Queue size: ${state.pendingImagesToCopy.size}, isPaused: $isPaused")
    }

    private fun getPendingFiles(): List<ImageToCopy> {
        return synchronized(queue) {
            listOfNotNull(currentlyCopiedFile) + queue.toList()
        }
    }

    private fun loadStateAndStart() {
        if (isCopying) {
            Log.w("CopyService", "loadStateAndStart called while already copying. Ignoring.")
            return
        }
        val stateJson = sharedPreferences.getString(KEY_PERSISTED_STATE, null)
        if (stateJson == null) {
            Log.w("CopyService", "No saved state found. Ignoring.")
            return
        }

        try {
            val typeToken = object : TypeToken<PersistableCopyState>() {}.type
            val loadedState = gson.fromJson<PersistableCopyState>(stateJson, typeToken)

            if (loadedState != null && loadedState.pendingImagesToCopy.isNotEmpty()) {
                Log.d("CopyService", "State loaded. Queue size: ${queue.size}, isPaused: $isPaused")
                enqueueAndStart(loadedState.pendingImagesToCopy, loadedState.isPaused)
            } else {
                Log.d("CopyService", "Loaded state was null or empty queue, clearing prefs.")
                clearStateFromPreferences() // Clean up if state is invalid or empty
            }
        } catch (e: Exception) {
            Log.e("CopyService", "Error loading state from preferences: ${e.message}", e)
            clearStateFromPreferences() // Clear corrupted state
        }
    }

    private fun clearStateFromPreferences() {
        sharedPreferences.edit { remove(KEY_PERSISTED_STATE) }
        Log.d("CopyService", "Cleared saved state from preferences.")
    }

    private fun enqueueAndStart(images: List<ImageToCopy>, isPaused: Boolean = false) {
        Log.i("CopyService", "enqueueAndStart called with images: ${images.size}, isPaused: $isPaused")
        queue.addAll(images)
        saveStateToPreferences()

        if (isCopying) {
            Log.i("CopyService", "enqueueAndStart called while already copying. Ignoring.")
            totalBytes.addAndGet(images.sumOf { it.fileSize })
            _activeCopyQueueFlow.value = getPendingFiles().map { it.uri.toUri() }
            updateProgressInternal()
        } else {
            startCopyLoopInternal(isPaused)
        }
    }

    private fun startCopyLoopInternal(setPaused: Boolean = false) {
        if (isCopying) {
            Log.w("CopyService", "startCopyLoopInternal called while already copying. Ignoring.")
            return
        }
        Log.i("CopyService", "startCopyLoopInternal called with setPaused: $setPaused")
        isCopying = true
        isCancelled = false
        isPaused = setPaused

        startTime = System.currentTimeMillis()
        lastResumeTime = startTime
        activeCopyMillis = 0L
        currentCopiedBytes = 0L

        totalBytes.set(queue.sumOf {it.fileSize })
        copiedBytes.set(0)
        _progressFlow.value = CopyProgress(0, totalBytes.get(), 0, 0, 0.0, setPaused, completed = false)
        _activeCopyQueueFlow.value = ArrayList(queue.map { it.uri.toUri() })
        createInitialNotification()

        serviceScope.launch {
            while (isCopying) {
                currentlyCopiedFile = queue.poll() ?: break
                Log.i("CopyService", "Copying file: ${currentlyCopiedFile!!.uri}")
                try {
                    fileCopier.copy(
                        currentlyCopiedFile!!,
                        {
                            currentCopiedBytes = it
                            updateProgressDebounced()
                        },
                        { isPaused },
                        { isCancelled }
                    )
                } catch (_: CancellationException) {
                    Log.i("CopyService", "Copy cancelled")
                    // This is expected if the serviceJob or this specific coroutine is cancelled
                    break
                } catch (e: Exception) {
                    Log.e("CopyService", "Error during copy: ${e.message}", e)
                    throw e
                }
                copiedBytes.addAndGet(currentlyCopiedFile!!.fileSize)
                _copiedImageFlow.emit(currentlyCopiedFile!!)
                _activeCopyQueueFlow.value = ArrayList(queue.map { it.uri.toUri() })
                saveStateToPreferences()
            }
            Log.i("CopyService", "Copy loop finished")
            isCopying = false
            _progressFlow.value = _progressFlow.value.copy(completed = true)

            teardown()
        }
    }

    private fun teardown() {
        Log.i("CopyService", "teardown called")
        notificationManager.cancel(notificationId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        clearStateFromPreferences()
    }

    private fun createInitialNotification() {
        if (checkNotificationPermission()) {
            startForeground(notificationId,createNotification(_progressFlow.value))
        } else {
            Log.w("CopyService", "POST_NOTIFICATIONS permission not granted. Cannot update notification progress.")
        }
    }

    private fun updateProgressDebounced() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdateTime < NOTIFICATION_UPDATE_INTERVAL_MS) {
            return
        }
        lastNotificationUpdateTime = currentTime
        updateProgressInternal()
    }

    private fun updateProgressInternal() {
        _progressFlow.value = calculateCurrentProgress(currentCopiedBytes)
        if (checkNotificationPermission()) {
            notificationManager.notify(notificationId, createNotification(_progressFlow.value))
        } else {
            Log.w("CopyService", "POST_NOTIFICATIONS permission not granted. Cannot update notification progress.")
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun calculateCurrentProgress(copiedCurrentFile: Long): CopyProgress {
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

        return CopyProgress(totalBytesCopied, totalBytes.get(), elapsedSec.toInt(),eta, speed,isPaused, false)
    }


    private fun createNotification(currentProgress: CopyProgress? = null): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Opens app
        val pendingIntent = PendingIntent.getActivity(this, 1, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, VayuApp.COPY_NOTIFICATION_CHANNEL_ID) // Create channel first
            .setSmallIcon(R.drawable.ic_notification) // Replace with your icon
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (currentProgress != null) {
            builder.setContentTitle(
                if (currentProgress.etaSeconds > 0) "${currentProgress.etaSeconds.formatDuration()} remaining"
                else "Calculating..."
            )
            builder.setContentText("${queue.size+1} remaining files\n${currentProgress.copiedBytes.formatBytes()} / ${currentProgress.totalBytes.formatBytes()} (${
                currentProgress.speed.toLong().formatBytes()
            }/s)")
            builder.setProgress(currentProgress.totalBytes.toInt(), currentProgress.copiedBytes.toInt(), false)
            // Add Pause/Resume Action
            if (currentProgress.paused) {
                val resumeIntent = Intent(this, CopyService::class.java).setAction(ACTION_RESUME_COPY)
                val resumePendingIntent = PendingIntent.getService(this, 2, resumeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(R.drawable.ic_resume, "Resume", resumePendingIntent)
            } else {
                val pauseIntent = Intent(this, CopyService::class.java).setAction(ACTION_PAUSE_COPY)
                val pausePendingIntent = PendingIntent.getService(this, 3, pauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(R.drawable.ic_pause, "Pause", pausePendingIntent)
            }
        }
        // Add Cancel Action
        val cancelIntent = ConfirmationActivity.newIntent(this, ConfirmationActivity.ACTION_CONFIRM_CANCEL_COPY)
        val cancelPendingIntent = PendingIntent.getActivity(this, 4, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.addAction(R.drawable.ic_cancel, "Cancel", cancelPendingIntent)

        return builder.build()
    }

    fun pauseCopyInternal() {
        if (!isPaused) {
            activeCopyMillis += (System.currentTimeMillis() - lastResumeTime)
            isPaused = true
        }
        _progressFlow.value = _progressFlow.value.copy(paused = isPaused)
        updateProgressInternal()
        saveStateToPreferences()
    }

    fun resumeCopyInternal() {
        if (isPaused) {
            lastResumeTime = System.currentTimeMillis()
            isPaused = false
        }
        _progressFlow.value = _progressFlow.value.copy(paused = isPaused)
        updateProgressInternal()
        saveStateToPreferences()
    }

    fun cancelCopyInternal() {
        isCancelled = true
        isCopying = false
        isPaused = false

        queue.clear()
        _activeCopyQueueFlow.value = emptyList()
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): CopyService = this@CopyService
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
