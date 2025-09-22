package com.lechneralexander.vayusync

import SelectionViewModel
import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import com.github.chrisbanes.photoview.OnSingleFlingListener
import com.github.chrisbanes.photoview.PhotoView
import com.lechneralexander.vayusync.cache.CacheHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import kotlin.math.roundToInt

class PreviewDialogFragment : DialogFragment() {
    private var currentPosition: Int = 0
    private lateinit var imageUris: List<Uri>
    private lateinit var gestureDetector: GestureDetector // For VideoView gestures
    private val preloadDisposables = mutableListOf<Disposable>()

    private lateinit var selectionViewModel: SelectionViewModel

    private lateinit var previewSelectionBadge: ImageView

    companion object {
        private const val SWIPE_THRESHOLD_VELOCITY = 200
        private const val SWIPE_MIN_DISTANCE_FLING = 120

        fun newInstance(uris: List<Uri>, position: Int): PreviewDialogFragment {
            val fragment = PreviewDialogFragment()
            val args = Bundle()
            args.putParcelableArrayList("uris", ArrayList(uris))
            args.putInt("position", position)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.AppTheme_Dialog_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectionViewModel = ViewModelProvider(requireActivity()).get(SelectionViewModel::class.java)
        previewSelectionBadge = view.findViewById(R.id.previewSelectionBadge)

        val allUrisArg = arguments?.getParcelableArrayList("uris", Uri::class.java)
        val currentPositionArg = arguments?.getInt("position", 0) ?: 0
        if (allUrisArg == null || allUrisArg.isEmpty()) {
            Log.e("PreviewDialogFragment", "No URIs provided or list is empty.")
            dismissAllowingStateLoss()
            return
        }

        imageUris = allUrisArg
        currentPosition = if (currentPositionArg >= 0 && currentPositionArg < imageUris.size) {
            currentPositionArg
        } else {
            Log.w("PreviewDialogFragment", "Invalid position $currentPositionArg, defaulting to 0.")
            0
        }

        // Initialize GestureDetector for VideoView
        val videoGestureListener = createOnVideoFlingListener(this::navigateToPreviousImage, this::navigateToNextImage)
        gestureDetector = GestureDetector(requireContext(), videoGestureListener)

        // Load the initial image/video
        loadFile(imageUris[currentPosition])

        // Set OnClickListener for the badge
        previewSelectionBadge.setOnClickListener {
            toggleSelection()
        }

        // Observe selection changes
        selectionViewModel.currentlySelectedUris.observe(viewLifecycleOwner) {
            updateSelection()
        }

        // Fallback dismiss listener for the background
        view.setOnClickListener { dismiss() }
    }

    private fun toggleSelection() {
        val currentUri = imageUris[currentPosition]
        selectionViewModel.toggleAndRecordSelection(currentUri)
    }

    private fun updateSelection() {
        val currentUri = imageUris[currentPosition]
        val isSelected = selectionViewModel.getCurrentlySelectedUris().contains(currentUri)
        when {
            isSelected -> {
                previewSelectionBadge.setImageResource(R.drawable.ic_check_circle)
                previewSelectionBadge.visibility = View.VISIBLE
                previewSelectionBadge.alpha = 1f
            }
            //TODO show copied info
            else -> {
                previewSelectionBadge.setImageResource(R.drawable.ic_check_empty)
                previewSelectionBadge.visibility = View.VISIBLE
                previewSelectionBadge.alpha = 1f
            }
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        cancelPreloadTasks()
        parentFragmentManager.setFragmentResult("preview_closed", Bundle.EMPTY)
    }

    private fun cancelPreloadTasks() {
        preloadDisposables.forEach { it.dispose() }
        preloadDisposables.clear()
        Log.d("Preview", "Cancelled all preload tasks.")
    }

    private fun loadFile(uri: Uri) {
        val context = context ?: return
        val currentView = view ?: return

        // Cancel any ongoing preload tasks before loading new file
        cancelPreloadTasks()

        val mime = context.contentResolver.getType(uri)
        val imageView = currentView.findViewById<PhotoView>(R.id.fullImageView)
        val videoView = currentView.findViewById<VideoView>(R.id.fullVideoView)
        val exifView = currentView.findViewById<TextView>(R.id.fullExifInfo)

        // Clear previous listeners
        imageView.setOnSingleFlingListener(null)
        imageView.setOnViewTapListener(null)
        videoView.setOnTouchListener(null)

        // Reset views
        imageView.visibility = View.GONE
        videoView.visibility = View.GONE
        videoView.stopPlayback()

        if (mime?.startsWith("video/") == true) {
            videoView.setVideoURI(uri)
            videoView.visibility = View.VISIBLE

            val mediaController = MediaController(requireContext())
            mediaController.setAnchorView(videoView)
            mediaController.setPrevNextListeners({navigateToNextImage()}, {navigateToPreviousImage()})
            videoView.setMediaController(mediaController)

            // Delay start until prepared, or add prepared listener
            videoView.setOnPreparedListener { mp ->
                mp.start()
                mediaController.show(0)
            }

            videoView.setOnTouchListener { _, event ->
                val flingConsumed = gestureDetector.onTouchEvent(event)

                // If it was a fling, we're done.
                if (flingConsumed) {
                    return@setOnTouchListener true
                }
                return@setOnTouchListener false
            }
        } else {
            Log.d("Preview", "Loading image: $uri, mime: $mime")
            imageView.load(uri, getImageLoader()) {
                memoryCacheKey(CacheHelper.getFullViewCacheKey(uri))
                placeholderMemoryCacheKey(CacheHelper.getPreviewCacheKey(uri))
                placeholder(R.drawable.ic_image_loading)
                error(R.drawable.ic_image_load_error)
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        preloadAdjacentImages(currentPosition, 3, 1)
                    },
                    onError = { _, result ->
                        Log.e("Preview", "Error loading image $uri: ${result.throwable}")
                    }
                )
            }

            imageView.visibility = View.VISIBLE
            imageView.setOnViewTapListener { _, _, _ -> dismiss() }
            imageView.setOnSingleFlingListener(createOnSingleFlingListener(imageView, this::navigateToPreviousImage, this::navigateToNextImage))
        }

        // Update selection badge when file loads
        updateSelection()

        // Load EXIF info asynchronously
        lifecycleScope.launch {
            val exifInfoString = loadExifInfo(uri)
            exifView.text = exifInfoString ?: "No EXIF information!"
        }
    }

