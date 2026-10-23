package com.example.autobook.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.example.autobook.ui.design.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import com.example.autobook.ui.design.AbPalette
import com.example.autobook.ui.design.DarkPalette
import com.example.autobook.ui.design.LightPalette
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.LocalReduceTransparency
import com.example.autobook.ui.design.SystemAccent

/**
 * 把我们的语义色映射到 Material3 的 ColorScheme，
 * 这样仍在使用 M3 组件的零散位置（TextField、Switch 的默认参数等）也不会露出紫蓝默认色。
 */
private fun AbPalette.toLightScheme() = lightColorScheme(
    primary = accent,
    onPrimary = onAccent,
    secondary = accent,
    onSecondary = onAccent,
    background = pageBg,
    onBackground = label,
    surface = cardBg,
    onSurface = label,
    surfaceVariant = fill,
    onSurfaceVariant = secondaryLabel,
    outline = separator,
    error = red,
    onError = onAccent,
)

private fun AbPalette.toDarkScheme() = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    secondary = accent,
    onSecondary = onAccent,
    background = pageBg,
    onBackground = label,
    surface = cardBg,
    onSurface = label,
    surfaceVariant = fill,
    onSurfaceVariant = secondaryLabel,
    outline = separator,
    error = red,
    onError = onAccent,
)

/**
 * @param reduceTransparency 强制关闭玻璃（null = 跟随系统无障碍偏好）
 */
@Composable
fun AutoBookTheme(
    darkTheme: Boolean? = null,
    reduceTransparency: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val revision = appearanceRevision()
    val prefs = remember(context, revision) { Appearance.prefs(context) }
    val dark = darkTheme ?: when (prefs.getString("theme", "SYSTEM")) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val textScale = runCatching { Appearance.TextSize.valueOf(prefs.getString("text_size", "STANDARD")!!).multiplier }.getOrDefault(1f)
    val reduceMotion = prefs.getBoolean("reduce_motion", false) || android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val density = LocalDensity.current
    // 跟随系统强调色（Android 12+ 从壁纸取色）；取不到就用品牌薄荷绿。
    // 只换 accent / onAccent / 玻璃描边，中性色和 Tab 选中块不动。
    val basePalette = if (dark) DarkPalette else LightPalette
    val palette = remember(dark, context) {
        SystemAccent.apply(context, basePalette, dark)
    }

    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        var owner: android.content.Context? = context
        while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
        (owner as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowInsetsControllerCompat(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val reduce = reduceTransparency ?: false

    CompositionLocalProvider(
        LocalAbPalette provides palette,
        LocalReduceMotion provides reduceMotion,
        LocalDensity provides Density(density.density, density.fontScale * textScale),
        LocalReduceTransparency provides reduce,
    ) {
        MaterialTheme(
            colorScheme = if (dark) palette.toDarkScheme() else palette.toLightScheme(),
            content = content,
        )
    }
}
