package com.lechneralexander.vayusync

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import coil.load
import coil.request.ImageRequest
import com.github.chrisbanes.photoview.PhotoView

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
        } else {
            Log.d("Preview", "Loading image: $uri, mime: $mime")
            val imageView = view.findViewById<PhotoView>(R.id.fullImageView)
            val imageLoader = (requireContext().applicationContext as VayuApp).getImageLoader()

            //TODO consolidate cache key
            //TODO use separate cache key for mem cache
            val highResRequest = ImageRequest.Builder(requireContext())
                .data(uri)
                .memoryCacheKey("prev_$uri")
                .build()
            val cachedHighResDrawable = imageLoader.memoryCache?.get(highResRequest.memoryCacheKey!!)
            val placeholderDrawable = cachedHighResDrawable?.bitmap?.toDrawable(resources)

            Log.i("Preview", "cache key: ${highResRequest.memoryCacheKey}")

            if (cachedHighResDrawable != null) {
                imageView.load(uri, imageLoader) {
                    placeholder(placeholderDrawable)
                    error(R.drawable.ic_image_load_error)
                    crossfade(true)
                    allowRgb565(true)
                }
            } else {
                imageView.load(uri, imageLoader) {
                    placeholder(R.drawable.ic_image_loading)
                    error(R.drawable.ic_image_load_error)
                    crossfade(true)
                    allowRgb565(true)
                }
            }

            imageView.visibility = View.VISIBLE
            imageView.setOnViewTapListener { _, _, _ -> dismiss() }
        }

        view.setOnClickListener { dismiss() }

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
