package com.lechneralexander.vayusync.extensions

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.MenuItem


fun MenuItem.setTint(context: Context, resourceId: Int) {
    val typedValue = TypedValue()
    context.theme.resolveAttribute(resourceId, typedValue, true)
    this.setIconTintList(ColorStateList.valueOf(typedValue.data))
}
