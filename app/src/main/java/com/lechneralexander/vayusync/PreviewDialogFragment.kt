package com.lechneralexander.vayusync

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
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.github.chrisbanes.photoview.OnSingleFlingListener
import com.github.chrisbanes.photoview.PhotoView
import com.lechneralexander.vayusync.cache.CacheHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewDialogFragment : DialogFragment() {
    private var currentPosition: Int = 0
    private lateinit var imageUris: List<Uri>
    private lateinit var gestureDetector: GestureDetector // For VideoView gestures

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

        // get arguments
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

        // Fallback dismiss listener for the background
        view.setOnClickListener { dismiss() }
    }

    private fun loadFile(uri: Uri) {
        val context = context ?: return
        val currentView = view ?: return

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

        Log.d("Preview", "Loading file: $uri, mime: $mime")

        if (mime?.startsWith("video/") == true) {
            videoView.setVideoURI(uri)
            videoView.visibility = View.VISIBLE

            // Delay start until prepared, or add prepared listener
            videoView.setOnPreparedListener { mp ->
                mp.start()
            }

            val mediaController = MediaController(requireContext())
            mediaController.setAnchorView(videoView)
            videoView.setMediaController(mediaController)

            videoView.setOnTouchListener { _, event ->
                // Allow MediaController to process taps, but also let our gesture detector check for flings.
                // gestureDetector.onTouchEvent will return true if its onFling (or other listener) consumed the event.
                gestureDetector.onTouchEvent(event)
            }
        } else {
            Log.d("Preview", "Loading image: $uri, mime: $mime")
            val imageLoader = (requireContext().applicationContext as VayuApp).getImageLoader()

            imageView.load(uri, imageLoader) {
                memoryCacheKey(CacheHelper.getFullViewCacheKey(uri))
                placeholderMemoryCacheKey(CacheHelper.getPreviewCacheKey(uri))
                placeholder(R.drawable.ic_image_loading)
                error(R.drawable.ic_image_load_error)
                crossfade(true)
            }

            imageView.visibility = View.VISIBLE
            imageView.setOnViewTapListener { _, _, _ -> dismiss() }
            imageView.setOnSingleFlingListener(createOnSingleFlingListener(imageView, this::navigateToPreviousImage, this::navigateToNextImage))
        }

        // Load EXIF info asynchronously
        lifecycleScope.launch {
            val exifInfoString = loadExifInfo(uri) // New async function
            exifView.text = exifInfoString ?: "No EXIF information!"
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
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME)
                val focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED)
                val exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, -1)
                val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, -1)
                val sizeBytes = context?.contentResolver?.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    afd.length
                } ?: -1L
                val lensMake = exif.getAttribute(ExifInterface.TAG_LENS_MAKE)
                val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                val shutterSpeed = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)

                val exifInfo = buildString {
                    listOfNotNull(
                        if (make != null || model != null) "Camera: $make $model" else null,
                        focalLength?.let { "Focal Length: $it" },
                        iso?.let { "ISO: $it" },
                        exposureTime?.let { "Exposure: $it s" },
                        datetime?.let { "Taken: $it" },
                        if (width > 0 && height > 0) "Resolution: ${width}x${height}" else null,
                        if (sizeBytes > 0) "File size: ${sizeBytes / 1024} KB" else null,
                        lensMake?.let { "Lens make: $it" },
                        aperture?.let { "Aperture: f/$it" },
                        shutterSpeed?.let { "Shutter speed: $it s" }
                    ).forEach { appendLine(it) }
                }
                if (exifInfo.isNotEmpty()) {
                    return@withContext exifInfo
                }
            }
        } catch (e: Exception) {
            Log.e("PreviewDialogFragment", "Error loading EXIF for $uri", e)
            return@withContext "Error loading EXIF"
        }
        return@withContext null
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
