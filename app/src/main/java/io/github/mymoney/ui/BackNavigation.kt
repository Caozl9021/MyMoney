package io.github.mymoney.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalOuterPredictiveBackCommit
import io.github.mymoney.ui.design.LocalPredictiveBackEnabled
import io.github.mymoney.ui.design.LocalPredictiveSlideHost
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.LocalReduceTransparency
import io.github.mymoney.ui.design.PredictiveBackSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

private val MomentumRecoverSpring = spring<Float>(
    dampingRatio = 0.88f,
    stiffness = 360f,
    visibilityThreshold = 0.0005f,
)

@Immutable
private data class ExitCommitState(
    val fromProgress: Float,
    val fromVerticalPx: Float,
    val direction: Float,
    val blurFromDp: Float,
    val bitmap: ImageBitmap,
    val backdropPreview: ImageBitmap?,
)

@Immutable
private data class FrozenSlideExit(
    val progress: Float,
    val verticalPx: Float,
    val swipeEdge: Int,
)

/**
 * 统一拖拽、取消回弹与提交退场的 3D 倾角 / 缩放 / 圆角裁剪参数，保证起止帧连续不跳变。
 */
private fun GraphicsLayerScope.applyPredictiveCardTransform(
    progress: Float,
    verticalPx: Float,
    direction: Float,
    maxFollowY: Float,
    deviceCorner: Dp,
    exitProgress: Float = 0f,
) {
    val normProgress = (progress / 0.9f).coerceIn(0f, 1f)
    val clampedFollowY = verticalPx.coerceIn(-maxFollowY, maxFollowY)
    val followNormY = (clampedFollowY / maxFollowY).coerceIn(-1f, 1f)
    val baseScale = 1f - progress * 0.12f
    val cardScale = baseScale * (1f - 0.08f * exitProgress)
    val baseX = direction * progress * 44.dp.toPx()
    val baseY = progress * 8.dp.toPx() + clampedFollowY * 0.45f
    transformOrigin = TransformOrigin.Center
    scaleX = cardScale
    scaleY = cardScale
    translationX = baseX + direction * 148.dp.toPx() * exitProgress
    translationY = baseY + 12.dp.toPx() * exitProgress
    rotationY = direction * progress * 7.5f * (1f + 0.45f * exitProgress)
    rotationX = -followNormY * 4.5f * (0.35f + 0.65f * progress)
    rotationZ = direction * progress * (1.4f + 1.2f * exitProgress) + followNormY * direction * 0.8f
    cameraDistance = 22f
    shape = RoundedCornerShape(deviceCorner * (progress + (1f - progress) * 0.25f * exitProgress))
    clip = true
    val fade = 1f - exitProgress
    shadowElevation = 24.dp.toPx() * normProgress * fade
    alpha = fade.coerceIn(0f, 1f)
}

@Composable
private fun rememberDeviceCornerRadius(enabled: Boolean): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var radius by remember { mutableStateOf(28.dp) }
    LaunchedEffect(view, enabled) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var insets = view.rootWindowInsets
            if (insets == null) {
                delay(100)
                insets = view.rootWindowInsets
            }
            val pixels = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            if (pixels > 0) radius = with(density) { pixels.toDp() }
        }
    }
    return radius
}

/** Outgoing AnimatedContent pages must not consume a back event. */
internal val LocalBackActive = staticCompositionLocalOf { true }
internal val LocalPredictiveBackCommit = staticCompositionLocalOf { false }
internal val LocalPageBackManaged = staticCompositionLocalOf { false }

