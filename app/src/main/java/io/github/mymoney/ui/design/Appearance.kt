package io.github.mymoney.ui.design

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

object Appearance {
    enum class Theme { SYSTEM, LIGHT, DARK }
    enum class TextSize(val multiplier: Float) { STANDARD(1f), LARGE(1.15f), EXTRA_LARGE(1.3f) }
    const val PREDICTIVE_BACK = "predictive_back"
    const val DEFAULT_PREDICTIVE_BACK = false
    const val LIQUID_GLASS = "liquid_glass"
    const val DEFAULT_LIQUID_GLASS = true
    const val LIQUID_GLASS_CLARITY = "liquid_glass_clarity"
    const val DEFAULT_LIQUID_GLASS_CLARITY = 0.65f
    const val LIQUID_GLASS_PER_ZONE_CLARITY = "liquid_glass_per_zone_clarity"
    const val CLARITY_DOCK = "liquid_glass_clarity_dock"
    const val CLARITY_HEADER = "liquid_glass_clarity_header"
    const val CLARITY_SHEET = "liquid_glass_clarity_sheet"
    const val CLARITY_DIALOG = "liquid_glass_clarity_dialog"

    fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    internal fun resolveClaritySpec(
        perZone: Boolean,
        global: Float,
        dock: Float,
        header: Float,
        sheet: Float,
        dialog: Float,
    ): LiquidGlassClaritySpec {
        val g = global.coerceIn(0f, 1f)
        return if (!perZone) {
            LiquidGlassClaritySpec(dock = g, header = g, sheet = g, dialog = g)
        } else {
            LiquidGlassClaritySpec(
                dock = dock.coerceIn(0f, 1f),
                header = header.coerceIn(0f, 1f),
                sheet = sheet.coerceIn(0f, 1f),
                dialog = dialog.coerceIn(0f, 1f),
            )
        }
    }

    internal fun readClaritySpec(prefs: android.content.SharedPreferences): LiquidGlassClaritySpec {
        val global = prefs.getFloat(LIQUID_GLASS_CLARITY, DEFAULT_LIQUID_GLASS_CLARITY).coerceIn(0f, 1f)
        val perZone = prefs.getBoolean(LIQUID_GLASS_PER_ZONE_CLARITY, false)
        return resolveClaritySpec(
            perZone = perZone,
            global = global,
            dock = prefs.getFloat(CLARITY_DOCK, global),
            header = prefs.getFloat(CLARITY_HEADER, global),
            sheet = prefs.getFloat(CLARITY_SHEET, global),
            dialog = prefs.getFloat(CLARITY_DIALOG, global),
        )
    }
}

@Immutable
data class LiquidGlassClaritySpec(
    val dock: Float = Appearance.DEFAULT_LIQUID_GLASS_CLARITY,
    val header: Float = Appearance.DEFAULT_LIQUID_GLASS_CLARITY,
    val sheet: Float = Appearance.DEFAULT_LIQUID_GLASS_CLARITY,
    val dialog: Float = Appearance.DEFAULT_LIQUID_GLASS_CLARITY,
)

val LocalReduceMotion = compositionLocalOf { false }
val LocalPredictiveBackEnabled = compositionLocalOf { Appearance.DEFAULT_PREDICTIVE_BACK }
val LocalLiquidGlassEnabled = compositionLocalOf { Appearance.DEFAULT_LIQUID_GLASS }
val LocalLiquidGlassClarity = compositionLocalOf { Appearance.DEFAULT_LIQUID_GLASS_CLARITY }
val LocalLiquidGlassClaritySpec = compositionLocalOf { LiquidGlassClaritySpec() }

@Composable
fun appearanceRevision(): Int {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = Appearance.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); context.contentResolver.unregisterContentObserver(observer) }
    }
    return revision
}
