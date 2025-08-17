package com.lechneralexander.vayusync

import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.github.chrisbanes.photoview.PhotoView
import com.lechneralexander.vayusync.cache.CacheHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewDialogFragment : DialogFragment() {

    companion object {
        fun newInstance(uri: Uri): PreviewDialogFragment {
            val fragment = PreviewDialogFragment()
            val args = Bundle()
            args.putParcelable("uri", uri)
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
        val view = inflater.inflate(R.layout.fragment_preview, container, false)
        val uri = arguments?.getParcelable("uri", Uri::class.java) ?: return view

        val mime = context?.contentResolver?.getType(uri)
        if (mime?.startsWith("video/") == true) {
            val playerView = view.findViewById<VideoView>(R.id.fullVideoView)
            playerView.setVideoURI(uri)
            playerView.start()
            playerView.visibility = View.VISIBLE

            val mediaController = MediaController(requireContext())
            mediaController.setAnchorView(playerView)
            mediaController.visibility = View.VISIBLE
            playerView.setMediaController(mediaController)
        } else {
            Log.d("Preview", "Loading image: $uri, mime: $mime")
            val imageView = view.findViewById<PhotoView>(R.id.fullImageView)
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
        }

        val exifView = view.findViewById<TextView>(R.id.fullExifInfo)
        // Load EXIF info asynchronously
        lifecycleScope.launch {
            val exifInfoString = loadExifInfo(uri) // New async function
            exifView.text = exifInfoString ?: "No EXIF information!"
        }

        view.setOnClickListener { dismiss() }

        return view
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
