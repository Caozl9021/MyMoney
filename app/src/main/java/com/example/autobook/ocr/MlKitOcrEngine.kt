package com.example.autobook.ocr

import android.graphics.Bitmap
import com.example.billkit.ocr.OcrEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * :billkit 的 OCR 实现（ML Kit 中文，离线、免费）。
 * 想换 PaddleOCR 只需要再写一个 OcrEngine 实现，库和规则都不用动。
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): List<String> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    cont.resume(
                        text.textBlocks
                            .flatMap { it.lines }
                            .map { it.text.trim() }
                            .filter { it.isNotEmpty() }
                    )
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    override fun close() {
        recognizer.close()
    }
}