/** Captures only pixels. Previewing a parent never composes it or replays its effects. */
@Stable
internal class BackPixels {
    internal var capture: (suspend () -> ImageBitmap?)? = null
    suspend fun snapshot(): ImageBitmap? = try { capture?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}

@Composable
internal fun RememberBackPixels(): BackPixels = remember { BackPixels() }

@Composable
internal fun BackSurface(
    enabled: Boolean,
    onBack: () -> Unit,
    preview: ImageBitmap? = null,
    routeKey: Any? = null,
    contentReady: Boolean = true,
    pixels: BackPixels = RememberBackPixels(),
    onPredictiveCommit: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val exitLayer = rememberGraphicsLayer()
    var hasExitLayer by remember { mutableStateOf(false) }
    var exitCommit by remember { mutableStateOf<ExitCommitState?>(null) }
    val exitFraction = remember { Animatable(0f) }
    var frozenSlideExit by remember { mutableStateOf<FrozenSlideExit?>(null) }
    val reduce = LocalReduceMotion.current
    val reduceTransparency = LocalReduceTransparency.current
    val active = LocalBackActive.current
    val insideSlideHost by rememberUpdatedState(LocalPredictiveSlideHost.current)
    val predictiveEnabled = LocalPredictiveBackEnabled.current
    val predictionAllowed by rememberUpdatedState(predictiveEnabled)
    val ready by rememberUpdatedState(contentReady)
    val handoff = remember { BackHandoff() }
    val back by rememberUpdatedState(onBack)
    val predictiveCommitHook by rememberUpdatedState(onPredictiveCommit)
    val latestKey by rememberUpdatedState(routeKey)
    val canCommit by rememberUpdatedState(enabled && active)
    val allowBlur = !reduce && !reduceTransparency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val allowBlurState by rememberUpdatedState(allowBlur)
    var gesturePreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var cancelPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var verticalOffsetPx by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val cancelProgress = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    val cancelVerticalOffset = remember { Animatable(0f) }
    var cancelJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val captureMutex = remember { Mutex() }
    var captureTicket by remember { mutableIntStateOf(0) }
    var recordedTicket by remember { mutableIntStateOf(0) }
    var recordedRoute by remember { mutableStateOf<Any?>(null) }
    val deviceCorner = rememberDeviceCornerRadius(predictiveEnabled && enabled)
    val density = LocalDensity.current
    fun springBackOnCancel() {
        val from = handoff.progress
        val fromY = verticalOffsetPx
        val snap = gesturePreview
        handoff.reset()
        gesturePreview = null
        verticalOffsetPx = 0f
        if (!reduce && from > 0.001f && snap != null) {
            cancelPreview = snap
            cancelJob?.cancel()
            cancelJob = scope.launch {
                try {
                    cancelProgress.snapTo(from)
                    cancelVerticalOffset.snapTo(fromY)
                    coroutineScope {
                        launch { cancelProgress.animateTo(0f, MomentumRecoverSpring) }
                        launch { cancelVerticalOffset.animateTo(0f, MomentumRecoverSpring) }
                    }
                } finally {
                    cancelPreview = null
                }
            }
        }
    }
    LaunchedEffect(predictiveEnabled) {
        cancelJob?.cancel(); cancelPreview = null; verticalOffsetPx = 0f
        exitCommit = null; frozenSlideExit = null; hasExitLayer = false
        exitFraction.snapTo(0f)
        handoff.reset(); gesturePreview = null
    }
    LaunchedEffect(exitCommit) {
        if (exitCommit == null) {
            exitFraction.snapTo(0f)
            return@LaunchedEffect
        }
        exitFraction.snapTo(0f)
        exitFraction.animateTo(1f, tween(190, easing = AbEasing.EaseOut))
        exitCommit = null
        exitFraction.snapTo(0f)
    }
    LaunchedEffect(frozenSlideExit) {
        if (frozenSlideExit == null) return@LaunchedEffect
        // If a commit handler refuses to dismiss SlideSubPage, restore after exit window.
        delay(280)
        frozenSlideExit = null
    }
    // Cleared with the composable; pixels never reach storage/diagnostics.
    DisposableEffect(layer, pixels, predictiveEnabled) {
        pixels.capture = if (!predictiveEnabled) null else suspend {
            captureMutex.withLock {
                val origin = latestKey
                snapshotFlow { (handoff.phase == BackHandoff.Phase.IDLE && ready) || latestKey != origin || !predictionAllowed }.first { it }
                if (!predictionAllowed || latestKey != origin) null
                else {
                    val ticket = captureTicket + 1
                    captureTicket = ticket
                    try {
                        snapshotFlow { recordedTicket >= ticket || latestKey != origin || !predictionAllowed }.first { it }
                        if (predictionAllowed && latestKey == origin && recordedRoute == origin && ready) {
                            // Detach from the renderer; the preview can never share a live layer buffer.
                            val bitmap = layer.toImageBitmap().asAndroidBitmap()
                            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
                        } else null
                    } finally {
                        if (recordedTicket < ticket) recordedTicket = ticket
                    }
                }
            }
        }
        onDispose { pixels.capture = null }
    }
    // A route that drew under the cover can replace it on the next render.
    LaunchedEffect(handoff.drawnRoute) {
        if (handoff.finishDrawn()) gesturePreview = null
    }
    LaunchedEffect(handoff.phase) {
        if (handoff.phase == BackHandoff.Phase.COMMITTED) {
            withFrameNanos { }
            // Busy/no-op callbacks must not leave a permanent cover.
            if (latestKey == handoff.startedRoute) {
                handoff.reset(); gesturePreview = null
            } else {
                delay(350)
                if (handoff.phase == BackHandoff.Phase.COMMITTED) {
                    handoff.reset(); gesturePreview = null
                }
            }
        }
    }
    // Predictive back relies on the parent preview captured before navigation; only pixel capture waits for settle.
    val predictiveReady = predictiveEnabled
    // Keep both registrations stable: conditionally recreating a parent callback can
    // register it after an editor's callback and bypass its unsaved-changes guard.
    ActiveBackHandler(enabled = !predictiveReady && enabled && active && handoff.phase == BackHandoff.Phase.IDLE) { back() }
    PredictiveBackHandler(enabled = predictiveReady && enabled && active && handoff.phase != BackHandoff.Phase.COMMITTED) { events ->
        val startedAt = latestKey
        val commit = back
        val ownsGesture = handoff.begin(startedAt)
        var firstTouchY: Float? = null
        if (ownsGesture) {
            cancelJob?.cancel()
            cancelPreview = null
            exitCommit = null
            frozenSlideExit = null
            hasExitLayer = false
            verticalOffsetPx = 0f
            swipeEdge = BackEventCompat.EDGE_LEFT
            gesturePreview = preview
        }
        try {
            events.collect { event ->
                if (ownsGesture) {
                    if (firstTouchY == null) firstTouchY = event.touchY
                    swipeEdge = event.swipeEdge
                    if (reduce || gesturePreview == null) {
                        verticalOffsetPx = 0f
                        handoff.drag(0f)
                    } else {
                        verticalOffsetPx = event.touchY - (firstTouchY ?: event.touchY)
                        handoff.drag(min(event.progress, 0.9f))
                    }
                }
            }
            if (ownsGesture && canCommit && latestKey == startedAt) {
                // Once the gesture is released to commit, finish snapshot + route switch atomically.
                withContext(NonCancellable) {
                    val commitDir = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                    if (insideSlideHost) {
                        PredictiveBackSignal.markCommit(commitDir.toInt())
                        if (!reduce && handoff.progress > 0.001f) {
                            frozenSlideExit = FrozenSlideExit(handoff.progress, verticalOffsetPx, swipeEdge)
                        }
                    } else if (!reduce && hasExitLayer && handoff.progress > 0.001f) {
                        val snapBitmap = try { exitLayer.toImageBitmap() } catch (_: Throwable) { null }
                        if (snapBitmap != null && latestKey == startedAt) {
                            val normFrom = (handoff.progress / 0.9f).coerceIn(0f, 1f)
                            val blurFrom = if (allowBlurState) 8f * (1f - normFrom) else 0f
                            exitCommit = ExitCommitState(
                                fromProgress = handoff.progress,
                                fromVerticalPx = verticalOffsetPx,
                                direction = commitDir,
                                blurFromDp = blurFrom,
                                bitmap = snapBitmap,
                                backdropPreview = gesturePreview,
                            )
                        }
                    }
                    verticalOffsetPx = 0f
                    handoff.commit()
                    predictiveCommitHook()
                    commit()
                }
            } else if (ownsGesture) {
                withContext(NonCancellable) { springBackOnCancel() }
            }
        } catch (e: CancellationException) {
            if (ownsGesture && handoff.phase != BackHandoff.Phase.COMMITTED) {
                withContext(NonCancellable) { springBackOnCancel() }
            }
            throw e
        } finally {
            // A committed handoff belongs to the destination draw, not this callback's lifetime.
            if (ownsGesture && handoff.phase == BackHandoff.Phase.DRAGGING) {
                withContext(NonCancellable) { springBackOnCancel() }
            }
        }
    }
    val cover = handoff.phase == BackHandoff.Phase.COMMITTED
    val frozenExit = frozenSlideExit
    val effectiveProgress = when {
        handoff.phase == BackHandoff.Phase.DRAGGING -> handoff.progress
        frozenExit != null -> frozenExit.progress
        cancelPreview != null -> cancelProgress.value
        else -> 0f
    }.coerceIn(0f, 0.9f)
    val effectiveVerticalPx = when {
        handoff.phase == BackHandoff.Phase.DRAGGING -> verticalOffsetPx
        frozenExit != null -> frozenExit.verticalPx
        cancelPreview != null -> cancelVerticalOffset.value
        else -> 0f
    }
    val effectiveSwipeEdge = frozenExit?.swipeEdge ?: swipeEdge
    val activePreview = gesturePreview ?: cancelPreview
    val background = LocalAbPalette.current.pageBg
    val normProgress = (effectiveProgress / 0.9f).coerceIn(0f, 1f)
    val backdropBlurDp = if (allowBlur) 8f * (1f - normProgress) else 0f
    val direction = if (effectiveSwipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
    val maxFollowY = with(density) { 80.dp.toPx() }.coerceAtLeast(1f)
    val activeExit = exitCommit
    val exitProgress = if (activeExit != null) exitFraction.value.coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxSize().background(if (frozenExit != null) Color.Transparent else background)) {
        if (!cover && frozenExit == null && effectiveProgress > 0f && activePreview != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    activePreview,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdropBlurDp > 0.1f) Modifier.blur(backdropBlurDp.dp) else Modifier),
                    contentScale = ContentScale.FillBounds,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.14f * (1f - normProgress))),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (effectiveProgress > 0f) {
                        Modifier.graphicsLayer {
                            applyPredictiveCardTransform(
                                progress = effectiveProgress,
                                verticalPx = effectiveVerticalPx,
                                direction = direction,
                                maxFollowY = maxFollowY,
                                deviceCorner = deviceCorner,
                            )
                        }
                    } else Modifier,
                )
                .drawWithContent {
                    if (predictiveEnabled && contentReady && captureTicket > recordedTicket) {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                        recordedRoute = routeKey
                        recordedTicket = captureTicket
                    } else if (handoff.phase == BackHandoff.Phase.DRAGGING && !reduce && !insideSlideHost) {
                        exitLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(exitLayer)
                        hasExitLayer = true
                    } else drawContent()
                    handoff.drawn(routeKey, contentReady)
                },
        ) {
            CompositionLocalProvider(
                LocalPredictiveBackCommit provides cover,
                LocalOuterPredictiveBackCommit provides cover,
            ) { content() }
        }
        if (activeExit != null) {
            val ecNorm = (activeExit.fromProgress / 0.9f).coerceIn(0f, 1f)
            val ecFade = 1f - exitProgress
            val ecBlurDp = activeExit.blurFromDp * ecFade
            val ecScrimAlpha = 0.14f * (1f - ecNorm) * ecFade
            activeExit.backdropPreview?.let { bgPreview ->
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = ecFade }) {
                    Image(
                        bgPreview,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (ecBlurDp > 0.5f) Modifier.blur(ecBlurDp.dp) else Modifier),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
            if (ecScrimAlpha > 0.002f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ecScrimAlpha)))
            }
            Image(
                activeExit.bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        applyPredictiveCardTransform(
                            progress = activeExit.fromProgress,
                            verticalPx = activeExit.fromVerticalPx,
                            direction = activeExit.direction,
                            maxFollowY = maxFollowY,
                            deviceCorner = deviceCorner,
                            exitProgress = exitProgress,
                        )
                    },
                contentScale = ContentScale.FillBounds,
            )
        } else if (cover && frozenExit == null && gesturePreview != null) Image(
            gesturePreview!!,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
internal fun ActiveBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled && LocalBackActive.current, onBack = onBack)
}