    private fun getImageLoader(): ImageLoader =
        (requireContext().applicationContext as VayuApp).getImageLoader()

    private fun preloadAdjacentImages(currentIndex: Int, countPre: Int, countPost: Int) {
        val context = context ?: return

        // Preload previous images
        for (i in 1..countPre) {
            val prevIndex = currentIndex - i
            if (prevIndex >= 0) {
                val prevUri = imageUris[prevIndex]
                if (context.contentResolver.getType(prevUri)?.startsWith("image/") == true) {
                    val request = ImageRequest.Builder(context)
                        .data(prevUri)
                        .memoryCacheKey(CacheHelper.getFullViewCacheKey(prevUri))
                        .build()
                    val disposable = getImageLoader().enqueue(request)
                    preloadDisposables.add(disposable)
                    Log.d("Preview", "Enqueued preloading for previous image at index $prevIndex: $prevUri")
                }
            }
        }

        // Preload next images
        for (i in 1..countPost) {
            val nextIndex = currentIndex + i
            if (nextIndex < imageUris.size) {
                val nextUri = imageUris[nextIndex]
                if (context.contentResolver.getType(nextUri)?.startsWith("image/") == true) {
                    val request = ImageRequest.Builder(context)
                        .data(nextUri)
                        .memoryCacheKey(CacheHelper.getFullViewCacheKey(nextUri))
                        .build()
                    val disposable = getImageLoader().enqueue(request)
                    preloadDisposables.add(disposable)
                    Log.d("Preview", "Enqueued preloading for next image at index $nextIndex: $nextUri")
                } else {
                    Log.d("Preview", "Skipping preload for next item at index $nextIndex (not an image): $nextUri")
                }
            } else {
                break
            }
        }
    }

