package io.github.mymoney

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import io.github.mymoney.ui.design.*
import org.junit.Assert.*
import org.junit.Test

class BrandColorsTest {
    private fun luminance(c: Color): Double {
        val n = c.toArgb()
        fun channel(shift: Int): Double {
            val x = ((n ushr shift) and 255) / 255.0
            return if (x <= .04045) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }

    @Test fun approvedLightBrandColorsMapFromStableThemeIds() {
        val expected = mapOf(
            "blue" to 0xFF0070F5,
            "green" to 0xFF00847D,
            "red" to 0xFFCC493E,
            "gold" to 0xFFB25E00,
        )
        expected.forEach { (id, color) ->
            assertEquals(color.toInt(), BrandColors.roles(BrandTheme.fromId(id)!!, false).primary.toArgb())
        }
    }

    @Test fun defaultAndLegacyBrandThemeResolutionFollowsMigrationPolicy() {
        assertEquals(BrandTheme.BLUE, Appearance.DEFAULT_BRAND_THEME)
        assertEquals(BrandTheme.BLUE, Appearance.resolveBrandTheme(null, null))
        assertEquals(BrandTheme.BLUE, Appearance.resolveBrandTheme(null, "logo1"))
        assertEquals(BrandTheme.BLUE, Appearance.resolveBrandTheme(null, "logo2"))
        assertEquals(BrandTheme.RED, Appearance.resolveBrandTheme(null, "logo3"))
        assertEquals(BrandTheme.GREEN, Appearance.resolveBrandTheme("green", "logo3"))
        assertEquals(BrandTheme.GOLD, Appearance.resolveBrandTheme("gold", null))
        assertEquals(BrandTheme.BLUE, Appearance.resolveBrandTheme("invalid", "logo1"))
    }

    @Test fun everyPalettePairHasReadableText() {
        for (dark in listOf(false, true)) for (theme in BrandTheme.entries) {
            val r = BrandColors.roles(theme, dark)
            assertTrue("primary $theme dark=$dark", contrast(r.primary, r.onPrimary) >= 4.5)
            assertTrue("container $theme dark=$dark", contrast(r.container, r.onContainer) >= 4.5)
        }
    }

    @Test fun allIntermediateThemeFramesKeepPrimaryAndContainerReadable() {
        for (dark in listOf(false, true)) for (a in BrandTheme.entries) for (b in BrandTheme.entries) {
            val old = BrandColors.roles(a, dark); val new = BrandColors.roles(b, dark)
            for (step in 0..64) {
                val t = step / 64f
                val frame = BrandColors.readable(BrandColorRoles(
                    lerp(old.primary, new.primary, t), lerp(old.onPrimary, new.onPrimary, t),
                    lerp(old.container, new.container, t), lerp(old.onContainer, new.onContainer, t),
                    lerp(old.inversePrimary, new.inversePrimary, t),
                ))
                assertTrue("Primary $a->$b dark=$dark t=$t", contrast(frame.primary, frame.onPrimary) >= 4.5)
                assertTrue("Container $a->$b dark=$dark t=$t", contrast(frame.container, frame.onContainer) >= 4.5)
            }
        }
    }

    @Test fun endpointBrandColorsStayExactWhileInterpolationOvershootIsCorrected() {
        for (dark in listOf(false, true)) for (theme in BrandTheme.entries) {
            val roles = BrandColors.roles(theme, dark)
            assertEquals(roles, BrandColors.readable(roles))
        }
        val a = BrandColors.roles(BrandTheme.BLUE, false)
        val b = BrandColors.roles(BrandTheme.RED, false)
        val bad = a.copy(primary = lerp(a.primary, b.primary, 27 / 64f))
        assertTrue(contrast(bad.primary, bad.onPrimary) < 4.5)
        val fixed = BrandColors.readable(bad)
        assertTrue(contrast(fixed.primary, fixed.onPrimary) >= 4.5)
        assertEquals(bad.onPrimary, fixed.onPrimary)
    }

    @Test fun semanticAndIdentityColorsAreNotRepainted() {
        for (base in listOf(LightPalette, DarkPalette)) for (theme in BrandTheme.entries) {
            val actual = BrandColors.apply(base, BrandColors.roles(theme, base === DarkPalette))
            assertEquals(base, actual.copy(accent = base.accent, onAccent = base.onAccent, glassStrokeTop = base.glassStrokeTop))
        }
    }

    @Test fun everyMaterialBrandContainerUsesChosenPalette() {
        for (dark in listOf(false, true)) for (theme in BrandTheme.entries) {
            val roles = BrandColors.roles(theme, dark)
            val base = if (dark) DarkPalette else LightPalette
            val p = BrandColors.apply(base, roles); val m = p.materialScheme(roles, dark)
            assertEquals(roles.primary, m.primary); assertEquals(roles.primary, m.secondary); assertEquals(roles.primary, m.tertiary)
            assertEquals(roles.container, m.primaryContainer); assertEquals(roles.container, m.secondaryContainer); assertEquals(roles.container, m.tertiaryContainer)
            assertEquals(roles.onContainer, m.onPrimaryContainer); assertEquals(roles.onContainer, m.onSecondaryContainer); assertEquals(roles.onContainer, m.onTertiaryContainer)
            assertEquals(roles.primary, m.surfaceTint); assertEquals(roles.inversePrimary, m.inversePrimary)
            assertEquals(base.red, m.error); assertTrue(contrast(m.error, m.onError) >= 4.5)
            assertTrue(contrast(m.errorContainer, m.onErrorContainer) >= 4.5)
            assertEquals(base.pageBg, m.background); assertEquals(base.cardBg, m.surface)
        }
    }

    @Test fun reducingMotionUsesNoBrandTween() {
        assertEquals(0, BrandMotion.duration(true))
        assertEquals(220, BrandMotion.duration(false))
    }
}
