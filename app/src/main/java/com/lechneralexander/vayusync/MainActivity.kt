package com.lechneralexander.vayusync

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : Activity() {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ImageAdapter
    private val imageFiles = mutableListOf<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupRecyclerView()

        if (checkPermissions()) {
            loadImages()
        } else {
            requestPermissions()
        }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = ImageAdapter(imageFiles)
        recyclerView.adapter = adapter
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadImages()
            } else {
                Toast.makeText(this, "Storage permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadImages() {
        scope.launch(Dispatchers.IO) {
            // Use MediaStore for modern Android versions (more efficient and respects scoped storage)
            loadImagesFromMediaStore()
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadImagesFromMediaStore() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Images.Media.MIME_TYPE} = ? OR ${MediaStore.Images.Media.MIME_TYPE} = ?"
        val selectionArgs = arrayOf("image/jpeg", "image/jpg")
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            while (cursor.moveToNext()) {
                val filePath = cursor.getString(dataColumn)
                val file = File(filePath)
                if (file.exists() && file.isJpgFile()) {
                    imageFiles.add(file)
                }
            }
        }
    }

    private fun File.isJpgFile(): Boolean {
        val name = this.name.lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    inner class ImageAdapter(private val images: MutableList<File>) :
        RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val imageFile = images[position]
            holder.loadImage(imageFile)
        }

        override fun getItemCount(): Int = images.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView.findViewById(R.id.imageView)

            fun loadImage(imageFile: File) {
                // Clear previous image
                imageView.setImageResource(android.R.color.transparent)

                scope.launch(Dispatchers.IO) {
                    val bitmap = loadOptimizedBitmap(imageFile, 200, 200)
                    withContext(Dispatchers.Main) {
                        bitmap?.let { imageView.setImageBitmap(it) }
                    }
                }
            }

            private fun loadOptimizedBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
                return try {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(file.absolutePath, options)

                    options.apply {
                        inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
                        inJustDecodeBounds = false
                        inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                        inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                    }

                    BitmapFactory.decodeFile(file.absolutePath, options)
                } catch (e: Exception) {
                    null
                }
            }

            private fun calculateInSampleSize(
                options: BitmapFactory.Options,
                reqWidth: Int,
                reqHeight: Int
            ): Int {
                val (height: Int, width: Int) = options.run { outHeight to outWidth }
                var inSampleSize = 1

                if (height > reqHeight || width > reqWidth) {
                    val halfHeight: Int = height / 2
                    val halfWidth: Int = width / 2

                    while (halfHeight / inSampleSize >= reqHeight &&
                        halfWidth / inSampleSize >= reqWidth) {
                        inSampleSize *= 2
                    }
                }

                return inSampleSize
            }
        }
    }
}