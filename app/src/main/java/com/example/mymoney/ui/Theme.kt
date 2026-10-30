package com.example.mymoney.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mymoney.systems.LauncherIcons
import com.example.mymoney.ui.design.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import com.example.mymoney.ui.design.AbPalette
import com.example.mymoney.ui.design.DarkPalette
import com.example.mymoney.ui.design.LightPalette
import com.example.mymoney.ui.design.LocalAbPalette
import com.example.mymoney.ui.design.LocalReduceTransparency

/**
 * @param reduceTransparency 强制关闭玻璃（null = 跟随系统无障碍偏好）
 */
@Composable
fun MyMoneyTheme(
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
    val textScale = remember(prefs, revision) {
        runCatching { Appearance.TextSize.valueOf(prefs.getString("text_size", "STANDARD")!!).multiplier }.getOrDefault(1f)
    }
    // appearanceRevision observes this system setting as well as preferences.
    // Avoid a ContentResolver/Binder read on unrelated UI recompositions.
    val reduceMotion = remember(context, prefs, revision) {
        prefs.getBoolean("reduce_motion", false) || android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    val density = LocalDensity.current
    val confirmedChoice by LauncherIcons.selection.collectAsStateWithLifecycle()
    var lastConfirmed by remember(context) { mutableStateOf(LauncherIcons.initialThemeSelection(context)) }
    val choice = confirmedChoice ?: lastConfirmed
    SideEffect { confirmedChoice?.let { lastConfirmed = it } }
    val roles = animatedBrandRoles(choice, dark, reduceMotion)
    val basePalette = if (dark) DarkPalette else LightPalette
    val palette = remember(basePalette, roles) { BrandColors.apply(basePalette, roles) }

    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(context, view, dark) {
        var owner: android.content.Context? = context
        while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
        (owner as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowInsetsControllerCompat(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    val reduce = reduceTransparency ?: false
    val scaledDensity = remember(density.density, density.fontScale, textScale) {
        Density(density.density, density.fontScale * textScale)
    }
    val colorScheme = remember(palette, roles, dark) {
        palette.materialScheme(roles, dark)
    }

    CompositionLocalProvider(
        LocalAbPalette provides palette,
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = roles.primary, backgroundColor = roles.primary.copy(alpha = .25f)),
        LocalReduceMotion provides reduceMotion,
        LocalPredictiveBackEnabled provides prefs.getBoolean(Appearance.PREDICTIVE_BACK, Appearance.DEFAULT_PREDICTIVE_BACK),
        LocalDensity provides scaledDensity,
        LocalReduceTransparency provides reduce,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
