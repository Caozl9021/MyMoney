package com.example.billkit.ocr

import android.graphics.Bitmap

object BitmapTools {

    /**
     * 只保留状态栏以下、屏幕上半部分。
     * 账单的金额/商户/时间永远在上半屏，裁掉下半屏能显著降低 OCR 耗时与误识别
     * （下半屏通常是推荐位、按钮、键盘）。
     */
    fun cropTopHalf(bitmap: Bitmap, statusBarPx: Int = 0): Bitmap {
        val top = statusBarPx.coerceIn(0, bitmap.height / 4)
        val height = minOf(bitmap.height - top, bitmap.height / 2)
        if (height <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
    }

    /** 长边缩到 [maxSize] 以内，OCR 再快一点；返回值可能是原图。 */
    fun downscale(bitmap: Bitmap, maxSize: Int = 1440): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxSide
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
