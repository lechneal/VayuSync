package com.lechneralexander.vayusync

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FileInfo(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val lastModified: Long,
    var orientation: Orientation
) : Parcelable

enum class Orientation {PORTRAIT, LANDSCAPE, UNDEFINED}