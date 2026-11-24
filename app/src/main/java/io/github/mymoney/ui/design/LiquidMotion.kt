package io.github.mymoney.ui.design

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 对标 iOS 26/27 Fluid Motion 的全应用物理弹簧原语。
 * 所有转场、浮卡与弹窗共用同一组阻尼与刚度，支持可中断手势速度接力。
 */
internal object AbSpring {
    const val NAV_DAMPING = 0.96f
    const val NAV_STIFFNESS = 420f

    const val SHEET_DAMPING = 0.86f
    const val SHEET_STIFFNESS = 440f

    const val MODAL_DAMPING = 0.82f
    const val MODAL_STIFFNESS = 520f

    const val MORPH_DAMPING = 0.86f
    const val MORPH_STIFFNESS = 460f

    fun navigationOffset(reduceMotion: Boolean): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) snap() else spring(dampingRatio = NAV_DAMPING, stiffness = NAV_STIFFNESS)

    fun navigationFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = NAV_DAMPING, stiffness = NAV_STIFFNESS)

    fun sheetFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = SHEET_DAMPING, stiffness = SHEET_STIFFNESS)

    fun modalFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = MODAL_DAMPING, stiffness = MODAL_STIFFNESS)

    fun morphSize(reduceMotion: Boolean): FiniteAnimationSpec<IntSize> =
        if (reduceMotion) snap() else spring(dampingRatio = MORPH_DAMPING, stiffness = MORPH_STIFFNESS)

    fun morphOffset(reduceMotion: Boolean): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) snap() else spring(dampingRatio = MORPH_DAMPING, stiffness = MORPH_STIFFNESS)

    fun morphFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = MORPH_DAMPING, stiffness = MORPH_STIFFNESS)

    fun bouncyFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 460f)
}

/**
 * 统一的多级页面推拉物理过渡：
 * 推入时新页全幅临界弹簧进场、旧页轻微左移退让（-22% 视差）；退出时严格反向对称。
 */
internal fun liquidNavigationTransition(
    goingBack: Boolean,
    reduceMotion: Boolean,
    predictiveCommit: Boolean = false,
): ContentTransform {
    if (reduceMotion || predictiveCommit) {
        return fadeIn(snap()) togetherWith fadeOut(snap())
    }
    val offsetSpec = AbSpring.navigationOffset(reduceMotion = false)
    val enter = if (goingBack) {
        slideInHorizontally(offsetSpec) { -(it * 0.22f).toInt() } + fadeIn(tween(180))
    } else {
        slideInHorizontally(offsetSpec) { it } + fadeIn(tween(90))
    }
    val exit = if (goingBack) {
        slideOutHorizontally(offsetSpec) { it } + fadeOut(tween(160))
    } else {
        slideOutHorizontally(offsetSpec) { -(it * 0.22f).toInt() } + fadeOut(tween(200))
    }
    return (enter togetherWith exit).apply {
        targetContentZIndex = if (goingBack) 0f else 1f
    }
}
