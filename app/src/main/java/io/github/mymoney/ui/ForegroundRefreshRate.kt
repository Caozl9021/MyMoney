package io.github.mymoney.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window

/** Window-scoped preference only; power saving, thermal and ROM policies remain authoritative. */
internal class ForegroundRefreshRate(private val window: Window) {
    private var previous: RefreshRatePolicy.Request? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fallbackTarget: RefreshRatePolicy.Mode? = null
    private var checkedFallback = false
    private val fallbackCheck = Runnable {
        val target = fallbackTarget
        if (previous != null && target != null && window.decorView.hasWindowFocus() && !checkedFallback) {
            checkedFallback = true
            val display = window.decorView.display
            if (display != null) {
                val current = display.mode.toPolicyMode()
                // The display may have changed since resume. Re-resolve the mode
                // from this display rather than applying another display's ID.
                val stillSupported = display.supportedModes.any { it.toPolicyMode() == target }
                val request = if (stillSupported) RefreshRatePolicy.modeFallback(target, current, display.refreshRate)
                    else RefreshRatePolicy.Request()
                if (request.modeId != 0) {
                    val attributes = window.attributes
                    attributes.preferredDisplayModeId = request.modeId
                    attributes.preferredRefreshRate = request.refreshRate
                    window.attributes = attributes
                }
            }
        }
    }

    fun resume() {
        if (previous != null) return
        // The decor display belongs to this window, including on secondary displays.
        // First onResume can run before decor attachment. This window's manager
        // also resolves its associated display then (including secondary displays).
        @Suppress("DEPRECATION")
        val display = window.decorView.display ?: window.windowManager.defaultDisplay ?: return
        val current = display.mode.toPolicyMode()
        val supported = display.supportedModes.map { it.toPolicyMode() }
        val request = RefreshRatePolicy.request(Build.VERSION.SDK_INT, current, supported)
        if (request == RefreshRatePolicy.Request()) return
        val attributes = window.attributes
        previous = RefreshRatePolicy.Request(attributes.preferredDisplayModeId, attributes.preferredRefreshRate)
        attributes.preferredDisplayModeId = request.modeId
        attributes.preferredRefreshRate = request.refreshRate
        window.attributes = attributes
        checkedFallback = false
        fallbackTarget = if (request.modeId == 0) RefreshRatePolicy.fastestSameResolution(current, supported) else null
        focusChanged(window.decorView.hasWindowFocus())
    }

    fun focusChanged(hasFocus: Boolean) {
        handler.removeCallbacks(fallbackCheck)
        if (hasFocus && previous != null && fallbackTarget != null && !checkedFallback) {
            // One settled observation per foreground session, never a per-frame poll.
            handler.postDelayed(fallbackCheck, 750L)
        }
    }

    fun pause() {
        handler.removeCallbacks(fallbackCheck)
        fallbackTarget = null
        checkedFallback = false
        val original = previous ?: return
        previous = null
        val attributes = window.attributes
        attributes.preferredDisplayModeId = original.modeId
        attributes.preferredRefreshRate = original.refreshRate
        window.attributes = attributes
    }

    private fun Display.Mode.toPolicyMode() = RefreshRatePolicy.Mode(
        modeId, physicalWidth, physicalHeight, refreshRate,
    )
}
