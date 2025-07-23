package com.lechneralexander.vayusync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.ImageLoader
import coil.imageLoader
import coil.load
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Parameters
import coil.size.ViewSizeResolver
import com.lechneralexander.vayusync.cache.CacheHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), ActionMode.Callback {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "VayuSyncPrefs"
        private const val KEY_FOLDER_URI = "folderUri"
    }

    private val SUPPORTED_MIME_TYPES = arrayOf("image/jpeg", "image/jpg", "video/quicktime", "video/mp4")

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

        recyclerView.setHasFixedSize(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

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
        val gridLayoutManager = GridLayoutManager(this, 3) // Your existing manager

        // Prefetch 2 full rows of images ahead of time.
        gridLayoutManager.initialPrefetchItemCount = gridLayoutManager.spanCount * 2

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = gridLayoutManager // Use the modified manager
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

    private fun listImageFilesFromUri(treeUri: Uri): List<Uri> {
        val context = this
        val foundUris = mutableListOf<Uri>()

        // We need a stack to do a breadth-first or depth-first search.
        val directoryStack = ArrayDeque<Uri>()

        // Convert the base tree URI to a document URI that we can query for children
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        directoryStack.add(rootDocUri)

        // Process every directory until the stack is empty
        while (directoryStack.isNotEmpty()) {
            val currentDirUri = directoryStack.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(currentDirUri)
            )

            // Query the children of the current directory
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val mimeType = cursor.getString(1)

                    // Construct the URI for the found item
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    if (mimeType != null) {
                        when {
                            // If it's a directory, add it to the stack to be processed
                            mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> {
                                directoryStack.add(docUri)
                            }
                            // If it's an image, add it to our results list
                            SUPPORTED_MIME_TYPES.contains(mimeType) -> {
                                foundUris.add(docUri)
                            }
                        }
                    }
                }
            }
        }
        return foundUris
    }

    private fun loadImages(folderUri: Uri) {
        scope.launch(Dispatchers.IO) {
            val uris = listImageFilesFromUri(folderUri)

            // Sort the results if desired (optional)
            // Note: Sorting by name is simpler than by date without MediaStore
            val sortedUris = uris.sortedBy { it.toString() }.reversed()

            withContext(Dispatchers.Main) {
                imageUris.clear()
                imageUris.addAll(sortedUris)
                adapter.notifyDataSetChanged()
                if (imageUris.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No images found in the selected folder.", Toast.LENGTH_LONG).show()
                } else {
                    // Scroll to top after loading new folder
                    recyclerView.scrollToPosition(0)
                }
            }
        }
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
            private val selectionBadge: ImageView = itemView.findViewById(R.id.selectionBadge)
            private val mediaTypeIcon: ImageView = itemView.findViewById(R.id.mediaTypeIcon)

            fun bind(imageUri: Uri, isSelected: Boolean) {
                selectionBadge.visibility = if (isSelected) View.VISIBLE else View.GONE

                itemView.setOnClickListener {
                    if (actionMode != null) {
                        toggleSelection(bindingAdapterPosition)
                    } else {
                        showPreview(imageUri)
                    }
                }

                itemView.setOnLongClickListener {
                    if (actionMode == null) {
                        this@MainActivity.startActionMode(this@MainActivity)
                        toggleSelection(bindingAdapterPosition)
                    } else {
                        showPreview(imageUri)
                    }
                    true
                }

                loadImage(imageUri)
                loadMediaTypeIcon(imageUri)
            }

            private fun showPreview(imageUri: Uri) {
                PreviewDialogFragment.newInstance(imageUri)
                    .show(this@MainActivity.supportFragmentManager, "preview")
            }

            private fun loadMediaTypeIcon(imageUri: Uri) {
                val mimeType = getMediaType(imageUri)

                when {
                    mimeType?.startsWith("video") == true -> {
                        mediaTypeIcon.setImageResource(R.drawable.ic_type_video)
                        mediaTypeIcon.visibility = View.VISIBLE
                    }
                    mimeType?.startsWith("image") == true -> {
                        val orientation = getImageOrientation(imageUri)
                        mediaTypeIcon.setImageResource(
                            if (orientation == "landscape") R.drawable.ic_type_landscape else R.drawable.ic_type_portrait
                        )
                        mediaTypeIcon.visibility = View.VISIBLE
                    }
                    else -> {
                        mediaTypeIcon.visibility = View.GONE
                    }
                }
            }

            private fun getMediaType(uri: Uri): String? {
                return contentResolver.getType(uri)
            }

            private fun getImageOrientation(uri: Uri): String? {
                return try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeStream(input, null, options)
                        if (options.outWidth > options.outHeight) "landscape" else "portrait"
                    }
                } catch (e: Exception) {
                    Log.e(MainActivity.javaClass.name, "Error", e)
                    null
                }
            }

            private fun loadImage(imageUri: Uri) {
                val thumbnailCacheKey = "thumb_$imageUri"
                val highResCacheKey = "prev_$imageUri"

                imageView.tag = imageUri // Tag to verify in listeners

                if (loadMemoryCacheImage(imageUri, highResCacheKey)) {
                    return
                }

                if (loadDiskCacheImage(imageUri)) {
                    return
                }

                loadThumbnail(imageUri, thumbnailCacheKey, highResCacheKey)
            }

            private fun loadThumbnail(
                imageUri: Uri,
                thumbnailCacheKey: String,
                highResCacheKey: String
            ) {
                imageView.load(imageUri, getImageLoader()) {
                    size(ViewSizeResolver(imageView))
                    placeholder(R.drawable.ic_image_loading)
                    error(R.drawable.ic_image_load_error)
                    crossfade(true)
                    allowRgb565(true)
                    memoryCacheKey(thumbnailCacheKey)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    parameters(Parameters().newBuilder()
                        .set("use_thumbnail", true)
                        .build()
                    )
                    listener(
                        onSuccess = { _, _ ->
                            // Only load high-res if still bound to same URI
                            if (imageView.tag == imageUri) {
                                loadHighResImage(imageUri, highResCacheKey)
                            }
                        }
                    )
                }
            }

            private fun loadMemoryCacheImage(imageUri: Uri, cacheKey: String): Boolean {
                val highResRequest = ImageRequest.Builder(this@MainActivity)
                    .data(imageUri)
                    .size(ViewSizeResolver(imageView))
                    .memoryCacheKey(cacheKey)
                    .build()

                Log.i("TAG", "cache key: ${highResRequest.memoryCacheKey}")

                if (highResRequest.memoryCacheKey != null) {
                    val cachedHighResDrawable = imageLoader.memoryCache?.get(highResRequest.memoryCacheKey!!)
                    if (cachedHighResDrawable != null) {
                        loadHighResImage(imageUri, cacheKey)
                        return true;
                    }
                }
                return false;
            }

            private fun loadDiskCacheImage(imageUri: Uri): Boolean {
                val diskCache = getDiskCache()
                val cachedFile = File(diskCache, imageUri.lastPathSegment?.replace("/", "_") ?: "")

                if (cachedFile.exists()) {
                    Log.i("TAG", "loading disk cache: ${cachedFile.path}")
                    // Load from cached file (faster, cached on disk)
                    imageView.load(cachedFile) {
                        placeholder(R.drawable.ic_image_loading)
                        error(R.drawable.ic_image_load_error)
                        placeholder(imageView.drawable)
                        size(ViewSizeResolver(imageView))
                        crossfade(true)
                        allowRgb565(true)
                    }
                    return true;
                }
                return false;
            }

            private fun loadHighResImage(imageUri: Uri, cacheKey: String) {
                imageView.load(imageUri, imageLoader) {
                    memoryCacheKey(cacheKey)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    placeholder(imageView.drawable)
                    size(ViewSizeResolver(imageView))
                    allowRgb565(true)
                    crossfade(false)

                    listener(
                        onSuccess = { request, metadata ->
                            val drawable = metadata.drawable
                            val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@listener

                            // Save bitmap asynchronously
                            CoroutineScope(Dispatchers.IO).launch {
                                CacheHelper.saveBitmapToCache(this@MainActivity, imageUri, bitmap)
                            }
                        }
                    )
                }
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

    fun getImageLoader(): ImageLoader {
        return (applicationContext as VayuApp).getImageLoader()
    }

    fun getDiskCache(): File {
        return (applicationContext as VayuApp).getDiskCache()
    }
}