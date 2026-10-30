package io.github.caozl9021.mymoney.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/** Lucide Animated (pqoqubbw/icons, MIT), Lucide geometry (ISC).
 * Exact upstream SVG paths; Compose adapts Motion transforms to one-shot tab selection.
 * Source snapshots, checksums and licenses: assets/licenses/lucide-animated/.
 */
enum class TabGlyph { HOME, CARD, PIE, SETTINGS }

private object LucideTabPaths {
    private fun path(d: String) = PathParser().parsePathString(d).toPath()
    val home0 = path("M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val home1 = path("M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8")
    val chart_pie0 = path("M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 1 1z")
    val chart_pie1 = path("M21.21 15.89A10 10 0 1 1 8 2.83")
    val settings0 = path("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z")
    val cardOutline = path("M4 5H20A2 2 0 0 1 22 7V17A2 2 0 0 1 20 19H4A2 2 0 0 1 2 17V7A2 2 0 0 1 4 5Z")
    val cardStripe = path("M2 10H22")
    val gearCenter = Path().apply { addOval(androidx.compose.ui.geometry.Rect(9f, 9f, 15f, 15f)) }
}

@Composable
fun TabGlyphIcon(glyph: TabGlyph, active: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val progress = remember(glyph) { Animatable(1f) }
    val displacement = remember(glyph) { Animatable(0f) }
    val rotation = remember(glyph) { Animatable(0f) }
    // First composition is static. Only an actual false→true selection starts playback.
    val wasActive = remember(glyph) { booleanArrayOf(active) }
    LaunchedEffect(active, reduce) {
        val selectedNow = active && !wasActive[0]
        wasActive[0] = active
        progress.snapTo(1f)
        displacement.snapTo(0f)
        rotation.snapTo(0f)
        if (!selectedNow || reduce) return@LaunchedEffect
        when (glyph) {
            TabGlyph.HOME -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(600, easing = CubicBezierEasing(.42f, 0f, .58f, 1f)))
            }
            TabGlyph.CARD -> displacement.animateTo(0f, keyframes {
                durationMillis = 700
                0f at 0 using CubicBezierEasing(.42f, 0f, .58f, 1f)
                -4f at 280 using CubicBezierEasing(.42f, 0f, .58f, 1f)
                1.5f at 525 using CubicBezierEasing(.42f, 0f, .58f, 1f)
                0f at 700
            })
            TabGlyph.PIE -> {
                displacement.animateTo(1.1f, spring(dampingRatio = .48f, stiffness = 250f))
                displacement.animateTo(0f, spring(dampingRatio = 1f, stiffness = 250f))
            }
            TabGlyph.SETTINGS -> {
                rotation.animateTo(180f, spring(dampingRatio = .71f, stiffness = 50f))
                // Six-fold symmetric gear: 180° has the same resting silhouette as 0°.
                rotation.snapTo(0f)
            }
        }
    }
    val trim = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(modifier) {
        val unit = minOf(size.width, size.height) / 24f
        val stroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Reading animation values inside draw avoids recomposing the dock on every frame.
        val p = if (reduce || !active) 1f else progress.value
        val x = if (reduce || !active) 0f else displacement.value
        val angle = if (reduce || !active) 0f else rotation.value
        translate((size.width - 24f * unit) / 2, (size.height - 24f * unit) / 2) {
            scale(unit, unit, pivot = Offset.Zero) {
                fun line(path: Path, alpha: Float = 1f) = drawPath(path, tint.copy(alpha = tint.alpha * alpha), style = stroke)
                when (glyph) {
                    TabGlyph.HOME -> {
                        line(LucideTabPaths.home0)
                        if (p >= 1f) line(LucideTabPaths.home1) else if (p > 0f) {
                            segment.reset()
                            trim.setPath(LucideTabPaths.home1, false)
                            trim.getSegment(0f, trim.length * p, segment)
                            line(segment, (p * 3f).coerceAtMost(1f))
                        }
                    }
                    TabGlyph.CARD -> translate(left = x) {
                        line(LucideTabPaths.cardOutline)
                        line(LucideTabPaths.cardStripe)
                    }
                    TabGlyph.PIE -> {
                        line(LucideTabPaths.chart_pie1)
                        translate(left = x, top = -x) { line(LucideTabPaths.chart_pie0) }
                    }
                    TabGlyph.SETTINGS -> rotate(angle, pivot = Offset(12f, 12f)) {
                        line(LucideTabPaths.settings0)
                        line(LucideTabPaths.gearCenter)
                    }
                }
            }
        }
    }
}
