package io.github.mymoney.ui

import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import io.github.mymoney.ui.design.Appearance
import kotlin.math.roundToInt

/** The sheet owns system/IME insets; the decor must not subtract navigation height first. */
internal fun ComponentActivity.configureConfirmationWindow(blurBehind: Boolean = false) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    if (blurBehind && Build.VERSION.SDK_INT >= 31) {
        val glassEnabled = Appearance.prefs(this).getBoolean(Appearance.LIQUID_GLASS, Appearance.DEFAULT_LIQUID_GLASS)
        if (glassEnabled) {
            runCatching {
                val wm = getSystemService(WindowManager::class.java)
                if (wm?.isCrossWindowBlurEnabled == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = (18f * resources.displayMetrics.density).roundToInt()
                    }
                }
            }
        }
    }
}
