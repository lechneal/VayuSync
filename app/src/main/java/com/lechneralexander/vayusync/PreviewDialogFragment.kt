package com.lechneralexander.vayusync

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.fragment.app.DialogFragment
import coil.load

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
            val imageView = view.findViewById<ImageView>(R.id.fullImageView)
            val imageLoader = (requireContext().applicationContext as VayuApp).getImageLoader()
            imageView.load(uri, imageLoader) {
                placeholder(R.drawable.ic_image_loading)
                error(R.drawable.ic_image_load_error)
                crossfade(true)
                allowRgb565(true)
            }
            imageView.visibility = View.VISIBLE
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
