package io.github.mymoney.systems

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.SizeF
import kotlin.math.roundToInt

/**
 * Renders Apple-Health-inspired full-height capsule tracks with unified brand-hue bars.
 * All text is rendered natively via TextViews outside this Bitmap.
 */
object WidgetChartRenderer {

    fun renderSevenDayChart(
        context: Context,
        summary: SevenDaySummary,
        palette: WidgetPalette,
        bucket: WidgetSizeBucket,
        size: SizeF?,
        masked: Boolean,
    ): Bitmap {
        val rawDensity = context.resources.displayMetrics.density
        val scale = rawDensity.coerceIn(2f, 3.5f)
        val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.4f)
        val totalW = (size?.width ?: defaultWidthDp(bucket)).coerceAtLeast(96f)
        val totalH = (size?.height ?: defaultHeightDp(bucket)).coerceAtLeast(44f)

        val (horizontalReserveDp, verticalReserveDp) = when (bucket) {
            WidgetSizeBucket.STRIP_HORIZONTAL -> 30f to 34f
            WidgetSizeBucket.SQUARE_COMPACT -> 28f to (81f * fontScale)
            WidgetSizeBucket.TALL_CARD -> 30f to (122f * fontScale)
            else -> 30f to (104f * fontScale)
        }
        val chartWidthDp = (totalW - horizontalReserveDp).coerceIn(72f, 440f)
        val chartHeightDp = (totalH - verticalReserveDp).coerceIn(32f, 220f)

        val wPx = (chartWidthDp * scale).roundToInt().coerceIn(180, 1440)
        val hPx = (chartHeightDp * scale).roundToInt().coerceIn(80, 720)
        val bitmap = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val topPad = 2f * scale
        val bottomPad = 2f * scale
        val plotTop = topPad
        val plotBottom = (hPx - bottomPad).coerceAtLeast(plotTop + 10f * scale)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(8f)

        val bars = summary.bars
        val count = bars.size.coerceAtLeast(1)
        val slotW = wPx.toFloat() / count
        val barWidthRatio = when (bucket) {
            WidgetSizeBucket.STRIP_HORIZONTAL -> 0.56f
            WidgetSizeBucket.SQUARE_COMPACT -> 0.58f
            else -> 0.54f
        }
        val barW = (slotW * barWidthRatio).coerceIn(8f * scale, 26f * scale)
        val radius = barW / 2f
        val maxVal = summary.maxCents.coerceAtLeast(1L)

        // Average dashed line only across gaps between capsule tracks so dashes never bleed through translucent tracks.
        if (!masked && summary.maxCents > 0L && summary.averageCents > 0L) {
            val avgY = plotBottom - (summary.averageCents.toFloat() / maxVal) * plotHeight
            val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.chartAverageLine
                strokeWidth = 1.2f * scale
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(4f * scale, 3f * scale), 0f)
            }
            var prevRight = 0f
            for (i in 0 until count) {
                val cx = slotW * i + slotW / 2f
                val left = cx - barW / 2f
                val right = cx + barW / 2f
                if (left > prevRight + 1f) {
                    canvas.drawLine(prevRight, avgY, left, avgY, dashPaint)
                }
                prevRight = right
            }
            if (wPx.toFloat() > prevRight + 1f) {
                canvas.drawLine(prevRight, avgY, wPx.toFloat(), avgY, dashPaint)
            }
        }

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.chartTrack }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val activeSecondaryColor = withAlpha(palette.accent, if (palette.darkSurface) 132 else 120)

        for (i in bars.indices) {
            val bar = bars[i]
            val cx = slotW * i + slotW / 2f
            val left = cx - barW / 2f
            val right = cx + barW / 2f

            // Full-height capsule track anchors all 7 slots uniformly
            val trackRect = RectF(left, plotTop, right, plotBottom)
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

            if (bar.expenseCents > 0L) {
                val ratio = bar.expenseCents.toFloat() / maxVal
                val minVisible = minOf(barW, plotHeight) * 0.72f
                val barH = (ratio * plotHeight).coerceIn(minVisible, plotHeight)
                val top = plotBottom - barH
                barPaint.color = if (bar.isToday) palette.accent else activeSecondaryColor
                val barRect = RectF(left, top, right, plotBottom)
                val r = radius.coerceAtMost(barH / 2f)
                canvas.drawRoundRect(barRect, r, r, barPaint)
            }
        }
        return bitmap
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun defaultWidthDp(bucket: WidgetSizeBucket): Float = when (bucket) {
        WidgetSizeBucket.STRIP_HORIZONTAL -> 230f
        WidgetSizeBucket.SQUARE_COMPACT -> 154f
        WidgetSizeBucket.TALL_CARD -> 156f
        WidgetSizeBucket.WIDE_MEDIUM -> 236f
        else -> 316f
    }

    private fun defaultHeightDp(bucket: WidgetSizeBucket): Float = when (bucket) {
        WidgetSizeBucket.STRIP_HORIZONTAL -> 76f
        WidgetSizeBucket.TALL_CARD -> 226f
        else -> 154f
    }
}
