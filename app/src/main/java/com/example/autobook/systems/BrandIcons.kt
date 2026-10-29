package com.example.autobook.systems

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.autobook.R

/** Colored companion to the alpha-only status icon; both are generated from logo/logo1.png. */
object BrandIcons {
    fun large(context: Context): Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_brand_color)
}
