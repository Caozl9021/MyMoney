package io.github.mymoney.ocr.research

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import com.example.billkit.visual.VisualLine
import com.example.billkit.visual.VisualText
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.OCREngine
import com.paddle.ocr.util.OpenCVUtils

/** Pinned V6 native adapter, shared by production and same-image comparison tests. */
class V6ResearchEngine(context: Context) {
    // Instrumentation's package context has the test APK assets but may have no applicationContext.
    // Keep its assets and supply a non-null application scope to the upstream session loader.
    private val context = object : android.content.ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
    }
    fun recognizeBlocking(bitmap: Bitmap): VisualText {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(OpenCVUtils.init(context))
        val started = SystemClock.elapsedRealtime()
        val engine = OCREngine(context, PaddleOCRConfig(), EngineConfig(numThreads = 2),
            "models/v6/det/inference.onnx", "models/v6/rec/inference.onnx", "models/v6/rec/inference.yml")
        try {
            val result = engine.run(bitmap)
            return VisualText(bitmap.width, bitmap.height, result.results.map { result ->
                VisualLine(result.text, result.box.points.minOf { it.x }.toInt(), result.box.points.minOf { it.y }.toInt(),
                    result.box.points.maxOf { it.x }.toInt(), result.box.points.maxOf { it.y }.toInt(),
                    result.confidence.takeIf { it.isFinite() && it in 0f..1f })
            }, "paddle-v6-tiny-pinned", SystemClock.elapsedRealtime() - started)
        } finally { engine.release() }
    }
}
