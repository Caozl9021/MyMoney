package io.github.mymoney.ui.design

import android.os.SystemClock
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

internal val LocalPredictiveSlideHost = staticCompositionLocalOf { false }
internal val LocalOuterPredictiveBackCommit = staticCompositionLocalOf { false }

/**
 * 协调 SlideSubPage 内嵌 BackSurface 提交预测性返回时的退场方向（+1 向右 / -1 向左），
 * 让已侧倾的卡片延续拖拽方向退场，不再固定向右飞出。
 */
internal object PredictiveBackSignal {
    private var pendingDir: Int? = null
    private var pendingAtMs: Long = 0L

    fun markCommit(direction: Int) {
        pendingDir = direction
        pendingAtMs = SystemClock.uptimeMillis()
    }

    fun consumeFresh(windowMs: Long = 800L): Int? {
        val dir = pendingDir ?: return null
        pendingDir = null
        return if (SystemClock.uptimeMillis() - pendingAtMs <= windowMs) dir else null
    }
}

/**
 * 强自定义缓动曲线（Emil 设计工程规范）：避免 Compose 默认过弱的线性起步，
 * 进/退场优先使用强 ease-out，屏内形变/位移使用强 ease-in-out。
 */
internal object AbEasing {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

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
        if (reduceMotion) tween(120, easing = AbEasing.EaseOut) else spring(dampingRatio = NAV_DAMPING, stiffness = NAV_STIFFNESS)

    fun navigationFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(120, easing = AbEasing.EaseOut) else spring(dampingRatio = NAV_DAMPING, stiffness = NAV_STIFFNESS)

    fun sheetFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(120, easing = AbEasing.EaseOut) else spring(dampingRatio = SHEET_DAMPING, stiffness = SHEET_STIFFNESS)

    fun modalFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(110, easing = AbEasing.EaseOut) else spring(dampingRatio = MODAL_DAMPING, stiffness = MODAL_STIFFNESS)

    fun morphSize(reduceMotion: Boolean): FiniteAnimationSpec<IntSize> =
        if (reduceMotion) tween(120, easing = AbEasing.EaseOut) else spring(dampingRatio = MORPH_DAMPING, stiffness = MORPH_STIFFNESS)

    fun morphOffset(reduceMotion: Boolean): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) tween(120, easing = AbEasing.EaseOut) else spring(dampingRatio = MORPH_DAMPING, stiffness = MORPH_STIFFNESS)

    fun morphFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(120, easing = AbEasing.EaseOut) else spring(dampingRatio = MORPH_DAMPING, stiffness = MORPH_STIFFNESS)

    fun bouncyFloat(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(110, easing = AbEasing.EaseOut) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 460f)
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
    if (predictiveCommit) {
        return fadeIn(snap()) togetherWith fadeOut(snap())
    }
    if (reduceMotion) {
        return fadeIn(tween(150, easing = AbEasing.EaseOut)) togetherWith
            fadeOut(tween(120, easing = AbEasing.EaseOut))
    }
    val offsetSpec = AbSpring.navigationOffset(reduceMotion = false)
    val enter = if (goingBack) {
        slideInHorizontally(offsetSpec) { -(it * 0.22f).toInt() } + fadeIn(tween(160, easing = AbEasing.EaseOut))
    } else {
        slideInHorizontally(offsetSpec) { it } + fadeIn(tween(110, easing = AbEasing.EaseOut))
    }
    val exit = if (goingBack) {
        slideOutHorizontally(offsetSpec) { it } + fadeOut(tween(120, easing = AbEasing.EaseOut))
    } else {
        slideOutHorizontally(offsetSpec) { -(it * 0.22f).toInt() } + fadeOut(tween(160, easing = AbEasing.EaseOut))
    }
    return (enter togetherWith exit).apply {
        targetContentZIndex = if (goingBack) 0f else 1f
    }
}
