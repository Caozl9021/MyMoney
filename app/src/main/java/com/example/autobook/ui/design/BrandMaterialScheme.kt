package com.example.autobook.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/** Explicit brand and neutral roles prevent default Material purple from leaking into containers. */
fun AbPalette.materialScheme(brand: BrandColorRoles, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val inverse = if (dark) LightPalette else DarkPalette
    return base.copy(
        primary = brand.primary, onPrimary = brand.onPrimary,
        primaryContainer = brand.container, onPrimaryContainer = brand.onContainer,
        inversePrimary = brand.inversePrimary,
        secondary = brand.primary, onSecondary = brand.onPrimary,
        secondaryContainer = brand.container, onSecondaryContainer = brand.onContainer,
        tertiary = brand.primary, onTertiary = brand.onPrimary,
        tertiaryContainer = brand.container, onTertiaryContainer = brand.onContainer,
        background = pageBg, onBackground = label,
        surface = cardBg, onSurface = label,
        surfaceVariant = elevatedBg, onSurfaceVariant = secondaryLabel,
        surfaceTint = brand.primary,
        inverseSurface = inverse.cardBg, inverseOnSurface = inverse.label,
        error = red, onError = Color(ColorContrast.contrastingForeground(red.toArgb())),
        errorContainer = lerp(cardBg, red, .12f), onErrorContainer = label,
        outline = separator, outlineVariant = separator.copy(alpha = separator.alpha * .65f),
        scrim = Color.Black,
        surfaceBright = elevatedBg, surfaceDim = pageBg,
        surfaceContainer = cardBg, surfaceContainerHigh = elevatedBg,
        surfaceContainerHighest = elevatedBg, surfaceContainerLow = cardBg,
        surfaceContainerLowest = pageBg,
    )
}
