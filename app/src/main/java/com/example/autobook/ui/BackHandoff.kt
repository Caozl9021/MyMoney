package com.example.autobook.ui

import androidx.compose.runtime.*

/** Keep the destination cover until that route has actually drawn. */
internal class BackHandoff {
    enum class Phase { IDLE, DRAGGING, COMMITTED }
    var phase by mutableStateOf(Phase.IDLE); private set
    var progress by mutableFloatStateOf(0f); private set
    var startedRoute: Any? = null; private set
    var drawnRoute by mutableStateOf<Any?>(null); private set
    fun begin(route: Any?): Boolean {
        if (phase != Phase.IDLE) return false
        startedRoute = route; drawnRoute = null; progress = 0f; phase = Phase.DRAGGING
        return true
    }
    fun drag(value: Float) { if (phase == Phase.DRAGGING) progress = value.coerceIn(0f, 1f) }
    fun commit() { if (phase == Phase.DRAGGING) { phase = Phase.COMMITTED; progress = 0f } }
    fun drawn(route: Any?, contentReady: Boolean = true) {
        if (contentReady && phase == Phase.COMMITTED && route != startedRoute && drawnRoute != route) drawnRoute = route
    }
    fun finishDrawn(): Boolean {
        if (phase != Phase.COMMITTED || drawnRoute == null || drawnRoute == startedRoute) return false
        reset(); return true
    }
    fun reset() { phase = Phase.IDLE; progress = 0f; drawnRoute = null; startedRoute = null }
}
