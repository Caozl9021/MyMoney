package io.github.mymoney.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillRowTextTest {
    @Test fun titlePrefersSubCategoryIncludingMissingParent() {
        assertEquals("午餐", billRowTitle("餐饮", "午餐"))
        assertEquals("午餐", billRowTitle(null, "午餐"))
        assertEquals("午餐", billRowTitle("  ", "午餐"))
    }

    @Test fun titleFallsBackToParentThenUncategorized() {
        assertEquals("餐饮", billRowTitle("餐饮", null))
        assertEquals("餐饮", billRowTitle("餐饮", "  "))
        assertEquals("未分类", billRowTitle(null, null))
        assertEquals("未分类", billRowTitle("   ", " \n "))
    }

    @Test fun subtitleUsesTimeThenNoteWithoutParentCategory() {
        assertEquals("12:22 · 辣", billRowSubtitle("餐饮", "午餐", "老王面馆", "辣", "12:22", false))
        assertEquals("12:22 · 辣", billRowSubtitle("餐饮", null, "老王面馆", "辣", "12:22", false))
        assertEquals("12:22", billRowSubtitle(null, "午餐", null, null, "12:22", false))
        assertEquals("", billRowSubtitle(null, null, "  ", "  ", null, false))
    }

    @Test fun subtitleFallsBackToNoteOrMerchantAndMarksRefund() {
        assertEquals("退款 · 12:22 · 备注", billRowSubtitle("餐饮", "午餐", "", "备注", "12:22", true))
        assertEquals("备注", billRowSubtitle(null, null, null, "备注", null, false))
        assertEquals("同名商户", billRowSubtitle("餐饮", "餐饮", "同名商户", null, null, false))
    }

    @Test fun categoryDisplayJoinsParentAndSubCategoryForDetail() {
        assertEquals("餐饮 · 午餐", billCategoryDisplay("餐饮", "午餐"))
        assertEquals("餐饮", billCategoryDisplay("餐饮", null))
        assertEquals("餐饮", billCategoryDisplay("餐饮", "餐饮"))
        assertEquals("午餐", billCategoryDisplay(null, "午餐"))
        assertEquals("转账", billCategoryDisplay("餐饮", "午餐", "TRANSFER"))
        assertEquals(null, billCategoryDisplay("  ", null))
    }

    @Test fun reimburseStatusMapsToGrayOrYouthfulYellowAmountColor() {
        val light = LightPalette
        assertEquals(light.expense, billRowAmountColor(light, "EXPENSE", "NONE"))
        assertEquals(light.secondaryLabel, billRowAmountColor(light, "EXPENSE", "REIMBURSED"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF5A623), billRowAmountColor(light, "EXPENSE", "PENDING"))

        val dark = DarkPalette
        assertEquals(dark.expense, billRowAmountColor(dark, "EXPENSE", "NONE"))
        assertEquals(dark.secondaryLabel, billRowAmountColor(dark, "EXPENSE", "REIMBURSED"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFD60A), billRowAmountColor(dark, "EXPENSE", "PENDING"))
    }

    @Test fun amountTintKeepsExpenseDistinctFromDangerRed() {
        val p = LightPalette
        assertEquals(p.expense, amountTint(p, "EXPENSE"))
        assertEquals(p.income, amountTint(p, "INCOME"))
        assertEquals(p.refund, amountTint(p, "REFUND"))
        assertEquals(p.transfer, amountTint(p, "TRANSFER"))
        assertEquals(p.expense, amountTint(p, "SOMETHING_UNKNOWN"))
        // 支出色不能复用删除/错误的红（那是另一层语义）。
        assertNotEquals(p.red, amountTint(p, "EXPENSE"))
        assertNotEquals(p.red, p.expense)
    }

    @Test fun darkPaletteAlsoSeparatesExpenseFromDanger() {
        val p = DarkPalette
        assertNotEquals(p.red, p.expense)
        assertEquals(p.income, amountTint(p, "INCOME"))
        assertEquals(p.refund, amountTint(p, "REFUND"))
    }

    @Test fun semanticColorsReachWcagContrastOnTheirSurfaces() {
        assertTrue(contrast(LightPalette.expense, 0xFFFFFFFF.toInt()) >= 4.5)
        assertTrue(contrast(LightPalette.income, 0xFFFFFFFF.toInt()) >= 4.5)
        assertTrue(contrast(LightPalette.refund, 0xFFFFFFFF.toInt()) >= 4.5)
        assertTrue(contrast(LightPalette.heroAmount, LightPalette.heroSurfaceBottom.toArgbInt()) >= 4.5)
        assertTrue(contrast(LightPalette.heroSecondaryLabel, LightPalette.heroSurfaceBottom.toArgbInt()) >= 4.5)
        assertTrue(contrast(DarkPalette.expense, DarkPalette.cardBg.toArgbInt()) >= 4.5)
        assertTrue(contrast(DarkPalette.income, DarkPalette.cardBg.toArgbInt()) >= 4.5)
        assertTrue(contrast(DarkPalette.refund, DarkPalette.cardBg.toArgbInt()) >= 4.5)
        assertTrue(contrast(DarkPalette.heroAmount, DarkPalette.heroSurfaceBottom.toArgbInt()) >= 4.5)
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private fun contrast(foreground: androidx.compose.ui.graphics.Color, background: Int): Double =
        contrast(foreground.toArgbInt(), background)

    /** WCAG 2.1 相对亮度对比度，忽略 alpha（配色 token 都是不透明色）。 */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
