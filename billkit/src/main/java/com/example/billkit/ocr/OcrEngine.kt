package com.example.billkit.ocr

import android.graphics.Bitmap

/**
 * OCR 抽象。库只定义接口，具体实现由宿主注入
 * （示例见 app 模块的 `MlKitOcrEngine`，也可以换成 PaddleOCR / 云端）。
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<String>

    fun close() {}
}
