package io.github.mymoney

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.billkit.visual.VisualLine
import com.example.billkit.visual.VisualText
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Offline, bundled Chinese recognizer. Does not own/recycle the caller's bitmap. */
class MlKitResearchEngine(testPackageContext: Context) {
    // Main APK deliberately has no ML Kit init provider/components/models. Preserve the
    // instrumentation APK package, assets and class loader during component discovery.
    private val mlkitContext = object : ContextWrapper(testPackageContext) {
        override fun getApplicationContext(): Context = this
    }

    init {
        // Verified against the pinned common18.11.0 public SDK-internal entry point.
        // This discovers MlKitComponentDiscoveryService in the test package manifest.
        MlKitContext.initializeIfNeeded(mlkitContext)
    }

    suspend fun recognize(bitmap: Bitmap): VisualText = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(!bitmap.isRecycled && bitmap.width in 1..1800 && bitmap.height in 1..1800) {
            "图片尺寸无效，请裁剪后重试"
        }
        val started = SystemClock.elapsedRealtime()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            // Intentionally not suspendCancellableCoroutine: ML Kit may still own the input after
            // coroutine cancellation. Await actual task completion before callers can recycle it.
            val result = suspendCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
                    .addOnCanceledListener { continuation.resumeWithException(kotlinx.coroutines.CancellationException("识别已取消")) }
            }
            currentCoroutineContext().ensureActive()
            VisualText(bitmap.width, bitmap.height, result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val confidence = line.confidence.takeIf { it.isFinite() && it in 0f..1f }
                    VisualLine(line.text, box.left.coerceIn(0, bitmap.width), box.top.coerceIn(0, bitmap.height),
                        box.right.coerceIn(0, bitmap.width), box.bottom.coerceIn(0, bitmap.height), confidence)
                }
            }, ENGINE, SystemClock.elapsedRealtime() - started)
        } finally {
            recognizer.close()
        }
    }

    companion object {
        const val ENGINE = "mlkit-chinese-bundled-16.0.1"
    }
}
