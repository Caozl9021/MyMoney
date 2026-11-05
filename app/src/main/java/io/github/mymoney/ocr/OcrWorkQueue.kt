package io.github.mymoney.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.billkit.visual.VisualText
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** At most one decoded/native request plus two compressed waiting images. */
object OcrWorkQueue {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val admitted = AtomicInteger()
    private val permit = Semaphore(1)
    fun prepare(bitmap: Bitmap): ByteArray {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片已失效" }
        val edge = maxOf(bitmap.width, bitmap.height)
        val image = if (edge > 1800) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * 1800L / edge).toInt().coerceAtLeast(1),
            (bitmap.height * 1800L / edge).toInt().coerceAtLeast(1), true) else bitmap
        try {
            val bytes = ByteArrayOutputStream().use { out ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            }
            require(bytes.size <= MAX_BYTES) { "图片内容过大，请裁剪后重试" }
            return bytes
        } finally { if (image !== bitmap) image.recycle() }
    }
    suspend fun readImage(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val compressed = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= MAX_BYTES) { "图片文件超过5MiB，请裁剪后重试" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: error("无法读取所选图片")
        require(compressed.isNotEmpty()) { "图片为空" }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(compressed, 0, compressed.size, opts)
        require(opts.outWidth in 1..20000 && opts.outHeight in 1..20000 &&
            opts.outWidth.toLong() * opts.outHeight <= 40_000_000L) { "图片尺寸过大或格式不支持" }
        // Keep only compressed bytes while waiting; decoding is inside the global permit.
        compressed
    }

    suspend fun recognize(context: Context, image: ByteArray, allowed: () -> Boolean = { true }): VisualText {
        require(image.isNotEmpty() && image.size <= MAX_BYTES) { "图片过大或已失效" }
        check(admitted.incrementAndGet() <= 3) { admitted.decrementAndGet(); "识别任务较多，请稍后重试" }
        try {
            return permit.withPermit {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive(); check(allowed()) { "识别已停止" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                    require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L) { "图片尺寸不支持" }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3600) sample *= 2
                    val decoded = BitmapFactory.decodeByteArray(image, 0, image.size,
                        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: error("图片无法读取")
                    val edge = maxOf(decoded.width, decoded.height)
                    val bitmap = if (edge > 1800) try {
                        Bitmap.createScaledBitmap(decoded, (decoded.width * 1800L / edge).toInt().coerceAtLeast(1),
                            (decoded.height * 1800L / edge).toInt().coerceAtLeast(1), true)
                    } finally { decoded.recycle() } else decoded
                    try {
                        // The engine waits for actual native completion, even after coroutine cancellation.
                        val result = LocalOcrEngine(context).recognize(bitmap)
                        currentCoroutineContext().ensureActive(); check(allowed()) { "识别已停止" }; result
                    } finally { bitmap.recycle() }
                }
            }
        } finally { admitted.decrementAndGet() }
    }
}
