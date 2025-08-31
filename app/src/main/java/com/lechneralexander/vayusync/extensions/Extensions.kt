package com.lechneralexander.vayusync.extensions

import android.content.ContentResolver
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.MenuItem
import androidx.exifinterface.media.ExifInterface
import com.lechneralexander.vayusync.Orientation


fun MenuItem.setTint(context: Context, resourceId: Int) {
    val typedValue = TypedValue()
    context.theme.resolveAttribute(resourceId, typedValue, true)
    this.setIconTintList(ColorStateList.valueOf(typedValue.data))
}

fun ContentResolver.getImageOrientation(imageUri: Uri): Orientation {
    return try {
        openInputStream(imageUri)?.use { inputStream ->
            val exifInterface = ExifInterface(inputStream)
            val orientation = exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val originalWidth = exifInterface.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
            val originalHeight = exifInterface.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)

            val (effectiveWidth, effectiveHeight) = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE -> Pair(originalHeight, originalWidth)
                else -> Pair(originalWidth, originalHeight)
            }

            when {
                effectiveWidth == 0 || effectiveHeight == 0 -> Orientation.UNDEFINED
                effectiveWidth > effectiveHeight -> Orientation.LANDSCAPE
                effectiveHeight > effectiveWidth -> Orientation.PORTRAIT
                else -> Orientation.UNDEFINED
            }
        } ?: Orientation.UNDEFINED
    } catch (e: Exception) {
        Log.e("ImageUtils", "Error getting image orientation for $imageUri", e)
        Orientation.UNDEFINED
    }
}