package com.example.autobook.ui.design

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
    fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
}
val LocalReduceMotion = compositionLocalOf { false }
val LocalPredictiveBackEnabled = compositionLocalOf { Appearance.DEFAULT_PREDICTIVE_BACK }

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
