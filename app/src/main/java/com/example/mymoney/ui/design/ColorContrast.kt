package com.example.mymoney.ui.design

/** Device-independent color contrast helpers for labels and chart/identity marks. */
object ColorContrast {
    fun luminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= .04045) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
        }
        return .2126 * linear((argb ushr 16) and 255) +
            .7152 * linear((argb ushr 8) and 255) + .0722 * linear(argb and 255)
    }

    fun ratio(a: Int, b: Int): Double {
        val first = luminance(a); val second = luminance(b)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }

    /** Pure sRGB contrast calculation; the higher-contrast black/white choice is always >= 4.5:1. */
    internal fun contrastingForeground(argb: Int): Int {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * linear((argb ushr 16) and 255) +
            0.7152 * linear((argb ushr 8) and 255) + 0.0722 * linear(argb and 255)
        val blackContrast = (luminance + 0.05) / 0.05
        val whiteContrast = 1.05 / (luminance + 0.05)
        return if (blackContrast >= whiteContrast) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
}
