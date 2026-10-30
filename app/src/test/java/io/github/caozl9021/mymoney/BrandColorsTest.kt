package io.github.caozl9021.mymoney

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import io.github.caozl9021.mymoney.systems.LauncherIconChoice
import io.github.caozl9021.mymoney.ui.design.*
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
    @Test fun approvedLightBrandColorsMapFromStableIconIds() {
        val expected = mapOf("logo1" to 0xFF00847D, "logo2" to 0xFF0078D4, "logo3" to 0xFFCC493E)
        expected.forEach { (id, color) ->
            assertEquals(color.toInt(), BrandColors.roles(LauncherIconChoice.fromId(id)!!, false).primary.toArgb())
        }
    }
    @Test fun everyPalettePairHasReadableText() {
        for (dark in listOf(false, true)) for (choice in LauncherIconChoice.entries) {
            val r = BrandColors.roles(choice, dark)
            assertTrue(contrast(r.primary, r.onPrimary) >= 4.5)
            assertTrue(contrast(r.container, r.onContainer) >= 4.5)
        }
    }
    @Test fun allIntermediateThemeFramesKeepPrimaryAndContainerReadable() {
        for (dark in listOf(false, true)) for (a in LauncherIconChoice.entries) for (b in LauncherIconChoice.entries) {
            val old = BrandColors.roles(a, dark); val new = BrandColors.roles(b, dark)
            for (step in 0..64) {
                val t = step / 64f
                val frame = BrandColors.readable(BrandColorRoles(
                    lerp(old.primary,new.primary,t),lerp(old.onPrimary,new.onPrimary,t),
                    lerp(old.container,new.container,t),lerp(old.onContainer,new.onContainer,t),
                    lerp(old.inversePrimary,new.inversePrimary,t)))
                assertTrue("Primary $a->$b dark=$dark t=$t", contrast(frame.primary,frame.onPrimary) >= 4.5)
                assertTrue("Container $a->$b dark=$dark t=$t", contrast(frame.container,frame.onContainer) >= 4.5)
            }
        }
    }
    @Test fun endpointBrandColorsStayExactWhileInterpolationOvershootIsCorrected() {
        for (dark in listOf(false,true)) for (choice in LauncherIconChoice.entries) {
            val roles = BrandColors.roles(choice,dark)
            assertEquals(roles,BrandColors.readable(roles))
        }
        val a=BrandColors.roles(LauncherIconChoice.GREEN,false)
        val b=BrandColors.roles(LauncherIconChoice.BLUE,false)
        val bad=a.copy(primary=lerp(a.primary,b.primary,.34375f))
        assertTrue(contrast(bad.primary,bad.onPrimary)<4.5)
        val fixed=BrandColors.readable(bad)
        assertTrue(contrast(fixed.primary,fixed.onPrimary)>=4.5)
        assertEquals(bad.onPrimary,fixed.onPrimary)
    }
    @Test fun semanticAndIdentityColorsAreNotRepainted() {
        for (base in listOf(LightPalette, DarkPalette)) for (choice in LauncherIconChoice.entries) {
            val actual = BrandColors.apply(base, BrandColors.roles(choice, base === DarkPalette))
            assertEquals(base, actual.copy(accent=base.accent,onAccent=base.onAccent,glassStrokeTop=base.glassStrokeTop))
        }
    }
    @Test fun everyMaterialBrandContainerUsesChosenPalette() {
        for (dark in listOf(false, true)) for (choice in LauncherIconChoice.entries) {
            val roles = BrandColors.roles(choice, dark)
            val base = if (dark) DarkPalette else LightPalette
            val p = BrandColors.apply(base, roles); val m = p.materialScheme(roles, dark)
            assertEquals(roles.primary,m.primary);assertEquals(roles.primary,m.secondary);assertEquals(roles.primary,m.tertiary)
            assertEquals(roles.container,m.primaryContainer);assertEquals(roles.container,m.secondaryContainer);assertEquals(roles.container,m.tertiaryContainer)
            assertEquals(roles.onContainer,m.onPrimaryContainer);assertEquals(roles.onContainer,m.onSecondaryContainer);assertEquals(roles.onContainer,m.onTertiaryContainer)
            assertEquals(roles.primary,m.surfaceTint);assertEquals(roles.inversePrimary,m.inversePrimary)
            assertEquals(base.red,m.error);assertTrue(contrast(m.error,m.onError)>=4.5)
            assertTrue(contrast(m.errorContainer,m.onErrorContainer)>=4.5)
            assertEquals(base.pageBg,m.background);assertEquals(base.cardBg,m.surface)
        }
    }
    @Test fun reducingMotionUsesNoBrandTween() {
        assertEquals(0,BrandMotion.duration(true));assertEquals(320,BrandMotion.duration(false))
    }
}
