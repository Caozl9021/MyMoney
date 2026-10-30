package io.github.mymoney.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.mymoney.ui.design.LocalPredictiveBackEnabled

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
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val reduce = LocalReduceMotion.current
    val active = LocalBackActive.current
    val predictiveEnabled = LocalPredictiveBackEnabled.current
    val predictionAllowed by rememberUpdatedState(predictiveEnabled)
    val ready by rememberUpdatedState(contentReady)
    val handoff = remember { BackHandoff() }
    val back by rememberUpdatedState(onBack)
    val latestKey by rememberUpdatedState(routeKey)
    val canCommit by rememberUpdatedState(enabled && active)
    var gesturePreview by remember { mutableStateOf<ImageBitmap?>(null) }
    val captureMutex = remember { Mutex() }
    var captureTicket by remember { mutableIntStateOf(0) }
    var recordedTicket by remember { mutableIntStateOf(0) }
    var recordedRoute by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(predictiveEnabled) {
        handoff.reset(); gesturePreview = null
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
            if (latestKey == handoff.startedRoute) { handoff.reset(); gesturePreview = null }
        }
    }
    val predictiveReady = predictiveEnabled && contentReady
    // Keep both registrations stable: conditionally recreating a parent callback can
    // register it after an editor's callback and bypass its unsaved-changes guard.
    ActiveBackHandler(enabled = !predictiveReady && enabled && active && handoff.phase == BackHandoff.Phase.IDLE) { back() }
    PredictiveBackHandler(enabled = predictiveReady && enabled && active && handoff.phase != BackHandoff.Phase.COMMITTED) { events ->
        val startedAt = latestKey
        val commit = back
        val ownsGesture = handoff.begin(startedAt)
        if (ownsGesture) gesturePreview = preview
        try {
            events.collect { if (ownsGesture) handoff.drag(if (reduce || gesturePreview == null) 0f else it.progress) }
            if (ownsGesture && canCommit && latestKey == startedAt) {
                // Do not clear the old route then pop: cover the handoff in the same frame.
                handoff.commit()
                commit()
            } else if (ownsGesture) { handoff.reset(); gesturePreview = null }
        } catch (e: CancellationException) {
            if (ownsGesture && handoff.phase != BackHandoff.Phase.COMMITTED) { handoff.reset(); gesturePreview = null }
            throw e
        } finally {
            // A committed handoff belongs to the destination draw, not this callback's lifetime.
            if (ownsGesture && handoff.phase == BackHandoff.Phase.DRAGGING) { handoff.reset(); gesturePreview = null }
        }
    }
    val cover = handoff.phase == BackHandoff.Phase.COMMITTED
    val progress = handoff.progress
    val background = LocalAbPalette.current.pageBg
    Box(Modifier.fillMaxSize().background(background)) {
        if (!cover && progress > 0f && gesturePreview != null) Image(gesturePreview!!, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Box(Modifier.fillMaxSize()
            .then(if (progress > 0f) Modifier.graphicsLayer { translationX = size.width * progress; clip = true } else Modifier)
            .drawWithContent {
                if (predictiveEnabled && contentReady && captureTicket > recordedTicket) {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                    recordedRoute = routeKey
                    recordedTicket = captureTicket
                } else drawContent()
                handoff.drawn(routeKey, contentReady)
            }) {
            CompositionLocalProvider(LocalPredictiveBackCommit provides cover) { content() }
        }
        if (cover && gesturePreview != null) Image(gesturePreview!!, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
internal fun ActiveBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled && LocalBackActive.current, onBack = onBack)
}
