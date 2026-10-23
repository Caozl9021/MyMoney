package com.example.billkit.ocr

import android.graphics.Bitmap
import com.example.billkit.OcrNormalizer
import com.example.billkit.UiSnapshot

/**
 * OCR 兜底：截图 → 裁剪 → 识别 → 归一化 → 与无障碍/root dump 完全同构的 UiSnapshot。
 *
 * 什么时候用（由调用方决定）：
 * - 分类器返回 null（页面规则没覆盖，比如新版页面、H5 页面）
 * - 分类命中但没有金额（页面结构变了）
 *
 * 成本：一次 ML Kit 中文识别大约 150~400ms，所以一定要有冷却，不要每个事件都跑。
 */
class OcrFallback(
    private val engine: OcrEngine,
) {

    suspend fun snapshot(
        packageName: String,
        activityClass: String?,
        screenshot: Bitmap,
        statusBarPx: Int = 0,
        maxSize: Int = 1440,
    ): UiSnapshot? {
        val cropped = BitmapTools.cropTopHalf(screenshot, statusBarPx)
        val scaled = BitmapTools.downscale(cropped, maxSize)

        val lines = try {
            engine.recognize(scaled)
        } catch (e: Exception) {
            emptyList()
        } finally {
            if (scaled !== screenshot) scaled.recycle()
            if (cropped !== screenshot && cropped !== scaled) cropped.recycle()
        }

        val collected = OcrNormalizer.normalizeResult(lines)
        val items = collected.items
        if (items.size < 3) return null

        return UiSnapshot(
            packageName = packageName,
            activityClass = activityClass,
            items = items,
            truncated = collected.truncated,
            source = UiSnapshot.Source.ROOT_OCR,
        )
    }

    fun close() = engine.close()
}
