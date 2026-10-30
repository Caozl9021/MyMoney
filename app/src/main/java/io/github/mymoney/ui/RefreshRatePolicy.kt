package io.github.mymoney.ui

/** Pure selection policy: never request a resolution change or invent a device mode ID. */
internal object RefreshRatePolicy {
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)
    data class Request(val modeId: Int = 0, val refreshRate: Float = 0f)

    fun request(apiLevel: Int, current: Mode, supported: List<Mode>): Request {
        val best = fastestSameResolution(current, supported) ?: return Request()
        // Before API 34 a rate-only request applies to the default resolution, which
        // may differ from the current one. Use an explicitly matching mode instead.
        return if (apiLevel >= 34) Request(refreshRate = best.refreshRate)
        else Request(modeId = best.id)
    }

    fun fastestSameResolution(current: Mode, supported: List<Mode>): Mode? {
        if (current.width <= 0 || current.height <= 0) return null
        return supported.asSequence()
            .filter {
                it.id > 0 && it.width == current.width && it.height == current.height &&
                    it.refreshRate.isFinite() && it.refreshRate > 60.5f
            }
            .maxWithOrNull(compareBy<Mode> { it.refreshRate }.thenBy { it.id == current.id })
    }

    /** A single compatibility fallback after a rate-only preference failed to take effect. */
    fun modeFallback(target: Mode, current: Mode, observedRate: Float): Request {
        if (target.id <= 0 || target.width <= 0 || target.height <= 0 ||
            target.width != current.width || target.height != current.height ||
            !target.refreshRate.isFinite() || target.refreshRate <= 60.5f ||
            !observedRate.isFinite() || observedRate <= 0f ||
            observedRate + 1f >= target.refreshRate) return Request()
        return Request(modeId = target.id)
    }
}
