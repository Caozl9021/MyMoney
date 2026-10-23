package com.example.billkit.root

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.billkit.ocr.BitmapTools

/**
 * root 路径的截图：`screencap -p` 再用管道读回字节。
 * 走二进制管道而不是临时文件，省掉 /data/local/tmp 的权限问题。
 */
object RootScreenshot {

    fun capture(): Bitmap? {
        val bytes = RootShell.execBytes("screencap -p") ?: return null
        if (bytes.size < 8) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** 只留状态栏以下、屏幕上半部分（实现见 [BitmapTools.cropTopHalf]） */
    fun cropBillArea(bitmap: Bitmap, statusBarPx: Int = 0): Bitmap =
        BitmapTools.cropTopHalf(bitmap, statusBarPx)
}
