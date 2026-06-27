package com.datafire.app

import android.graphics.drawable.Drawable

data class AppModel(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val uid: Int,
    var isBlocked: Boolean = false,
    var isSelected: Boolean = false,
    val isSystemApp: Boolean = false
)
