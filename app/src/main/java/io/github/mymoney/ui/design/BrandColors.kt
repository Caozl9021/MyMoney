package io.github.mymoney.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/** In-app brand accent options, decoupled from the fixed launcher icon. */
enum class BrandTheme(val id: String, val title: String) {
    BLUE("blue", "蓝色"),
    GREEN("green", "青绿"),
    RED("red", "珊瑚红"),
    GOLD("gold", "麦穗金");

    companion object {
        fun fromId(id: String?): BrandTheme? = entries.firstOrNull { it.id == id }
    }
}

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
    fun roles(theme: BrandTheme, dark: Boolean): BrandColorRoles {
        val light = when (theme) {
            BrandTheme.BLUE -> Color(0xFF0070F5)
            BrandTheme.GREEN -> Color(0xFF00847D)
            BrandTheme.RED -> Color(0xFFCC493E)
            BrandTheme.GOLD -> Color(0xFFB25E00)
        }
        val bright = when (theme) {
            BrandTheme.BLUE -> Color(0xFF54AAFF)
            BrandTheme.GREEN -> Color(0xFF50C9BF)
            BrandTheme.RED -> Color(0xFFF28B7E)
            BrandTheme.GOLD -> Color(0xFFF4B248)
        }
        val ink = when (theme) {
            BrandTheme.BLUE -> Color(0xFF052242)
            BrandTheme.GREEN -> Color(0xFF062D29)
            BrandTheme.RED -> Color(0xFF3C1712)
            BrandTheme.GOLD -> Color(0xFF331B00)
        }
        val container = when (theme) {
            BrandTheme.BLUE -> if (dark) Color(0xFF102E4F) else Color(0xFFE8F2FF)
            BrandTheme.GREEN -> if (dark) Color(0xFF123A36) else Color(0xFFE5F4F1)
            BrandTheme.RED -> if (dark) Color(0xFF4B2722) else Color(0xFFFCEBE8)
            BrandTheme.GOLD -> if (dark) Color(0xFF3F270A) else Color(0xFFFEF3DC)
        }
        val containerText = when (theme) {
            BrandTheme.BLUE -> if (dark) Color(0xFFD2E7FF) else Color(0xFF004099)
            BrandTheme.GREEN -> if (dark) Color(0xFFB7F0E8) else Color(0xFF004C47)
            BrandTheme.RED -> if (dark) Color(0xFFFFDAD4) else Color(0xFF81271F)
            BrandTheme.GOLD -> if (dark) Color(0xFFFFE3B3) else Color(0xFF763A00)
        }
        return BrandColorRoles(
            primary = if (dark) bright else light,
            onPrimary = if (dark) ink else Color.White,
            container = container,
            onContainer = containerText,
            inversePrimary = if (dark) light else bright,
        )
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
    const val COLOR_MS = 220
    const val PRESS_DOWN_MS = 80
    const val PRESS_UP_MS = 180
    const val PRESSED_SCALE = .92f
    fun duration(reduceMotion: Boolean): Int = if (reduceMotion) 0 else COLOR_MS
}
