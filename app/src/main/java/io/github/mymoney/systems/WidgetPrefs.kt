package io.github.mymoney.systems

import android.content.Context
import android.content.SharedPreferences

/**
 * Local per-widget-instance preferences, stored outside backup/sync config.
 * Call [delete] from AppWidgetProvider.onDeleted to clean up removed instances.
 */
object WidgetPrefs {
    private const val PREFS_NAME = "widget_prefs"
    private const val KEY_DEFAULT_SURFACE = "default_surface"
    private const val KEY_PREFIX_MODE = "mode_"
    private const val KEY_PREFIX_SURFACE = "surface_"
    private const val KEY_PREFIX_MASKED = "masked_"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun defaultSurface(prefs: SharedPreferences, fallback: WidgetSurfaceStyle = WidgetSurfaceStyle.GLASS_DARK): WidgetSurfaceStyle =
        WidgetSurfaceStyle.fromId(prefs.getString(KEY_DEFAULT_SURFACE, null)) ?: fallback

    fun setDefaultSurface(prefs: SharedPreferences, style: WidgetSurfaceStyle) {
        prefs.edit().putString(KEY_DEFAULT_SURFACE, style.id).apply()
    }

    fun mode(prefs: SharedPreferences, appWidgetId: Int): AssetWidgetMode =
        AssetWidgetMode.fromId(prefs.getString("$KEY_PREFIX_MODE$appWidgetId", null))

    fun setMode(prefs: SharedPreferences, appWidgetId: Int, mode: AssetWidgetMode) {
        prefs.edit().putString("$KEY_PREFIX_MODE$appWidgetId", mode.id).apply()
    }

    fun surface(prefs: SharedPreferences, appWidgetId: Int, fallback: WidgetSurfaceStyle = defaultSurface(prefs)): WidgetSurfaceStyle =
        WidgetSurfaceStyle.fromId(prefs.getString("$KEY_PREFIX_SURFACE$appWidgetId", null)) ?: fallback

    fun setSurface(prefs: SharedPreferences, appWidgetId: Int, surface: WidgetSurfaceStyle) {
        prefs.edit().putString("$KEY_PREFIX_SURFACE$appWidgetId", surface.id).apply()
    }

    fun isMasked(prefs: SharedPreferences, appWidgetId: Int): Boolean =
        prefs.getBoolean("$KEY_PREFIX_MASKED$appWidgetId", false)

    fun setMasked(prefs: SharedPreferences, appWidgetId: Int, masked: Boolean) {
        prefs.edit().putBoolean("$KEY_PREFIX_MASKED$appWidgetId", masked).apply()
    }

    fun toggleMasked(prefs: SharedPreferences, appWidgetId: Int): Boolean {
        val next = !isMasked(prefs, appWidgetId)
        setMasked(prefs, appWidgetId, next)
        return next
    }

    fun delete(prefs: SharedPreferences, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove("$KEY_PREFIX_MODE$id")
            editor.remove("$KEY_PREFIX_SURFACE$id")
            editor.remove("$KEY_PREFIX_MASKED$id")
        }
        editor.apply()
    }
}
