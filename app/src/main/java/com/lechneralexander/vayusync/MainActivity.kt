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
import androidx.lifecycle.findViewTreeLifecycleOwner
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
import com.lechneralexander.vayusync.extensions.setTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ln

class MainActivity : AppCompatActivity(), ActionMode.Callback {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "VayuSyncPrefs"
        private const val KEY_SOURCE_FOLDER_URI = "sourceFolderUri"
        private const val KEY_DESTINATION_FOLDER_URI = "destinationFolderUri"
        private const val KEY_SHOW_IMAGE_INFO_OVERLAY = "showImageInfoOverlay"
        private const val KEY_SHOW_MEDIA_TYPE_ICON = "showMediaTypeIcon"
        private const val KEY_SORT_CRITERION = "sortCriterion"
        private const val KEY_SORT_ORDER = "sortOrder"
        private const val KEY_ACTIVE_MIME_TYPE_FILTERS = "activeMimeTypeFilters"

        private const val PAYLOAD_INFO_VISIBILITY_CHANGED = "PAYLOAD_INFO_VISIBILITY_CHANGED"
        private const val PAYLOAD_MEDIA_TYPE_ICON_VISIBILITY_CHANGED = "PAYLOAD_MEDIA_TYPE_ICON_VISIBILITY_CHANGED"

