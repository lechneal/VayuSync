package com.lechneralexander.vayusync

import android.net.Uri

data class FileInfo(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val lastModified: Long,
    var orientation: Orientation,
    var copyStatus: CopyStatus,
)

enum class Orientation {PORTRAIT, LANDSCAPE, UNDEFINED}

enum class CopyStatus {NOT_COPIED, COPYING, COPIED}