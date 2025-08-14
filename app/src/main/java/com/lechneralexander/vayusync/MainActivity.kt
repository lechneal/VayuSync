package com.lechneralexander.vayusync

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.ImageLoader
import coil.dispose
import coil.load
import coil.request.Parameters
import coil.size.ViewSizeResolver
import com.lechneralexander.vayusync.cache.CacheHelper
import com.lechneralexander.vayusync.copy.ContentResolverFileCopier
import com.lechneralexander.vayusync.copy.CopyViewModel
import com.lechneralexander.vayusync.copy.ImageToCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import kotlin.math.ln

class MainActivity : AppCompatActivity(), ActionMode.Callback {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "VayuSyncPrefs"
        private const val KEY_SOURCE_FOLDER_URI = "sourceFolderUri"
        private const val KEY_DESTINATION_FOLDER_URI = "destinationFolderUri"
    }

    private val SUPPORTED_MIME_TYPES = arrayOf("image/jpeg", "image/jpg", "video/quicktime", "video/mp4")

    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var selectSourceFolderButton: Button
    private lateinit var selectDestinationFolderButton: Button
    private lateinit var adapter: ImageAdapter
    // --- Use Uri instead of File ---
    private val imageInfos = mutableListOf<ImageInfo>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())


    // -- copy
    private lateinit var fileCopier: ContentResolverFileCopier
    private lateinit var copyViewModel: CopyViewModel
    private lateinit var copyProgressContainer: LinearLayout
    private lateinit var copyProgressBar: ProgressBar
    private lateinit var etaText: TextView
    private lateinit var statusText: TextView
    private lateinit var cancelCopyButton: ImageButton
    private lateinit var sourceFolderPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var destFolderPickerLauncher: ActivityResultLauncher<Uri?>

    // To manage the contextual action mode
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        loadingProgressBar = findViewById(R.id.loadingProgressBar)

        setupRecyclerView()
        setupSourceFolderPicker()
        setupDestFolderPicker()

        selectSourceFolderButton = findViewById(R.id.selectSourceFolderButton)
        selectSourceFolderButton.setOnClickListener {
            sourceFolderPickerLauncher.launch(null)
        }
        selectSourceFolderButton.setOnLongClickListener {
            val uri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SOURCE_FOLDER_URI, "Not set")
            Toast.makeText(this, uri, Toast.LENGTH_LONG).show()
            true
        }

        selectDestinationFolderButton = findViewById(R.id.selectDestinationFolderButton)
        selectDestinationFolderButton.setOnClickListener {
            destFolderPickerLauncher.launch(null)
        }
        selectDestinationFolderButton.setOnLongClickListener {
            val uri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(
                KEY_DESTINATION_FOLDER_URI, "Not set")
            Toast.makeText(this, uri, Toast.LENGTH_LONG).show()
            true
        }

        recyclerView.setHasFixedSize(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        if (checkPermissions()) {
            loadSavedSourceFolderAndImages()
        } else {
            requestPermissions()
        }

        //Observe copy progress
        copyProgressContainer = findViewById(R.id.copyProgressContainer)
        copyProgressBar = findViewById(R.id.copyProgressBar)
        etaText = findViewById(R.id.etaText)
        statusText = findViewById(R.id.statusText)
        cancelCopyButton = findViewById(R.id.cancelCopyButton)

        fileCopier = ContentResolverFileCopier(contentResolver)
        copyViewModel = CopyViewModel(fileCopier)

        cancelCopyButton.setOnClickListener {
            copyViewModel.cancelCopy()
            Toast.makeText(this, "Copy cancelled", Toast.LENGTH_SHORT).show()
            copyProgressContainer.visibility = View.GONE
            //TODO update image data and adapter to reset status: call same logic as after selecting output folder
        }

        lifecycleScope.launch {
            copyViewModel.getProgress().collectLatest { progress ->
                if (progress.completed) {
                    Toast.makeText(this@MainActivity, "Copied all files!", Toast.LENGTH_LONG).show()
                    copyProgressContainer.visibility = View.GONE
                    return@collectLatest
                }

                if (progress.totalBytes == 0L) {
                    copyProgressContainer.visibility = View.GONE
                    return@collectLatest
                }

                copyProgressBar.max =
                    progress.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                copyProgressBar.progress =
                    progress.copiedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                etaText.text =
                    if (progress.etaSeconds >= 0) "${formatDuration(progress.etaSeconds)} remaining"
                    else "Calculating..."
                statusText.text =
                    "${formatBytes(progress.copiedBytes)}/${formatBytes(progress.totalBytes)} bytes (${
                        formatBytes(progress.speed.toLong())
                    }/s)"

                copyProgressContainer.visibility = View.VISIBLE
            }
        }
        lifecycleScope.launch {
            copyViewModel.getCopiedImage().collect { copiedImageInfo ->
                val position = imageInfos.indexOfFirst { it.uri == copiedImageInfo.uri }
                copiedImageInfo.copyStatus = CopyStatus.COPIED
                if (position != -1) {
                    adapter.notifyItemChanged(position)
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val unit = 1024
        if (bytes < unit) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
        val prefix = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(unit.toDouble(), exp.toDouble()), prefix)
    }

    fun formatDuration(seconds: Int): String {
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                "$h h $m min"
            }
        }
    }

    private fun setupSourceFolderPicker() {
        sourceFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                // Persist access permissions across device reboots
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveSourceFolderUri(it)
                loadImages(it)
            }
        }
    }

    private fun setupDestFolderPicker() {
        destFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { destUri ->
                contentResolver.takePersistableUriPermission(destUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                saveDestinationFolderUri(destUri)
                Toast.makeText(this, "Output folder set: $destUri", Toast.LENGTH_SHORT).show()
                updateAlreadyStoredImages()
            }
        }
    }

    private fun loadSavedSourceFolderAndImages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val uriString = prefs.getString(KEY_SOURCE_FOLDER_URI, null)
        if (uriString != null) {
            val uri = uriString.toUri()
            // Verify we still have permission to read this URI
            if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                loadImages(uri)
            } else {
                // We lost permission, prompt user to select again
                Toast.makeText(this, "Permission for source folder lost. Please select again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getSavedDestinationFolder(): Uri? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val uriString = prefs.getString(KEY_DESTINATION_FOLDER_URI, null)
        if (uriString != null) {
            val uri = uriString.toUri()
            // Verify we still have permission to read and write this URI
            if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
                return uri
            } else {
                // We lost permission, prompt user to select again
                Toast.makeText(this, "Permission for destination folder lost. Please select again.", Toast.LENGTH_LONG).show()
            }
        }
        return null
    }

    private fun saveSourceFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_SOURCE_FOLDER_URI, uri.toString())
        }
    }

    private fun saveDestinationFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_DESTINATION_FOLDER_URI, uri.toString())
        }
    }

    private fun updateAlreadyStoredImages() {
        val outputUri = getSavedDestinationFolder()

        scope.launch(Dispatchers.IO) {
            val copiedNames = outputUri?.let { listExistingFilenames(it) } ?: emptySet()

            imageInfos.forEachIndexed { index, image ->
                image.copyStatus = if (copiedNames.contains(image.fileName)) CopyStatus.COPIED else CopyStatus.NOT_COPIED
            }
            withContext(Dispatchers.Main) {
                adapter.notifyItemRangeChanged(0, imageInfos.size)
            }
        }
    }

    private fun listExistingFilenames(destUri: Uri): Set<String> {
        val existing = mutableSetOf<String>()
        val docId = DocumentsContract.getTreeDocumentId(destUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(destUri, docId)

        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) {
                existing.add(it.getString(0))
            }
        }
        return existing
    }

    private fun setupRecyclerView() {
        val gridLayoutManager = GridLayoutManager(this, 3) // Your existing manager

        // Prefetch 2 full rows of images ahead of time.
        gridLayoutManager.initialPrefetchItemCount = gridLayoutManager.spanCount * 2

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = gridLayoutManager // Use the modified manager
        adapter = ImageAdapter(imageInfos)
        recyclerView.adapter = adapter

        //setup fastscroller
        FastScrollerBuilder(recyclerView)
            .useMd2Style()
            .setTrackDrawable(ContextCompat.getDrawable(this, R.drawable.line_drawable)!!)
            .setThumbDrawable(ContextCompat.getDrawable(this, R.drawable.thumb_drawable)!!)
            .setPopupTextProvider { _, position ->
                // Return the popup text for the item at this position
                "$position / ${imageInfos.size}"
            }
            .build();
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
                loadSavedSourceFolderAndImages()
            } else {
                Toast.makeText(this, "Storage permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun listImageFilesFromUri(treeUri: Uri): List<ImageInfo> {
        val outputUri = getSavedDestinationFolder()
        val copiedNames = outputUri?.let { listExistingFilenames(it) } ?: emptySet()

        val context = this
        val foundImages = mutableListOf<ImageInfo>()

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
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val mimeType = cursor.getString(1)
                    val fileName = cursor.getString(2)
                    val fileSize = cursor.getLong(3)

                    // Construct the URI for the found item
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val copyStatus = if (copiedNames.contains(fileName)) CopyStatus.COPIED else CopyStatus.NOT_COPIED

                    if (mimeType != null) {
                        when {
                            // If it's a directory, add it to the stack to be processed
                            mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> {
                                directoryStack.add(docUri)
                            }
                            // If it's an image, add it to our results list
                            SUPPORTED_MIME_TYPES.contains(mimeType) -> {
                                foundImages.add(ImageInfo(docUri, fileName, fileSize, mimeType, Orientation.UNDEFINED, copyStatus))
                            }
                        }
                    }
                }
            }
        }
        return foundImages
    }

    private fun loadImages(folderUri: Uri) {
        scope.launch(Dispatchers.Main) {
            loadingProgressBar.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }

        scope.launch(Dispatchers.IO) {
            val images = listImageFilesFromUri(folderUri)
            val sortedImages = images.sortedBy { it.uri.toString() }.reversed()

            withContext(Dispatchers.Main) {
                imageInfos.clear()
                imageInfos.addAll(sortedImages)
                adapter.notifyItemRangeInserted(0, images.size)

                recyclerView.scrollToPosition(0)
                recyclerView.visibility = View.VISIBLE
                loadingProgressBar.visibility = View.GONE

                if (imageInfos.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No images found in the selected folder: $folderUri", Toast.LENGTH_LONG).show()
                }
            }

            // Kick off EXIF parsing in background
            lazyLoadExifOrientation(images)
        }
    }

    private fun lazyLoadExifOrientation(images: List<ImageInfo>) {
        scope.launch(Dispatchers.IO) {
            images.forEach {
                it.orientation = readOrientation(it)
            }
        }
        images.forEachIndexed { index, image ->
            if (image.orientation != Orientation.UNDEFINED) {
                adapter.notifyItemChanged(index)
            }
        }
    }

    private fun readOrientation(image: ImageInfo): Orientation {
        return try {
            contentResolver.openInputStream(image.uri).use { input ->
                val exif = ExifInterface(input!!)
                val exifOrientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )

                return when (exifOrientation) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE -> Orientation.PORTRAIT

                    ExifInterface.ORIENTATION_NORMAL,
                    ExifInterface.ORIENTATION_ROTATE_180,
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Orientation.LANDSCAPE

                    else -> Orientation.UNDEFINED // default fallback
                }
            }
        } catch (e: Exception) {
            Log.e(MainActivity.javaClass.name, "Error", e)
            Orientation.UNDEFINED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // --- Adapter now takes a list of Uris ---
    inner class ImageAdapter(private val images: List<ImageInfo>) :
        RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

        private val selectedItems = mutableSetOf<ImageInfo>()

        fun getSelectedCount() = selectedItems.size
        fun getSelectedItems(): List<ImageInfo> = selectedItems.toList()

        fun getImageInfo(position: Int): ImageInfo {
            return images[position]
        }

        fun toggleSelection(position: Int) {
            val imageInfo = getImageInfo(position)
            if (selectedItems.contains(imageInfo)) {
                selectedItems.remove(imageInfo)
            } else {
                selectedItems.add(imageInfo)
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
            val imageInfo = getImageInfo(position)
            val isSelected = selectedItems.contains(imageInfo)
            holder.bind(imageInfo, isSelected)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.cancelPendingPreview();
        }

        fun cancelAllPendingPreviews() {
            // iterate visible view holders and cancel (uses recyclerView from enclosing Activity)
            val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
            val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
            for (i in first..last) {
                (recyclerView.findViewHolderForAdapterPosition(i) as? ViewHolder)?.cancelPendingPreview()
            }
        }

        fun restartVisiblePreviews() {
            val lm = (recyclerView.layoutManager as? GridLayoutManager) ?: return
            val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
            for (pos in first..last) {
                val holder = recyclerView.findViewHolderForAdapterPosition(pos) as? ViewHolder ?: continue
                onBindViewHolder(holder, pos)
            }
        }

        override fun getItemCount(): Int = images.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView.findViewById(R.id.imageView)
            private val selectionBadge: ImageView = itemView.findViewById(R.id.selectionBadge)
            private val mediaTypeIcon: ImageView = itemView.findViewById(R.id.mediaTypeIcon)

            init {
                itemView.setOnClickListener {
                    if (actionMode != null) {
                        toggleSelection(bindingAdapterPosition)
                    } else {
                        showPreview(getImageInfo(bindingAdapterPosition).uri)
                    }
                }

                itemView.setOnLongClickListener {
                    if (actionMode == null) {
                        this@MainActivity.startActionMode(this@MainActivity)
                        toggleSelection(bindingAdapterPosition)
                    } else {
                        showPreview(getImageInfo(bindingAdapterPosition).uri)
                    }
                    true
                }
            }

            fun cancelPendingPreview() {
                this.imageView.dispose();
            }

            fun bind(imageInfo: ImageInfo, isSelected: Boolean) {
                loadImage(imageInfo.uri)
                loadMediaTypeIcon(imageInfo)
                loadSelectionBadge(imageInfo, isSelected)
            }

            private fun loadSelectionBadge(
                imageInfo: ImageInfo,
                isSelected: Boolean
            ) {
                if (isSelected) {
                    selectionBadge.setImageResource(R.drawable.ic_check_circle)
                    selectionBadge.visibility = View.VISIBLE
                    selectionBadge.alpha = 1f
                } else if (imageInfo.copyStatus == CopyStatus.COPIED) {
                    selectionBadge.setImageResource(R.drawable.ic_check_circle)
                    selectionBadge.visibility = View.VISIBLE
                    selectionBadge.alpha = 0.5f
                } else if (imageInfo.copyStatus == CopyStatus.COPYING) {
                    selectionBadge.setImageResource(R.drawable.ic_image_loading)
                    selectionBadge.visibility = View.VISIBLE
                    selectionBadge.alpha = 1f
                } else {
                    selectionBadge.visibility = View.GONE
                }
            }

            private fun showPreview(imageUri: Uri) {
                val dialog = PreviewDialogFragment.newInstance(imageUri)
                dialog.show(this@MainActivity.supportFragmentManager, "preview")
                this@MainActivity.supportFragmentManager.setFragmentResultListener("preview_closed", this@MainActivity) { _, _ ->
                    this@ImageAdapter.restartVisiblePreviews()
                }

                this@ImageAdapter.cancelAllPendingPreviews();
            }

            private fun loadMediaTypeIcon(image: ImageInfo) {
                val mimeType = image.mimeType

                when {
                    mimeType.startsWith("video") -> {
                        mediaTypeIcon.setImageResource(R.drawable.ic_type_video)
                        mediaTypeIcon.visibility = View.VISIBLE
                    }
                    mimeType.startsWith("image") -> {
                        if (image.orientation == Orientation.UNDEFINED) {
                            mediaTypeIcon.visibility = View.GONE
                        } else {
                            mediaTypeIcon.visibility = View.VISIBLE
                            mediaTypeIcon.setImageResource(
                                if (image.orientation == Orientation.LANDSCAPE) R.drawable.ic_type_landscape
                                else R.drawable.ic_type_portrait
                            )
                        }
                    }
                    else -> {
                        mediaTypeIcon.visibility = View.GONE
                    }
                }
            }

            private fun loadImage(imageUri: Uri) {
                imageView.tag = imageUri // Tag to verify in listeners

                if (loadImageFromDiskCacheIfAvailable(imageUri)) {
                    return
                }

                loadThumbnail(imageUri)
            }

            private fun loadThumbnail(
                imageUri: Uri
            ) {
                imageView.load(imageUri, getImageLoader()) {
                    memoryCacheKey(CacheHelper.getThumbnailCacheKey(imageUri))
                    placeholder(R.drawable.ic_image_loading)
                    error(R.drawable.ic_image_load_error)
                    size(ViewSizeResolver(imageView))
                    crossfade(true)
                    parameters(Parameters().newBuilder()
                        .set("use_thumbnail", true)
                        .build()
                    )
                    listener(
                        onSuccess = { _, _ ->
                            // Only load high-res if still bound to same URI
                            if (imageView.tag == imageUri) {
                                loadPreview(imageUri)
                            }
                        }
                    )
                }
            }

            private fun loadPreview(imageUri: Uri) {
                imageView.load(imageUri, getImageLoader()) {
                    memoryCacheKey(CacheHelper.getPreviewCacheKey(imageUri))
                    placeholderMemoryCacheKey(CacheHelper.getThumbnailCacheKey(imageUri))
                    error(R.drawable.ic_image_load_error)
                    size(ViewSizeResolver(imageView))
                    crossfade(true)

                    listener(
                        onSuccess = { _, metadata ->
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

            private fun loadImageFromDiskCacheIfAvailable(imageUri: Uri): Boolean {
                val diskCache = getDiskCache()
                val cachedFile = File(diskCache, CacheHelper.getDiskCacheKey(imageUri))

                if (cachedFile.exists()) {
                    imageView.load(cachedFile, getImageLoader()) {
                        memoryCacheKey(CacheHelper.getPreviewCacheKey(imageUri))
                        placeholder(R.drawable.ic_image_loading)
                        error(R.drawable.ic_image_load_error)
                        size(ViewSizeResolver(imageView))
                        crossfade(true)
                    }
                    return true
                }
                return false
            }
        }
    }

    private fun copyFilesTo(images: List<ImageInfo>, destUri: Uri) {
        val imagesToCopy = images.map {
            it.copyStatus = CopyStatus.COPYING
            ImageToCopy(it, it.uri,getDestinationUri(it, destUri)!!)
        }
        copyViewModel.enqueueFiles(imagesToCopy)
        adapter.clearSelections()
        updateActionModeTitle()
    }

    private fun updateActionModeTitle() {
        val selectedCount = adapter.getSelectedCount()
        actionMode?.title = "$selectedCount selected"
    }

    private fun getDestinationUri(image: ImageInfo, destinationTreeUri: Uri): Uri? {
        val docId = DocumentsContract.getTreeDocumentId(destinationTreeUri)
        val destinationFolderDocUri = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, docId)
        val fileName = image.fileName ?: "file_${System.currentTimeMillis()}"

        // Now, use the corrected Document URI when creating the new file.
        return DocumentsContract.createDocument(
            contentResolver,
            destinationFolderDocUri, // Use the Document URI here
            image.mimeType,
            fileName
        )
    }

    // --- ActionMode.Callback Implementation ---
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        this.actionMode = mode
        mode?.menuInflater?.inflate(R.menu.contextual_action_menu, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        updateActionModeTitle()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_copy -> {
                val destUri = getSavedDestinationFolder()
                if (destUri != null) {
                    showCopyConfirmationDialog(destUri)
                } else {
                    Toast.makeText(this, "Please select output folder first", Toast.LENGTH_SHORT).show()
                    destFolderPickerLauncher.launch(null)
                }
                return true
            }
            R.id.action_select_all -> {
                adapter.selectAll()
                return true
            }
        }
        return false
    }

    private fun showCopyConfirmationDialog(destUri: Uri) {
        val selectedCount = adapter.getSelectedCount()
        AlertDialog.Builder(this)
            .setMessage("Copy $selectedCount files to:\n$destUri")
            .setPositiveButton("Copy") { _, _ ->
                copyFilesTo(adapter.getSelectedItems(), destUri)
            }
            .setNegativeButton("Cancel", null)
            .show()
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