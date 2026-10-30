package com.example.mymoney.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.example.mymoney.systems.LauncherIconChoice

/** One source for all product accent roles; never reads wallpaper or Android accent resources. */
@Immutable
data class BrandColorRoles(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
    val inversePrimary: Color,
)

object BrandColors {
    fun roles(choice: LauncherIconChoice, dark: Boolean): BrandColorRoles {
        val light = when (choice) {
            LauncherIconChoice.GREEN -> Color(0xFF00847D)
            LauncherIconChoice.BLUE -> Color(0xFF0078D4)
            LauncherIconChoice.RED -> Color(0xFFCC493E)
        }
        val bright = when (choice) {
            LauncherIconChoice.GREEN -> Color(0xFF50C9BF)
            LauncherIconChoice.BLUE -> Color(0xFF69B4F3)
            LauncherIconChoice.RED -> Color(0xFFF28B7E)
        }
        val ink = when (choice) {
            LauncherIconChoice.GREEN -> Color(0xFF062D29)
            LauncherIconChoice.BLUE -> Color(0xFF082C48)
            LauncherIconChoice.RED -> Color(0xFF3C1712)
        }
        val container = when (choice) {
            LauncherIconChoice.GREEN -> if (dark) Color(0xFF123A36) else Color(0xFFE5F4F1)
            LauncherIconChoice.BLUE -> if (dark) Color(0xFF102D45) else Color(0xFFE6F2FD)
            LauncherIconChoice.RED -> if (dark) Color(0xFF4B2722) else Color(0xFFFCEBE8)
        }
        val containerText = when (choice) {
            LauncherIconChoice.GREEN -> if (dark) Color(0xFFB7F0E8) else Color(0xFF004C47)
            LauncherIconChoice.BLUE -> if (dark) Color(0xFFC5E4FF) else Color(0xFF004B83)
            LauncherIconChoice.RED -> if (dark) Color(0xFFFFDAD4) else Color(0xFF81271F)
        }
        return BrandColorRoles(if (dark) bright else light, if (dark) ink else Color.White,
            container, containerText, if (dark) light else bright)
    }

    /** Oklab interpolation can brighten intermediate frames; keep text legible without swapping it. */
    fun readable(roles: BrandColorRoles): BrandColorRoles = roles.copy(
        primary = readableBackground(roles.primary, roles.onPrimary),
        container = readableBackground(roles.container, roles.onContainer),
    )

    private fun readableBackground(background: Color, foreground: Color): Color {
        val text = foreground.toArgb()
        if (ColorContrast.ratio(background.toArgb(), text) >= 4.5) return background
        val endpoint = if (ColorContrast.ratio(Color.Black.toArgb(), text) >
            ColorContrast.ratio(Color.White.toArgb(), text)) Color.Black else Color.White
        var low = 0f; var high = 1f
        repeat(14) {
            val mid = (low + high) / 2
            if (ColorContrast.ratio(lerp(background, endpoint, mid).toArgb(), text) >= 4.52) high = mid
            else low = mid
        }
        return lerp(background, endpoint, high)
    }

    fun apply(base: AbPalette, roles: BrandColorRoles): AbPalette = base.copy(
        accent = roles.primary,
        onAccent = roles.onPrimary,
        glassStrokeTop = lerp(base.glassStrokeTop, roles.container, .30f),
    )
}

object BrandMotion {
    const val COLOR_MS = 320
    const val PRESS_DOWN_MS = 80
    const val PRESS_UP_MS = 180
    const val PRESSED_SCALE = .92f
    fun duration(reduceMotion: Boolean): Int = if (reduceMotion) 0 else COLOR_MS
}