    private fun createOnVideoFlingListener(
        onLeft: () -> Unit,
        onRight: () -> Unit
    ) = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true // Necessary for onFling

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            // Check if predominantly horizontal and meets thresholds
            if (Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY &&
                Math.abs(diffX) > SWIPE_MIN_DISTANCE_FLING &&
                Math.abs(velocityX) > Math.abs(velocityY * 1.5) // More horizontal
            ) {
                if (velocityX > 0) {
                    onLeft()
                } else {
                    onRight()
                }
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Let VideoView/MediaController handle its own taps
            return super.onSingleTapUp(e)
        }
    }

    private fun createOnSingleFlingListener(
        imageView: PhotoView,
        onLeft: () -> Unit,
        onRight: () -> Unit
    ) = object : OnSingleFlingListener {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent?,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // Only allow swipe if not zoomed
            if (imageView.scale <= imageView.minimumScale + 0.01f) {
                if (e1 == null || e2 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY &&
                    Math.abs(diffX) > SWIPE_MIN_DISTANCE_FLING &&
                    Math.abs(velocityX) > Math.abs(velocityY * 1.5) // Ensure more horizontal
                ) {
                    if (velocityX > 0) {
                        onLeft()
                    } else {
                        onRight()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun navigateToNextImage() {
        if (currentPosition < imageUris.size - 1) {
            currentPosition++
            loadFile(imageUris[currentPosition])
        } else {
            Log.d("Preview", "Already at the last image.")
        }
    }

    private fun navigateToPreviousImage() {
        if (currentPosition > 0) {
            currentPosition--
            loadFile(imageUris[currentPosition])
        } else {
            Log.d("Preview", "Already at the first image.")
        }
    }

    private suspend fun loadExifInfo(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val stringBuilder = StringBuilder()

                // Device Info (Make and Model)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                if (!make.isNullOrEmpty() && !model.isNullOrEmpty()) {
                    if (model.startsWith(make, ignoreCase = true)) {
                        stringBuilder.append(model).append("\n")
                    } else {
                        stringBuilder.append("$make $model\n")
                    }
                } else if (!make.isNullOrEmpty()) {
                    stringBuilder.append(make).append("\n")
                } else if (!model.isNullOrEmpty()) {
                    stringBuilder.append(model).append("\n")
                }

                val lensMake = exif.getAttribute(ExifInterface.TAG_LENS_MAKE)?.trim()
                val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
                if (!lensMake.isNullOrEmpty() && !lensModel.isNullOrEmpty()) {
                     if (lensModel.startsWith(lensMake, ignoreCase = true)) {
                        stringBuilder.appendLine(lensModel)
                    } else {
                        stringBuilder.appendLine("$lensMake $lensModel")
                    }
                } else if (!lensMake.isNullOrEmpty()) {
                    stringBuilder.appendLine(lensMake)
                } else if (!lensModel.isNullOrEmpty()) {
                    stringBuilder.appendLine(lensModel)
                }

                val focalLengthActualString = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    ?.split("/")
                    ?.mapNotNull { it.toDoubleOrNull() }
                    ?.let { if (it.size == 2 && it[1] != 0.0) it[0] / it[1] else it.firstOrNull() }
                    ?.let { "${it.toInt()}mm" }

                val focalLengthEquivalentString = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                    ?.takeIf { it.isNotEmpty() && it != "0" }
                    ?.let {  "(${it}mm in 35mm equiv.)" }

                if (focalLengthActualString != null && focalLengthEquivalentString != null) {
                    stringBuilder.appendLine("$focalLengthActualString $focalLengthEquivalentString")
                } else if (focalLengthActualString != null) {
                    stringBuilder.appendLine(focalLengthActualString)
                }

                // Other camera Settings ( Aperture, Exposure, ISO)
                val cameraSettings = mutableListOf<String>()
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotEmpty() }?.let {
                    cameraSettings.add("f/$it")
                }

                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
                    val exposureTime = it
                    if (exposureTime < 1.0) {
                        val denominator = (1.0 / exposureTime).roundToInt()
                        if (denominator > 0) { // Avoid 1/0s if exposureTime is extremely small but not zero
                           cameraSettings.add("1/${denominator}s")
                        } else {
                           cameraSettings.add("${DecimalFormat("0.####").format(exposureTime)}s") // Fallback for very small values
                        }
                    } else {
                        cameraSettings.add("${DecimalFormat("0.#").format(exposureTime)}s")
                    }
                }
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED)?.takeIf { it.isNotEmpty() }?.let {
                    cameraSettings.add("ISO $it")
                }

                if (cameraSettings.isNotEmpty()) {
                    stringBuilder.append(cameraSettings.joinToString(" | ")).append("\n")
                }

                // Image Attributes (Dimensions and Size)
                val imageAttributes = mutableListOf<String>()
                val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, -1)
                val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, -1)
                if (width != -1 && height != -1) {
                    imageAttributes.add("${width}x${height}px")
                }
                context?.contentResolver?.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val sizeBytes = afd.length
                    if (sizeBytes != -1L) {
                        imageAttributes.add(android.text.format.Formatter.formatFileSize(context, sizeBytes))
                    }
                }
                if (imageAttributes.isNotEmpty()) {
                    stringBuilder.append(imageAttributes.joinToString(" | ")).append("\n")
                }

                // Date/Time
                val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                val dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)

                (dateTimeOriginal ?: dateTimeDigitized ?: dateTime)?.takeIf { it.isNotEmpty() }?.let {
                    stringBuilder.append(it).append("\n")
                }

                if (stringBuilder.isEmpty()) {
                    return@use null // No EXIF data found or all fields were empty
                }
                return@use stringBuilder.toString().trim()
            }
        } catch (e: Exception) {
            Log.e("PreviewDialogFragment", "Error loading EXIF for $uri", e)
            null
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        parentFragmentManager.setFragmentResult("preview_closed", Bundle())
    }
}
