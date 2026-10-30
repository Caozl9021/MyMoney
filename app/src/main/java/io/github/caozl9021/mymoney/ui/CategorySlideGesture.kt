package io.github.caozl9021.mymoney.ui

/** Root-coordinate hit regions; gaps and clipped/offscreen rows are never candidates. */
internal data class CategoryHitRegion(val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

internal data class CategoryPanelSpace(val above: Boolean, val height: Float)

/** Prefer the roomier side of the held chip, so opening cannot put a new candidate under the finger. */
internal fun categoryPanelSpace(anchorTop: Float, anchorBottom: Float, rootHeight: Float,
    safeTop: Float, safeBottom: Float, gap: Float): CategoryPanelSpace {
    val above = (anchorTop - safeTop - gap).coerceAtLeast(0f)
    val below = (rootHeight - safeBottom - anchorBottom - gap).coerceAtLeast(0f)
    val available = (rootHeight - safeTop - safeBottom).coerceAtLeast(1f)
    return CategoryPanelSpace(above >= below, maxOf(above, below).coerceIn(1f, available))
}

/** A gesture can finish once. Previewing never mutates the bill. */
internal class CategorySlideGesture(private val allowed: Set<String>) {
    private var active = true
    private var candidate: String? = null

    fun move(x: Float, y: Float, regions: List<CategoryHitRegion>): String? {
        if (!active) return null
        candidate = regions.firstOrNull { it.name in allowed && it.contains(x, y) }?.name
        return candidate
    }

    fun release(): String? {
        if (!active) return null
        active = false
        return candidate
    }

    fun cancel() { active = false; candidate = null }
}