        private const val MIME_TYPE_MENU_ITEM_GROUP_ID = 1001 // Unique group ID
        private const val MIME_TYPE_MENU_ITEM_ID_OFFSET = 10000 // Start IDs for MIME types
    }

    private val currentSort = CurrentSort()
    private var showImageInfoOverlay = false
    private var showMediaTypeIcon = true

    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var selectSourceFolderButton: Button
    private lateinit var selectDestinationFolderButton: Button
    private lateinit var adapter: ImageAdapter
    // --- Use Uri instead of File ---
    private val allFileInfos = mutableListOf<FileInfo>()
    private val shownFileInfos = mutableListOf<FileInfo>()
    private var alreadyCopiedImages = HashSet<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // mime filter
    private var activeMimeTypeFilters: MutableSet<String> = mutableSetOf()
    private val discoveredMimeTypes = mutableSetOf<String>()
    private val mimeTypeMenuItemIds = mutableMapOf<Int, String>()
    private var menuItemMimeTypeFilter: MenuItem? = null

    // -- copy
    private lateinit var fileCopier: ContentResolverFileCopier
    private lateinit var copyViewModel: CopyViewModel
    private lateinit var copyProgressContainer: LinearLayout
    private lateinit var copyProgressBar: ProgressBar
    private lateinit var etaText: TextView
    private lateinit var statusText: TextView
    private lateinit var cancelCopyButton: ImageButton
    private lateinit var pauseButton: ImageButton
    private lateinit var resumeButton: ImageButton
    private lateinit var sourceFolderPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var destFolderPickerLauncher: ActivityResultLauncher<Uri?>

    // Jobs
    private var debounceRestartPreviewJob: Job? = null

    // To manage the contextual action mode
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        //Init prefs
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        showImageInfoOverlay = prefs.getBoolean(KEY_SHOW_IMAGE_INFO_OVERLAY, false)
        showMediaTypeIcon = prefs.getBoolean(KEY_SHOW_MEDIA_TYPE_ICON, true)

        val savedCriterionName = prefs.getString(KEY_SORT_CRITERION, SortCriterion.FILENAME.toString())
        val savedOrderName = prefs.getString(KEY_SORT_ORDER, SortOrder.ASCENDING.toString())
        currentSort.criterion = try { SortCriterion.valueOf(savedCriterionName!!) } finally { }
        currentSort.order = try { SortOrder.valueOf(savedOrderName!!) } finally { }

        val savedFilters = prefs.getStringSet(KEY_ACTIVE_MIME_TYPE_FILTERS, emptySet())
        activeMimeTypeFilters.addAll(savedFilters ?: emptySet())


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
        pauseButton = findViewById(R.id.pauseButton)
        resumeButton = findViewById(R.id.resumeButton)

        fileCopier = ContentResolverFileCopier(contentResolver)
        copyViewModel = CopyViewModel(fileCopier)

        cancelCopyButton.setOnClickListener {
            showCopyCancelConfirmationDialog {
                copyViewModel.cancelCopy()
                Toast.makeText(this, "Copy cancelled", Toast.LENGTH_SHORT).show()
                copyProgressContainer.visibility = View.GONE
                refreshCopyStatus()
                actionMode?.finish()
            }
        }
        pauseButton.setOnClickListener {
            copyViewModel.pauseCopy()
        }
        resumeButton.setOnClickListener {
            copyViewModel.resumeCopy()
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

                pauseButton.visibility = if (!progress.paused) View.VISIBLE else View.GONE
                resumeButton.visibility = if (progress.paused) View.VISIBLE else View.GONE
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
                copiedImageInfo.copyStatus = CopyStatus.COPIED
                alreadyCopiedImages.add(copiedImageInfo.fileName)

                val position = shownFileInfos.indexOfFirst { it.uri == copiedImageInfo.uri }
                if (position != -1) {
                    adapter.notifyItemChanged(position)
                }
            }
        }
    }

    private fun refreshCopyStatus() {
        shownFileInfos.forEachIndexed { index, image ->
            val newStatus = if (alreadyCopiedImages.contains(image.fileName)) CopyStatus.COPIED else CopyStatus.NOT_COPIED
            if (image.copyStatus != newStatus) {
                image.copyStatus = newStatus
                adapter.notifyItemChanged(index)
            }
        }
        allFileInfos.forEach { if (it.copyStatus == CopyStatus.NOT_COPIED) it.copyStatus = CopyStatus.NOT_COPIED }
    }

    private fun saveActiveMimeTypeFilters() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putStringSet(KEY_ACTIVE_MIME_TYPE_FILTERS, activeMimeTypeFilters)
            apply()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        menuItemMimeTypeFilter = menu
            .findItem(R.id.action_filter_mime_type)

        updateFilterIcon()

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.let { safeMenu ->
            updateSortMenuChecks(safeMenu)
            safeMenu.findItem(R.id.action_toggle_info)?.isChecked = showImageInfoOverlay
            safeMenu.findItem(R.id.action_toggle_media_type_icon)?.isChecked = showMediaTypeIcon

            // Dynamically build MIME type filter menu
            val filterMenuParent = safeMenu.findItem(R.id.action_filter_mime_type)
            setupMimeFilter(filterMenuParent)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setupMimeFilter(filterMenuParent: MenuItem) {
        if (discoveredMimeTypes.isNotEmpty()) {
            val submenu = filterMenuParent.subMenu ?: throw IllegalStateException("Submenu cannot be null")
            submenu.clear() // Clear previous dynamic items
            mimeTypeMenuItemIds.clear()

            filterMenuParent.isVisible = true // Show if types are available

            // Add "Show All" option
            val showAllItem = submenu.add(
                MIME_TYPE_MENU_ITEM_GROUP_ID,
                Menu.NONE,
                0, // Order
                "Show All Types"
            )
            showAllItem.isCheckable = true
            showAllItem.isChecked = activeMimeTypeFilters.isEmpty() // Checked if no filters active

            discoveredMimeTypes.sorted().forEachIndexed { index, mimeType ->
                val itemId = MIME_TYPE_MENU_ITEM_ID_OFFSET + index
                mimeTypeMenuItemIds[itemId] = mimeType // Map ID to MIME type string
                val item = submenu.add(
                    MIME_TYPE_MENU_ITEM_GROUP_ID,
                    itemId,
                    index + 1, // Order
                    mimeType // Display the MIME type string
                )
                item.isCheckable = true
                item.isChecked = activeMimeTypeFilters.contains(mimeType)
            }
        } else {
            filterMenuParent.isVisible = false
        }
    }

    fun updateFilterIcon() {
        menuItemMimeTypeFilter?.let {
            if (activeMimeTypeFilters.isNotEmpty()) {
                it.setTint(this, com.google.android.material.R.attr.colorPrimary)
            } else {
                it.setTint(this, com.google.android.material.R.attr.colorOnSurface)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle MIME type filter selection
        if (item.groupId == MIME_TYPE_MENU_ITEM_GROUP_ID) {
            if (item.itemId == Menu.NONE) { //show all
                if (!item.isChecked) {
                    activeMimeTypeFilters.clear()
                }
            } else {
                mimeTypeMenuItemIds[item.itemId]?.let { mimeType ->
                    if (item.isChecked) {
                        activeMimeTypeFilters.remove(mimeType)
                    } else {
                        activeMimeTypeFilters.add(mimeType)
                    }
                }
            }
            saveActiveMimeTypeFilters()
            refreshShownImages()
            invalidateOptionsMenu()
            return true
        }

        val previousSortCriterion = currentSort.criterion
        val previousSortOrder = currentSort.order
        when (item.itemId) {
            R.id.sort_filename_asc -> {
                currentSort.criterion = SortCriterion.FILENAME
                currentSort.order = SortOrder.ASCENDING
            }
            R.id.sort_filename_desc -> {
                currentSort.criterion = SortCriterion.FILENAME
                currentSort.order = SortOrder.DESCENDING
            }
            R.id.sort_filesize_asc -> {
                currentSort.criterion = SortCriterion.FILESIZE
                currentSort.order = SortOrder.ASCENDING
            }
            R.id.sort_filesize_desc -> {
                currentSort.criterion = SortCriterion.FILESIZE
                currentSort.order = SortOrder.DESCENDING
            }
            R.id.sort_filetype_asc -> {
                currentSort.criterion = SortCriterion.FILETYPE
                currentSort.order = SortOrder.ASCENDING
            }
            R.id.sort_filetype_desc -> {
                currentSort.criterion = SortCriterion.FILETYPE
                currentSort.order = SortOrder.DESCENDING
            }
            R.id.sort_datemodified_asc -> {
                currentSort.criterion = SortCriterion.LAST_MODIFIED
                currentSort.order = SortOrder.ASCENDING
            }
            R.id.sort_datemodified_desc -> {
                currentSort.criterion = SortCriterion.LAST_MODIFIED
                currentSort.order = SortOrder.DESCENDING
            }
            R.id.sort_uri_asc -> {
                currentSort.criterion = SortCriterion.URI
                currentSort.order = SortOrder.ASCENDING
            }
            R.id.sort_uri_desc -> {
                currentSort.criterion = SortCriterion.URI
                currentSort.order = SortOrder.DESCENDING
            }
            R.id.action_toggle_info -> {
                showImageInfoOverlay = !showImageInfoOverlay
                item.isChecked = showImageInfoOverlay

                // Save the new state
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                    putBoolean(KEY_SHOW_IMAGE_INFO_OVERLAY, showImageInfoOverlay)
                }

                adapter.notifyItemRangeChanged(0, shownFileInfos.size, PAYLOAD_INFO_VISIBILITY_CHANGED) // Use payload
                return true
            }
            R.id.action_toggle_media_type_icon -> {
                showMediaTypeIcon = !showMediaTypeIcon
                item.isChecked = showMediaTypeIcon

                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                    putBoolean(KEY_SHOW_MEDIA_TYPE_ICON, showMediaTypeIcon)
                }

                adapter.notifyItemRangeChanged(0, shownFileInfos.size, PAYLOAD_MEDIA_TYPE_ICON_VISIBILITY_CHANGED)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }

        if (previousSortCriterion != currentSort.criterion || previousSortOrder != currentSort.order) {
            item.isChecked = true
            saveSortConfig()
            refreshShownImages()
        }
        return true
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
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Permission for destination folder lost. Please select again.", Toast.LENGTH_LONG).show()
                }
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

        if (outputUri == null) {
            return
        }
        scope.launch(Dispatchers.IO) {
            updateAlreadyCopiedImages(outputUri)
            withContext(Dispatchers.Main) {
                refreshCopyStatus()
            }
        }
    }

    private fun listExistingFilenames(destUri: Uri): HashSet<String> {
        val existing = HashSet<String>()
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
        adapter = ImageAdapter(shownFileInfos)
        recyclerView.adapter = adapter

        setupFastScroller()
    }

    private fun setupFastScroller() {
        FastScrollerBuilder(recyclerView)
            .useMd2Style()
            .setTrackDrawable(ContextCompat.getDrawable(this, R.drawable.line_drawable)!!)
            .setThumbDrawable(ContextCompat.getDrawable(this, R.drawable.thumb_drawable)!!)
            .setPopupTextProvider { _, position ->
                val imageInfo = shownFileInfos[position]
                when (currentSort.criterion) {
                    SortCriterion.FILENAME -> imageInfo.fileName
                    SortCriterion.FILESIZE -> formatBytes(imageInfo.fileSize) // Helper needed
                    SortCriterion.FILETYPE -> imageInfo.mimeType // Helper needed
                    SortCriterion.LAST_MODIFIED -> formatTimestamp(imageInfo.lastModified) // Helper needed
                    SortCriterion.URI -> imageInfo.uri.lastPathSegment ?: "..." // Or some part of URI
                }
            }
            .build()
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "N/A"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(Date(timestamp))
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

    private fun loadImageFilesFromUri(treeUri: Uri) {
        val context = this
        val foundFiles = mutableListOf<FileInfo>()
        val currentDiscoveredMimeTypes = mutableSetOf<String>() // Local set for this scan

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
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val mimeType = cursor.getString(1)
                    val fileName = cursor.getString(2)
                    val fileSize = cursor.getLong(3)
                    val lastModified = cursor.getLong(4)

                    // Construct the URI for the found item
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    if (mimeType != null) {
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            directoryStack.add(docUri)
                        } else if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                            currentDiscoveredMimeTypes.add(mimeType)
                            foundFiles.add(
                                FileInfo(
                                    docUri,
                                    fileName,
                                    fileSize,
                                    mimeType,
                                    lastModified,
                                    Orientation.UNDEFINED,
                                    if (alreadyCopiedImages.contains(fileName)) CopyStatus.COPIED else CopyStatus.NOT_COPIED
                                )
                            )
                        }
                    }
                }
            }
        }

        this.discoveredMimeTypes.clear()
        this.discoveredMimeTypes.addAll(currentDiscoveredMimeTypes.sorted()) // Keep them sorted for menu display

        this.allFileInfos.clear()
        this.allFileInfos.addAll(foundFiles)
    }

    private fun updateAlreadyCopiedImages(outputUri: Uri?) {
        if (outputUri == null) {
            alreadyCopiedImages.clear()
            return
        }

        alreadyCopiedImages = listExistingFilenames(outputUri!!)
    }

    private fun loadImages(folderUri: Uri) {
        scope.launch(Dispatchers.Main) {
            loadingProgressBar.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }

        scope.launch(Dispatchers.IO) {
            updateAlreadyCopiedImages(getSavedDestinationFolder())

            loadImageFilesFromUri(folderUri)

            withContext(Dispatchers.Main) {
                refreshShownImages()
                invalidateOptionsMenu()

                recyclerView.scrollToPosition(0)
                recyclerView.visibility = View.VISIBLE
                loadingProgressBar.visibility = View.GONE

                if (allFileInfos.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No images found in the selected folder: $folderUri", Toast.LENGTH_LONG).show()
                }
            }

            // Kick off EXIF parsing in background
            lazyLoadExifOrientation(allFileInfos)
        }
    }

    private fun refreshShownImages() {
        adapter.cancelAllPendingPreviews()

        shownFileInfos.clear()
        shownFileInfos.addAll(allFileInfos
            .filter { activeMimeTypeFilters.isEmpty() || activeMimeTypeFilters.contains(it.mimeType) }
            .sortedWith(getImageSorter())
        )

        adapter.notifyDataSetChanged()
        updateFilterIcon()
    }

    private fun getImageSorter(): Comparator<FileInfo> =
        when (currentSort.criterion) {
            SortCriterion.URI -> compareBy { it.uri.toString() }
            SortCriterion.FILENAME -> compareBy { it.fileName.lowercase() }
            SortCriterion.FILETYPE -> compareBy { it.mimeType }
            SortCriterion.FILESIZE -> compareBy { it.fileSize }
            SortCriterion.LAST_MODIFIED -> compareBy<FileInfo> { it.lastModified }.thenBy { it.fileName }
        }.let {
            if (currentSort.order == SortOrder.ASCENDING) it else it.reversed()
        }

    private fun saveSortConfig() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_SORT_CRITERION, currentSort.criterion.name)
            putString(KEY_SORT_ORDER, currentSort.order.name)
            apply() // or commit()
        }
    }

    private fun updateSortMenuChecks(menu: Menu) {
        val itemIdToCheck = when (currentSort.criterion) {
            SortCriterion.FILENAME -> if (currentSort.order == SortOrder.ASCENDING) R.id.sort_filename_asc else R.id.sort_filename_desc
            SortCriterion.FILESIZE -> if (currentSort.order == SortOrder.ASCENDING) R.id.sort_filesize_asc else R.id.sort_filesize_desc
            SortCriterion.FILETYPE -> if (currentSort.order == SortOrder.ASCENDING) R.id.sort_filetype_asc else R.id.sort_filetype_desc
            SortCriterion.LAST_MODIFIED -> if (currentSort.order == SortOrder.ASCENDING) R.id.sort_datemodified_asc else R.id.sort_datemodified_desc
            SortCriterion.URI -> if (currentSort.order == SortOrder.ASCENDING) R.id.sort_uri_asc else R.id.sort_uri_desc
        }
        menu.findItem(itemIdToCheck)?.isChecked = true
    }

    private fun lazyLoadExifOrientation(images: List<FileInfo>) {
        scope.launch(Dispatchers.IO) {
            images.forEach {
                it.orientation = readOrientation(it)
            }
            scope.launch(Dispatchers.Main) {
                adapter.notifyItemRangeChanged(0, images.size)
            }
        }
    }

    private fun readOrientation(image: FileInfo): Orientation {
        return try {
            contentResolver.openInputStream(image.uri).use { input ->
                val exif = ExifInterface(input!!)
                val exifOrientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )

                val originalWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val originalHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                val (effectiveWidth, effectiveHeight) = when (exifOrientation) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,  // Rotated and flipped
                    ExifInterface.ORIENTATION_TRANSVERSE -> { // Rotated and flipped
                        Pair(originalHeight, originalWidth)
                    }
                    ExifInterface.ORIENTATION_NORMAL,
                    ExifInterface.ORIENTATION_ROTATE_180,
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                        Pair(originalWidth, originalHeight)
                    }
                    else -> {
                        Pair(originalWidth, originalHeight)
                    }
                }

                return when {
                    effectiveWidth == 0 || effectiveHeight == 0 -> Orientation.UNDEFINED
                    effectiveWidth > effectiveHeight -> Orientation.LANDSCAPE
                    effectiveHeight > effectiveWidth -> Orientation.PORTRAIT
                    else -> Orientation.UNDEFINED
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
    inner class ImageAdapter(private val images: List<FileInfo>) :
        RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

        private val selectedItems = mutableListOf<FileInfo>()

        fun getSelectedCount() = selectedItems.size
        fun getSelectedItems(): List<FileInfo> = selectedItems.toList()

        fun getImageInfo(position: Int): FileInfo {
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
            selectedItems.clear()
            images.forEach { selectedItems.add(it) }
            notifyItemRangeChanged(0, images.size)
            actionMode?.invalidate()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            val imageInfo = getImageInfo(position)
            if (payloads.contains(PAYLOAD_INFO_VISIBILITY_CHANGED)) {
                holder.loadImageInfoOverlay(imageInfo)
            } else if (payloads.contains(PAYLOAD_MEDIA_TYPE_ICON_VISIBILITY_CHANGED)) {
                holder.loadMediaTypeIcon(imageInfo)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        // Ensure the standard onBindViewHolder calls the full bind
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val imageInfo = getImageInfo(position)
            val isSelected = selectedItems.contains(imageInfo)
            holder.bind(imageInfo, isSelected)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.cancelPendingPreview()
            super.onViewRecycled(holder)
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
            // Info Overlay Views
            private val infoOverlay: LinearLayout = itemView.findViewById(R.id.infoOverlay)
            private val infoFileName: TextView = itemView.findViewById(R.id.infoFileName)
            private val infoFileSizeAndDate: TextView = itemView.findViewById(R.id.infoFileSizeAndDate)
            // Jobs
            private var debouncePreviewJob: Job? = null

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
                debouncePreviewJob?.cancel()
                this.imageView.dispose();
            }

            fun bind(fileInfo: FileInfo, isSelected: Boolean) {
                imageView.tag = fileInfo.uri // Tag to verify in listeners

                this@MainActivity.lifecycleScope.launch {
                    loadImage(fileInfo.uri)
                }
                loadMediaTypeIcon(fileInfo)
                loadSelectionBadge(fileInfo, isSelected)
                loadImageInfoOverlay(fileInfo)
            }

            fun loadImageInfoOverlay(fileInfo: FileInfo) {
                if (showImageInfoOverlay) {
                    infoOverlay.visibility = View.VISIBLE
                    infoFileName.text = fileInfo.fileName
                    val sizeStr = formatBytes(fileInfo.fileSize) // Use helper from MainActivity or move it
                    val dateStr = formatTimestamp(fileInfo.lastModified) // Use helper from MainActivity or move it
                    infoFileSizeAndDate.text = "$sizeStr - $dateStr"
                } else {
                    infoOverlay.visibility = View.GONE
                }
            }

            private fun loadSelectionBadge(
                fileInfo: FileInfo,
                isSelected: Boolean
            ) {
                if (isSelected) {
                    selectionBadge.setImageResource(R.drawable.ic_check_circle)
                    selectionBadge.visibility = View.VISIBLE
                    selectionBadge.alpha = 1f
                } else if (fileInfo.copyStatus == CopyStatus.COPIED) {
                    selectionBadge.setImageResource(R.drawable.ic_check_circle)
                    selectionBadge.visibility = View.VISIBLE
                    selectionBadge.alpha = 0.5f
                } else if (fileInfo.copyStatus == CopyStatus.COPYING) {
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
                this@MainActivity.supportFragmentManager.setFragmentResultListener(
                    "preview_closed",
                    this@MainActivity
                ) { _, _ ->
                    debounceRestartPreviewJob?.cancel()
                    debounceRestartPreviewJob = this@MainActivity.lifecycleScope.launch {
                        delay(500) // Adjust the debounce delay as needed (e.g., 300ms)
                        this@ImageAdapter.restartVisiblePreviews()
                    }
                }

                this@ImageAdapter.cancelAllPendingPreviews();
            }

            fun loadMediaTypeIcon(image: FileInfo) {
                if (!showMediaTypeIcon) {
                    mediaTypeIcon.visibility = View.GONE
                    return
                }

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

            private suspend fun loadImage(imageUri: Uri) {
                val loadedFromDiskCache = loadImageFromDiskCacheIfAvailable(imageUri)

                if (!loadedFromDiskCache) {
                    loadThumbnail(imageUri)
                }
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
                            debouncePreviewJob?.cancel()

                            if (imageView.tag != imageUri) {
                                return@listener
                            }

                            debouncePreviewJob = itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                                try {
                                    delay(500L)
                                    Log.d("MainActivity", "Loading preview for: $imageUri")
                                    if (imageView.tag == imageUri && isActive) { // Check tag AND coroutine status
                                        loadPreview(imageUri)
                                    }
                                } catch (_: CancellationException) { /* Expected */ }
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
                            Log.i("MainActivity", "Storing bitmap to cache for: $imageUri")
                            if (imageView.tag == imageUri) {
                                val drawable = metadata.drawable
                                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@listener

                                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                                    CacheHelper.saveBitmapToCache(
                                        this@MainActivity,
                                        imageUri,
                                        bitmap
                                    )
                                }
                            }
                        }
                    )
                }
            }

            private suspend fun loadImageFromDiskCacheIfAvailable(imageUri: Uri): Boolean {
                val diskCache = getDiskCache()
                val cachedFile = CacheHelper.getCachedFile(diskCache, imageUri)

                val fileExists = withContext(Dispatchers.IO) { // Switch to IO thread for exists()
                    cachedFile?.exists() == true
                }

                if (fileExists) {
                    Log.d("MainActivity", "Loading image from disk cache: $cachedFile")
                    imageView.load(cachedFile, getImageLoader()) {
                        memoryCacheKey(CacheHelper.getPreviewCacheKey(imageUri))
                        placeholder(R.drawable.ic_image_loading)
                        error(R.drawable.ic_image_load_error)
                        size(ViewSizeResolver(imageView))
                        crossfade(true)
                    }
                    return true
                }
                Log.d("MainActivity", "No cached image found for: $imageUri")
                return false
            }
        }
    }

    private fun copyFilesTo(images: List<FileInfo>, destinationFolder: Uri) {
        val imagesToCopy = images.map {
            it.copyStatus = CopyStatus.COPYING
            ImageToCopy(it, destinationFolder)
        }
        copyViewModel.enqueueFiles(imagesToCopy)
        adapter.clearSelections()
        updateActionModeTitle()
    }

    private fun updateActionModeTitle() {
        val selectedCount = adapter.getSelectedCount()
        actionMode?.title = "$selectedCount selected"
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
                val destinationFolder = getSavedDestinationFolder()
                if (destinationFolder != null) {
                    showCopyConfirmationDialog(destinationFolder)
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

    private fun showCopyConfirmationDialog(destinationFolder: Uri) {
        val selectedCount = adapter.getSelectedCount()
        AlertDialog.Builder(this)
            .setMessage("Copy $selectedCount files to:\n$destinationFolder")
            .setPositiveButton("Copy") { _, _ ->
                copyFilesTo(adapter.getSelectedItems(), destinationFolder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCopyCancelConfirmationDialog(confirmationCallback: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Copy?")
            .setMessage("This will stop copying the remaining files.")
            .setPositiveButton("Cancel Copy") { _, _ ->
                confirmationCallback()
            }
            .setNegativeButton("Keep Copying", null)
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

enum class SortCriterion {
    URI, FILENAME, FILETYPE, FILESIZE, LAST_MODIFIED
}

enum class SortOrder {
    ASCENDING, DESCENDING
}

data class CurrentSort(
    var criterion: SortCriterion = SortCriterion.FILENAME,
    var order: SortOrder = SortOrder.ASCENDING
)
