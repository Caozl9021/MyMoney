package io.github.mymoney

import io.github.mymoney.ui.design.ColorContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastContrastTest {
    @Test fun paleDeviceBlueUsesDarkText() {
        assertEquals(0xFF000000.toInt(), ColorContrast.contrastingForeground(0xFFA1C5FF.toInt()))
    }

    @Test fun darkAccentUsesLightText() {
        assertEquals(0xFFFFFFFF.toInt(), ColorContrast.contrastingForeground(0xFF00382C.toInt()))
    }

    @Test fun whiteAndBlackRemainReadable() {
        assertEquals(0xFF000000.toInt(), ColorContrast.contrastingForeground(0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorContrast.contrastingForeground(0xFF000000.toInt()))
    }

    @Test fun selectedTextMeetsNormalTextContrastAcrossRgbCube() {
        fun luminance(rgb: Int): Double {
            val values = intArrayOf((rgb ushr 16) and 255, (rgb ushr 8) and 255, rgb and 255).map {
                val c = it / 255.0
                if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return values[0] * 0.2126 + values[1] * 0.7152 + values[2] * 0.0722
        }
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val color = (0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
            val background = luminance(color)
            val text = luminance(ColorContrast.contrastingForeground(color))
            val ratio = (maxOf(background, text) + 0.05) / (minOf(background, text) + 0.05)
            assertTrue("Text contrast $ratio for ${color.toUInt().toString(16)}", ratio >= 4.5)
        }
    }
}
