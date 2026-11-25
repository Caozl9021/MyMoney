package io.github.mymoney.systems

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import io.github.mymoney.R
import io.github.mymoney.ui.design.Appearance
import io.github.mymoney.ui.design.BrandColors

data class WidgetPalette(
    val darkSurface: Boolean,
    val accent: Int,
    val onAccent: Int,
    val accentContainer: Int,
    val onAccentContainer: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val chartTrack: Int,
    val chartAverageLine: Int,
)

/** Resolves the app's BrandTheme & widget surface style into concrete ARGB colors for RemoteViews. */
object WidgetThemeTokens {

    fun isSystemDark(context: Context): Boolean {
        val prefs = Appearance.prefs(context)
        return when (prefs.getString("theme", "SYSTEM")) {
            "DARK" -> true
            "LIGHT" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun palette(context: Context, surfaceStyle: WidgetSurfaceStyle = defaultSurfaceStyle(context)): WidgetPalette {
        val prefs = Appearance.prefs(context)
        val darkSurface = when (surfaceStyle) {
            WidgetSurfaceStyle.GLASS_DARK -> true
            WidgetSurfaceStyle.GLASS_LIGHT -> false
            WidgetSurfaceStyle.SOLID -> isSystemDark(context)
        }
        val brandTheme = Appearance.readBrandTheme(prefs)
        val roles = BrandColors.readable(BrandColors.roles(brandTheme, darkSurface))
        return WidgetPalette(
            darkSurface = darkSurface,
            accent = roles.primary.toArgb(),
            onAccent = roles.onPrimary.toArgb(),
            accentContainer = roles.container.toArgb(),
            onAccentContainer = if (surfaceStyle == WidgetSurfaceStyle.GLASS_DARK) Color.WHITE else roles.onContainer.toArgb(),
            primaryText = if (darkSurface) Color.parseColor("#F5F5F7") else Color.parseColor("#1C1C1E"),
            secondaryText = if (darkSurface) Color.parseColor("#CCFFFFFF") else Color.parseColor("#55555A"),
            chartTrack = when (surfaceStyle) {
                WidgetSurfaceStyle.GLASS_DARK -> Color.parseColor("#1AFFFFFF")
                WidgetSurfaceStyle.GLASS_LIGHT -> Color.parseColor("#1C767680")
                WidgetSurfaceStyle.SOLID -> if (darkSurface) Color.parseColor("#24FFFFFF") else Color.parseColor("#1F767680")
            },
            chartAverageLine = if (darkSurface) Color.parseColor("#55FFFFFF") else Color.parseColor("#478E8E93"),
        )
    }

    fun defaultSurfaceStyle(context: Context): WidgetSurfaceStyle {
        val glassOn = Appearance.prefs(context).getBoolean(Appearance.LIQUID_GLASS, Appearance.DEFAULT_LIQUID_GLASS)
        val fallback = if (glassOn) WidgetSurfaceStyle.GLASS_DARK else WidgetSurfaceStyle.SOLID
        return WidgetPrefs.defaultSurface(WidgetPrefs.prefs(context), fallback)
    }

    fun applySurface(views: RemoteViews, surfaceStyle: WidgetSurfaceStyle) {
        val bgRes = when (surfaceStyle) {
            WidgetSurfaceStyle.GLASS_DARK -> R.drawable.widget_background_glass_dark
            WidgetSurfaceStyle.GLASS_LIGHT -> R.drawable.widget_background_glass_light
            WidgetSurfaceStyle.SOLID -> R.drawable.widget_background
        }
        views.setInt(R.id.widget_card, "setBackgroundResource", bgRes)
    }

    fun applyPillBackground(
        views: RemoteViews,
        viewId: Int,
        surfaceStyle: WidgetSurfaceStyle,
        emphasized: Boolean,
        palette: WidgetPalette,
    ) {
        if (emphasized) {
            views.setImageViewResource(viewId, R.drawable.widget_accent_pill)
            if (surfaceStyle == WidgetSurfaceStyle.GLASS_DARK) {
                views.setInt(viewId, "setColorFilter", palette.accent or (0xFF shl 24))
                views.setInt(viewId, "setImageAlpha", 82)
            } else {
                views.setInt(viewId, "setColorFilter", palette.accentContainer or (0xFF shl 24))
                views.setInt(viewId, "setImageAlpha", 255)
            }
        } else {
            val res = when (surfaceStyle) {
                WidgetSurfaceStyle.GLASS_DARK -> R.drawable.widget_pill_glass_dark
                WidgetSurfaceStyle.GLASS_LIGHT -> R.drawable.widget_pill_glass_light
                WidgetSurfaceStyle.SOLID -> R.drawable.widget_pill_solid
            }
            views.setImageViewResource(viewId, res)
            views.setInt(viewId, "setColorFilter", 0)
            views.setInt(viewId, "setImageAlpha", 255)
        }
    }

    fun tintImage(views: RemoteViews, viewId: Int, colorArgb: Int) {
        views.setInt(viewId, "setColorFilter", colorArgb or (0xFF shl 24))
        views.setInt(viewId, "setImageAlpha", Color.alpha(colorArgb))
    }
}
