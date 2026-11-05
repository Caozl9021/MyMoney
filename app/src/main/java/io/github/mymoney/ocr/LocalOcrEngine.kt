package io.github.mymoney.ocr

import android.content.Context
import android.os.Build
import android.os.Process
import android.graphics.Bitmap
import com.example.billkit.visual.VisualText
import io.github.mymoney.ocr.research.V6ResearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bundled V6 Tiny only. Caller owns the bitmap; native work completes before cancellation returns. */
class LocalOcrEngine(context: Context) {
    private val engine by lazy { V6ResearchEngine(context.applicationContext ?: context) }

    suspend fun recognize(bitmap: Bitmap): VisualText = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(isSupported) { "当前离线识别组件需要 Android 11 或更高版本及 64 位设备" }
        require(!bitmap.isRecycled && bitmap.width in 1..1800 && bitmap.height in 1..1800) {
            "图片尺寸无效，请裁剪后重试"
        }
        // Blocking native call deliberately runs to completion before the caller can recycle input.
        val result = try {
            engine.recognizeBlocking(bitmap)
        } catch (error: LinkageError) {
            // Preserve the cause for diagnosis while allowing the caller's Exception fallback.
            // Do not catch VM failures such as OutOfMemoryError.
            throw IllegalStateException("离线识别组件与当前系统不兼容，请使用其他识别方式", error)
        }
        currentCoroutineContext().ensureActive()
        result
    }

    companion object {
        const val ENGINE = "paddle-v6-tiny-pinned"
        // Current pinned native binaries import LIBC_Q/R symbols, despite lower AAR manifests.
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= 30 && Process.is64Bit() && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    }
}
