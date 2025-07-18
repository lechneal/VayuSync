package com.lechneralexander.vayusync

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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

class MainActivity : AppCompatActivity(), ActionMode.Callback {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "VayuSyncPrefs"
        private const val KEY_FOLDER_URI = "folderUri"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var selectFolderButton: Button
    private lateinit var adapter: ImageAdapter
    // --- Use Uri instead of File ---
    private val imageUris = mutableListOf<Uri>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- Modern way to handle Activity results ---
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>

    // -- copy
    private lateinit var copyProgressBar: ProgressBar
    private lateinit var destFolderPickerLauncher: ActivityResultLauncher<Uri?>

    // To manage the contextual action mode
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        copyProgressBar = findViewById(R.id.copyProgressBar)

        setupRecyclerView()
        setupFolderPicker()
        setupDestFolderPicker() // New launcher for destination

        selectFolderButton = findViewById(R.id.selectFolderButton)
        selectFolderButton.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        if (checkPermissions()) {
            loadSavedFolder()
        } else {
            requestPermissions()
        }
    }

    private fun setupFolderPicker() {
        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                // Persist access permissions across device reboots
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveFolderUri(it)
                loadImages(it)
            }
        }
    }

    private fun loadSavedFolder() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val uriString = prefs.getString(KEY_FOLDER_URI, null)
        if (uriString != null) {
            val uri = Uri.parse(uriString)
            // Verify we still have permission to read this URI
            if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                loadImages(uri)
            } else {
                // We lost permission, prompt user to select again
                Toast.makeText(this, "Permission for folder lost. Please select again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFolderUri(uri: Uri) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        prefs.putString(KEY_FOLDER_URI, uri.toString())
        prefs.apply()
    }


    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        // --- Pass the new Uri list ---
        adapter = ImageAdapter(imageUris)
        recyclerView.adapter = adapter
    }

    private fun checkPermissions(): Boolean {
        // These are still needed to query MediaStore
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadSavedFolder()
            } else {
                Toast.makeText(this, "Storage permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Updated to take a folder Uri ---
    private fun loadImages(folderUri: Uri) {
        scope.launch(Dispatchers.IO) {
            val relativePath = getRelativePathFromUri(folderUri)
            if (relativePath == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not find folder path", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // --- Pass the relative path to the MediaStore query ---
            val uris = loadImagesFromMediaStore(relativePath)
            withContext(Dispatchers.Main) {
                imageUris.clear()
                imageUris.addAll(uris)
                adapter.notifyDataSetChanged()
                if (imageUris.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No images found in the selected folder.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- Helper to convert a SAF tree URI to a MediaStore relative path ---
    private fun getRelativePathFromUri(treeUri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":")
        return if (split.size > 1) {
            "${split[1]}/" // The path needs to end with a slash for MediaStore
        } else {
            null
        }
    }

    // --- Updated to return a list of Uris and filter by path ---
    private fun loadImagesFromMediaStore(relativePath: String): List<Uri> {
        val foundUris = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        // --- The key change: Filter by RELATIVE_PATH with a wildcard to include subfolders ---
        val selection = "${MediaStore.Images.Media.MIME_TYPE} IN (?, ?) AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("image/jpeg", "image/jpg", "$relativePath%")
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                // --- Build the content URI for each image ---
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                foundUris.add(contentUri)
            }
        }
        return foundUris
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // --- Adapter now takes a list of Uris ---
    inner class ImageAdapter(private val images: List<Uri>) :
        RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

        private val selectedItems = mutableSetOf<Uri>()

        fun getSelectedCount() = selectedItems.size
        fun getSelectedItems(): List<Uri> = selectedItems.toList()

        fun toggleSelection(position: Int) {
            val uri = images[position]
            if (selectedItems.contains(uri)) {
                selectedItems.remove(uri)
            } else {
                selectedItems.add(uri)
            }
            notifyItemChanged(position)
            actionMode?.invalidate() // Re-runs onPrepareActionMode to update title
        }

        fun clearSelections() {
            val previouslySelected = selectedItems.toList()
            selectedItems.clear()
            previouslySelected.forEach { uri ->
                val index = images.indexOf(uri)
                if (index != -1) notifyItemChanged(index)
            }
        }

        fun selectAll() {
            images.forEach { selectedItems.add(it) }
            notifyDataSetChanged()
            actionMode?.invalidate()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val imageUri = images[position]
            val isSelected = selectedItems.contains(imageUri)
            holder.bind(imageUri, isSelected)
        }

        override fun getItemCount(): Int = images.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView.findViewById(R.id.imageView)
            private val selectionOverlay: View = itemView.findViewById(R.id.selectionBadge)

            init {
                itemView.setOnClickListener {
                    if (actionMode != null) {
                        toggleSelection(bindingAdapterPosition)
                    } else {
                        // Handle normal click if needed (e.g., open image full screen)
                    }
                }

                itemView.setOnLongClickListener {
                    if (actionMode == null) {
                        this@MainActivity.startActionMode(this@MainActivity)
                    }
                    toggleSelection(bindingAdapterPosition)
                    true
                }
            }

            fun bind(imageUri: Uri, isSelected: Boolean) {
                selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                loadImage(imageUri)
            }

            // --- Updated to load from a Uri ---
            fun loadImage(imageUri: Uri) {
                imageView.setImageResource(android.R.color.transparent)

                scope.launch(Dispatchers.IO) {
                    val bitmap = loadOptimizedBitmap(imageUri, 200, 200)
                    withContext(Dispatchers.Main) {
                        // Check if the holder is still bound to the same position
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            bitmap?.let { imageView.setImageBitmap(it) }
                        }
                    }
                }
            }

            // --- Updated to decode from a Uri InputStream ---
            private fun loadOptimizedBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
                return try {
                    // First, decode with inJustDecodeBounds=true to check dimensions
                    var inputStream = contentResolver.openInputStream(uri)
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // Calculate inSampleSize
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

                    // Decode bitmap with inSampleSize set
                    options.inJustDecodeBounds = false
                    options.inPreferredConfig = Bitmap.Config.RGB_565
                    inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()
                    bitmap
                } catch (e: Exception) {
                    e.printStackTrace()
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
                    while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                        inSampleSize *= 2
                    }
                }
                return inSampleSize
            }
        }
    }

    private fun setupDestFolderPicker() {
        destFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { destUri ->
                val itemsToCopy = adapter.getSelectedItems()
                if (itemsToCopy.isNotEmpty()) {
                    copyFilesTo(itemsToCopy, destUri)
                }
            }
        }
    }

    private fun copyFilesTo(files: List<Uri>, destinationTreeUri: Uri) { // Renamed for clarity
        scope.launch(Dispatchers.Main) {
            copyProgressBar.visibility = View.VISIBLE
            val fileCount = files.size
            var successCount = 0

            val docId = DocumentsContract.getTreeDocumentId(destinationTreeUri)
            val destinationFolderDocUri = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, docId)

            withContext(Dispatchers.IO) {
                files.forEach { fileUri ->
                    try {
                        val fileName = getFileName(fileUri) ?: "file_${System.currentTimeMillis()}"

                        // Now, use the corrected Document URI when creating the new file.
                        val newFileUri = DocumentsContract.createDocument(
                            contentResolver,
                            destinationFolderDocUri!!, // Use the Document URI here
                            "image/jpeg",
                            fileName
                        )

                        if (newFileUri != null) {
                            contentResolver.openInputStream(fileUri)?.use { input ->
                                contentResolver.openOutputStream(newFileUri)?.use { output ->
                                    input.copyTo(output)
                                    successCount++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // It's good practice to log or show an error for the failed file
                    }
                }
            }

            // Back on the Main thread
            copyProgressBar.visibility = View.GONE
            Toast.makeText(this@MainActivity, "Copied $successCount of $fileCount files", Toast.LENGTH_LONG).show()
            actionMode?.finish() // Exit action mode after copy
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        result = cursor.getString(columnIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result
    }

    // --- ActionMode.Callback Implementation ---
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        this.actionMode = mode
        mode?.menuInflater?.inflate(R.menu.contextual_action_menu, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        val selectedCount = adapter.getSelectedCount()
        mode?.title = "$selectedCount selected"
        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_copy -> {
                // Launch the destination folder picker
                destFolderPickerLauncher.launch(null)
                return true
            }
            R.id.action_select_all -> {
                adapter.selectAll()
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        this.actionMode = null
        adapter.clearSelections()
    }
}