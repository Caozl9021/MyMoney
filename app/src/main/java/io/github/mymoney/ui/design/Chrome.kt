package io.github.mymoney.ui.design

import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.snap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 大标题栏，iOS large title 的行为：
 *
 * - 停在顶部时只有 34sp 页首标题；
 * - 滚动后标题淡出，玻璃背板淡入，出现 17sp 居中标题；
 * - 右侧动作按钮在两种状态下都在，位置一致。
 *
 * 进度用滚动偏移线性映射（不做弹簧），避免和 LazyColumn 的 fling 打架。
 *
 * @param scrollOffset 已滚动像素
 * @param collapseAt   完全收起所需的像素
 * @param compact      直接以收起态渲染（没有大标题块）。统计页用它腾出首屏高度。
 */
@Composable
fun LargeTitleBar(
    title: String,
    scrollOffset: Float,
    modifier: Modifier = Modifier,
    collapseAt: Float = 140f,
    subtitle: String? = null,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    val progress = if (compact) 1f else if (collapseAt <= 0f) 1f else (scrollOffset / collapseAt).coerceIn(0f, 1f)

    // 标题在中间区间完成交叉淡入淡出，避免两行字同时清晰
    val largeAlpha = ((0.62f - progress) / 0.47f).coerceIn(0f, 1f)
    val compactAlpha = ((progress - 0.30f) / 0.42f).coerceIn(0f, 1f)
    val barAlpha = ((progress - 0.55f) / 0.35f).coerceIn(0f, 1f)

    Box(modifier.fillMaxWidth()) {
        if (barAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (reduce) p.cardBg.copy(alpha = barAlpha)
                        else p.glassTint.copy(alpha = barAlpha),
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(p.separator.copy(alpha = barAlpha)),
                )
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AbSize.navBarHeight)
                    .padding(horizontal = AbSpacing.page),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Column(Modifier.alpha(compactAlpha)) {
                        Text(title, style = AbType.navTitle, color = p.label)
                        if (compact && subtitle != null) Text(subtitle, style = AbType.caption1, color = p.secondaryLabel)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(compactAlpha),
                    content = actions,
                )
            }

            // 大标题块的高度跟着进度一起收到 0，避免收起后留一大块空白
            val largeBlockHeight = (if (subtitle != null) 69.dp else 51.dp) * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(largeBlockHeight * largeAlpha)
                    .clipToBounds(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AbSpacing.page, end = AbSpacing.page, bottom = 8.dp)
                        .alpha(largeAlpha),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = AbType.largeTitle, color = p.label)
                        if (subtitle != null) {
                            Text(subtitle, style = AbType.footnote, color = p.secondaryLabel)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 栏
// ---------------------------------------------------------------------------

data class TabItem(
    val label: String,
    val icon: ImageVector,
    /** 有值就画会动的版本（选中时播一次画入动效）；否则退回 [icon] 静态图标。 */
    val glyph: TabGlyph? = null,
)

/**
 * 底部悬浮胶囊 Tab 栏（API < 33 的假玻璃兜底）：半透明玻璃、内容从它下面穿过。
 *
 * 宿主负责左右边距和 View 投影，此处只提供胶囊背景及四个入口。
 */
@Composable
fun LiquidTabBar(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dockHeight()),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(AbRadius.capsule))
                .background(p.cardBg.copy(alpha = if (reduce) 1f else 0.72f)),
        )
        LiquidTabCells(items = items, selected = selected, onSelect = onSelect)
    }
}

internal fun tabIndexForX(x: Float, rowWidth: Float, tabCount: Int): Int {
    if (tabCount <= 1 || rowWidth <= 0f) return 0
    return (x / (rowWidth / tabCount)).toInt().coerceIn(0, tabCount - 1)
}

internal fun rubberBandTabPosition(rawPos: Float, lastIndex: Int): Float {
    val maxIdx = lastIndex.coerceAtLeast(0).toFloat()
    return when {
        rawPos < 0f -> -((-rawPos) * 0.16f).coerceAtMost(0.10f)
        rawPos > maxIdx -> maxIdx + ((rawPos - maxIdx) * 0.16f).coerceAtMost(0.10f)
        else -> rawPos
    }
}

internal fun resolveScrubTargetTab(currentPos: Float, velocityTabsPerSec: Float, tabCount: Int): Int {
    if (tabCount <= 1) return 0
    val projection = if (abs(velocityTabsPerSec) >= 0.8f) {
        (velocityTabsPerSec * 0.045f).coerceIn(-0.45f, 0.45f)
    } else 0f
    return (currentPos + projection).roundToInt().coerceIn(0, tabCount - 1)
}

internal fun smoothstep01(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

internal data class TabIndicatorFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val fillAlpha: Float,
    val highlightAlpha: Float,
    val rimAlpha: Float,
    val shadowAlpha: Float,
    val lensMix: Float,
)

/**
 * Pure geometry and visual state for the floating dock tab indicator pill.
 *
 * Uses continuous velocity-coupled squash & stretch plus lifted glass sheen.
 * When [bleedPx] > 0, the indicator expands symmetrically into the surrounding
 * transparent shadow-bleed ring so edge tabs and vertical lift are unclipped.
 */
private data class LensTick(
    val pos: Float,
    val lift: Float,
    val lens: Float,
    val selected: Int,
)

internal fun computeTabIndicatorFrame(
    canvasWidth: Float,
    canvasHeight: Float,
    bleedPx: Float,
    insetPx: Float,
    extraPadPx: Float,
    tabCount: Int,
    position: Float,
    velocityTabsPerSec: Float,
    lift: Float,
    baseFillAlpha: Float,
    isDark: Boolean,
    lens: Float? = null,
): TabIndicatorFrame {
    val dockLeft = bleedPx
    val dockTop = bleedPx
    val dockW = (canvasWidth - bleedPx * 2f).coerceAtLeast(1f)
    val dockH = (canvasHeight - bleedPx * 2f).coerceAtLeast(1f)
    val cellW = ((dockW - insetPx * 2f) / tabCount.coerceAtLeast(1)).coerceAtLeast(1f)
    val cellH = (dockH - insetPx * 2f).coerceAtLeast(1f)
    val baseW = cellW + extraPadPx
    val baseH = cellH + extraPadPx
    val speed = abs(velocityTabsPerSec)
    val flow = speed / (speed + 7f)
    val l = lift.coerceIn(-0.03f, 1.15f)
    val posLift = lift.coerceIn(0f, 1.15f)
    val scaleX = 1f + 0.34f * l + 0.14f * flow
    val scaleY = 1f + 0.36f * l - 0.04f * flow
    val maxOverhangX = if (bleedPx > 0f) bleedPx * 0.92f else -1f
    val maxOverhangY = if (bleedPx > 0f) bleedPx * 0.92f else -1f
    val maxW = (dockW + maxOverhangX * 2f).coerceAtLeast(1f)
    val maxH = (dockH + maxOverhangY * 2f).coerceAtLeast(1f)
    val width = (baseW * scaleX).coerceIn(1f, maxW)
    val height = (baseH * scaleY).coerceIn(1f, maxH)
    val centerX = dockLeft + insetPx + cellW * (position + 0.5f)
    val centerY = dockTop + dockH * 0.5f
    val minX = dockLeft - maxOverhangX
    val maxX = (dockLeft + dockW + maxOverhangX - width).coerceAtLeast(minX)
    val left = (centerX - width * 0.5f).coerceIn(minX, maxX)
    val top = centerY - height * 0.5f
    val energy = (posLift * 0.75f + flow * 0.35f).coerceIn(0f, 1f)
    val lensMix = (lens ?: max(posLift, flow * 0.85f)).coerceIn(0f, 1f)
    val fillAlpha = (baseFillAlpha + (if (isDark) 0.12f else 0.07f) * energy)
        .coerceIn(0f, if (isDark) 0.40f else 0.26f)
    val highlightAlpha = ((if (isDark) 0.22f else 0.48f) * energy).coerceIn(0f, 1f)
    val rimAlpha = ((if (isDark) 0.40f else 0.72f) * energy).coerceIn(0f, 1f)
    val shadowAlpha = ((if (isDark) 0.12f else 0.05f) * posLift).coerceIn(0f, 1f)
    return TabIndicatorFrame(
        left = left,
        top = top,
        width = width,
        height = height,
        fillAlpha = fillAlpha,
        highlightAlpha = highlightAlpha,
        rimAlpha = rimAlpha,
        shadowAlpha = shadowAlpha,
        lensMix = lensMix,
    )
}

private class DockGestureState {
    var isDragging: Boolean = false
    var committedFromDrag: Boolean = false
    var releaseVelocityTabsPerSec: Float? = null
}

/**
 * Tab 图标本身，不含玻璃背景。
 *
 * 真·液体玻璃（Android 13+）由 `LiquidGlassView` 这个真实 Android View 提供，
 * 它需要一个与被采样内容并列的 ViewGroup 才能做折射，所以图标要单独放进它的子
 * ComposeView 里（玻璃画完再画图标，不会被自己折射）。
 * API < 33 时退回 [LiquidTabBar] 的 Compose 假玻璃。
 */
@Composable
fun LiquidTabCells(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bleed: Dp = 0.dp,
) {
    LiquidTabCells(
        items = items,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        bleed = bleed,
        lensBridge = null,
    )
}

@Composable
internal fun LiquidTabCells(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    bleed: Dp,
    lensBridge: LiquidPillLensBridge?,
) {
    val p = LocalAbPalette.current
    if (items.isEmpty()) return
    val haptic = rememberAbHaptic()

    val reduceMotion = LocalReduceMotion.current
    val isDark = p.pageBg == DarkPalette.pageBg
    // 物理连续：position 负责横移与释放速度延续，lift 负责按压蓄力/单次冲击弹性浮起与落位；
    // 动画状态仅在 drawBehind 中读取，不触发逐帧重组或重排。
    val position = remember { Animatable(selected.toFloat()) }
    val lift = remember { Animatable(0f) }
    val lens = remember { Animatable(0f) }
    val previousSelection = remember { intArrayOf(selected) }
    var scrubbingTab by remember { mutableIntStateOf(-1) }
    val visualActive = if (scrubbingTab >= 0) scrubbingTab else selected
    val scope = rememberCoroutineScope()
    val gestureState = remember { DockGestureState() }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val inset = 5.dp
    val extraPad = 0.dp
    val density = LocalDensity.current
    val currentItemsCount by rememberUpdatedState(items.size)
    val currentBaseFillAlpha by rememberUpdatedState(p.tabIndicator.alpha)
    val currentIsDark by rememberUpdatedState(isDark)
    val currentBleed by rememberUpdatedState(bleed)
    val currentDensity by rememberUpdatedState(density)

    DisposableEffect(lensBridge) {
        if (lensBridge != null) {
            lensBridge.isDarkProvider = { currentIsDark }
            lensBridge.frameProvider = { canvasW, canvasH ->
                with(currentDensity) {
                    computeTabIndicatorFrame(
                        canvasWidth = canvasW,
                        canvasHeight = canvasH,
                        bleedPx = currentBleed.toPx(),
                        insetPx = inset.toPx(),
                        extraPadPx = extraPad.toPx(),
                        tabCount = currentItemsCount,
                        position = if (currentReduceMotion) currentSelected.toFloat() else position.value,
                        velocityTabsPerSec = if (currentReduceMotion) 0f else position.velocity,
                        lift = if (currentReduceMotion) 0f else lift.value,
                        baseFillAlpha = currentBaseFillAlpha,
                        isDark = currentIsDark,
                        lens = if (currentReduceMotion) 0f else lens.value,
                    )
                }
            }
            lensBridge.notifyFrameChanged()
        }
        onDispose {
            if (lensBridge != null) {
                lensBridge.frameProvider = null
                lensBridge.isDarkProvider = null
            }
        }
    }

    LaunchedEffect(lensBridge, reduceMotion) {
        if (lensBridge == null) return@LaunchedEffect
        snapshotFlow { LensTick(position.value, lift.value, lens.value, currentSelected) }
            .collect {
                lensBridge.notifyFrameChanged()
            }
    }

    LaunchedEffect(selected, reduceMotion) {
        val changed = previousSelection[0] != selected
        previousSelection[0] = selected
        val fromDrag = gestureState.committedFromDrag
        val releaseVel = gestureState.releaseVelocityTabsPerSec
        gestureState.committedFromDrag = false
        gestureState.releaseVelocityTabsPerSec = null

        if (reduceMotion) {
            position.snapTo(selected.toFloat())
            lift.snapTo(0f)
            lens.snapTo(0f)
        } else if (fromDrag || changed) {
            val targetPos = selected.toFloat()
            coroutineScope {
                launch {
                    position.animateTo(
                        targetValue = targetPos,
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = if (fromDrag) 520f else 620f,
                        ),
                        initialVelocity = if (fromDrag) (releaseVel ?: position.velocity) else position.velocity,
                    )
                }
                launch {
                    lift.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 540f))
                }
                launch {
                    lens.animateTo(1f, spring(dampingRatio = 0.94f, stiffness = 480f))
                }
                launch {
                    delay(110)
                    withTimeoutOrNull(120) {
                        snapshotFlow { abs(position.value - targetPos) to abs(position.velocity) }
                            .first { (dist, vel) -> dist <= 0.12f && vel <= 2.2f }
                    }
                    launch {
                        lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 200f))
                    }
                    launch {
                        lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 135f))
                    }
                }
            }
        } else if (!gestureState.isDragging) {
            launch {
                lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f))
            }
            launch {
                lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f))
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val bleedPx = bleed.toPx()
                val insetPx = inset.toPx()
                val extraPadPx = extraPad.toPx()
                val dockLeft = bleedPx
                val dockTop = bleedPx
                val dockW = (size.width - bleedPx * 2f).coerceAtLeast(1f)
                val dockH = (size.height - bleedPx * 2f).coerceAtLeast(1f)
                val stroke = 0.65.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.34f),
                            Color.White.copy(alpha = 0.04f),
                            Color.Black.copy(alpha = 0.07f),
                        ),
                        startY = dockTop,
                        endY = dockTop + dockH,
                    ),
                    topLeft = Offset(dockLeft + stroke / 2f, dockTop + stroke / 2f),
                    size = Size((dockW - stroke).coerceAtLeast(1f), (dockH - stroke).coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(dockH / 2f),
                    style = Stroke(stroke),
                )

                val frame = computeTabIndicatorFrame(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    bleedPx = bleedPx,
                    insetPx = insetPx,
                    extraPadPx = extraPadPx,
                    tabCount = items.size,
                    position = if (reduceMotion) selected.toFloat() else position.value,
                    velocityTabsPerSec = if (reduceMotion) 0f else position.velocity,
                    lift = if (reduceMotion) 0f else lift.value,
                    baseFillAlpha = p.tabIndicator.alpha,
                    isDark = isDark,
                    lens = if (reduceMotion) 0f else lens.value,
                )
                val corner = CornerRadius(frame.height / 2f)
                val pillTopLeft = Offset(frame.left, frame.top)
                val pillSize = Size(frame.width, frame.height)
                val hasRealLens = lensBridge?.shaderReady == true && !reduceMotion
                if (hasRealLens) {
                    val t = smoothstep01(frame.lensMix)
                    val restingAlpha = p.tabIndicator.alpha * (1f - t)
                    if (restingAlpha > 0.004f) {
                        drawRoundRect(
                            color = p.tabIndicator.copy(alpha = restingAlpha),
                            topLeft = pillTopLeft,
                            size = pillSize,
                            cornerRadius = corner,
                        )
                    }
                } else {
                    if (frame.shadowAlpha > 0.005f) {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = frame.shadowAlpha),
                            topLeft = Offset(frame.left, frame.top + 0.5.dp.toPx()),
                            size = pillSize,
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(
                        color = p.tabIndicator.copy(alpha = frame.fillAlpha),
                        topLeft = pillTopLeft,
                        size = pillSize,
                        cornerRadius = corner,
                    )
                    if (frame.highlightAlpha > 0.005f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = frame.highlightAlpha),
                                    Color.Transparent,
                                ),
                                startY = frame.top,
                                endY = frame.top + frame.height,
                            ),
                            topLeft = pillTopLeft,
                            size = pillSize,
                            cornerRadius = corner,
                        )
                    }
                    if (frame.rimAlpha > 0.005f) {
                        val rimStroke = 0.9.dp.toPx()
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = frame.rimAlpha),
                                    Color.White.copy(alpha = frame.rimAlpha * 0.25f),
                                ),
                                startY = frame.top,
                                endY = frame.top + frame.height,
                            ),
                            topLeft = Offset(frame.left + rimStroke / 2f, frame.top + rimStroke / 2f),
                            size = Size((frame.width - rimStroke).coerceAtLeast(1f), (frame.height - rimStroke).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(((frame.height - rimStroke) / 2f).coerceAtLeast(1f)),
                            style = Stroke(rimStroke),
                        )
                    }
                }
            },
    ) {
        val outsideClipPath = remember { Path() }
        val insideClipPath = remember { Path() }

        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val frame = computeTabIndicatorFrame(
                        canvasWidth = size.width,
                        canvasHeight = size.height,
                        bleedPx = bleed.toPx(),
                        insetPx = inset.toPx(),
                        extraPadPx = extraPad.toPx(),
                        tabCount = items.size,
                        position = if (reduceMotion) selected.toFloat() else position.value,
                        velocityTabsPerSec = if (reduceMotion) 0f else position.velocity,
                        lift = if (reduceMotion) 0f else lift.value,
                        baseFillAlpha = p.tabIndicator.alpha,
                        isDark = isDark,
                        lens = if (reduceMotion) 0f else lens.value,
                    )
                    outsideClipPath.rewind()
                    outsideClipPath.addRoundRect(
                        RoundRect(
                            left = frame.left,
                            top = frame.top,
                            right = frame.left + frame.width,
                            bottom = frame.top + frame.height,
                            cornerRadius = CornerRadius(frame.height / 2f),
                        ),
                    )
                    clipPath(outsideClipPath, clipOp = ClipOp.Difference) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(bleed)
                    .padding(horizontal = inset)
                    .selectableGroup()
                    .pointerInput(items.size) {
                        val count = items.size
                        val lastIndex = (count - 1).coerceAtLeast(0)
                        awaitEachGesture {
                            val rowW = size.width.toFloat()
                            val rowH = size.height.toFloat()
                            val cellW = (rowW / count.coerceAtLeast(1)).coerceAtLeast(1f)
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val startTab = tabIndexForX(downPos.x, rowW, count)
                            val velocityTracker = VelocityTracker().apply {
                                addPosition(down.uptimeMillis, downPos)
                            }
                            var dragging = false
                            var cancelled = false
                            var lastHovered = startTab
                            var lastPointerPos = downPos
                            val dragSlop = viewConfiguration.touchSlop * 0.65f

                            if (!currentReduceMotion) {
                                scope.launch {
                                    lift.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 480f))
                                }
                                scope.launch {
                                    lens.animateTo(1f, spring(dampingRatio = 0.95f, stiffness = 420f))
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                if (change == null) {
                                    cancelled = true
                                    break
                                }
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                lastPointerPos = change.position
                                if (!change.pressed) {
                                    if (dragging) change.consume()
                                    break
                                }
                                if (change.isConsumed) {
                                    cancelled = true
                                    break
                                }
                                val dx = change.position.x - downPos.x
                                val dy = change.position.y - downPos.y
                                if (!dragging && abs(dx) > dragSlop && abs(dx) >= abs(dy) * 0.8f) {
                                    dragging = true
                                    gestureState.isDragging = true
                                    scrubbingTab = lastHovered
                                    if (!currentReduceMotion) {
                                        scope.launch {
                                            lift.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 480f))
                                        }
                                        scope.launch {
                                            lens.animateTo(1f, spring(dampingRatio = 0.95f, stiffness = 420f))
                                        }
                                    }
                                }
                                if (dragging) {
                                    change.consume()
                                    val rawPos = (change.position.x / cellW) - 0.5f
                                    val targetPos = rubberBandTabPosition(rawPos, lastIndex)
                                    val hovered = targetPos.roundToInt().coerceIn(0, lastIndex)
                                    if (hovered != lastHovered) {
                                        lastHovered = hovered
                                        scrubbingTab = hovered
                                        haptic.tick()
                                    }
                                    if (currentReduceMotion) {
                                        scope.launch { position.snapTo(hovered.toFloat()) }
                                    } else {
                                        scope.launch {
                                            position.animateTo(
                                                targetValue = targetPos,
                                                animationSpec = spring(dampingRatio = 0.92f, stiffness = 1600f),
                                            )
                                        }
                                    }
                                }
                            }

                            scrubbingTab = -1
                            gestureState.isDragging = false
                            if (cancelled) {
                                if (currentReduceMotion) {
                                    scope.launch {
                                        position.snapTo(currentSelected.toFloat())
                                        lift.snapTo(0f)
                                        lens.snapTo(0f)
                                    }
                                } else {
                                    scope.launch {
                                        position.animateTo(
                                            currentSelected.toFloat(),
                                            spring(dampingRatio = 0.82f, stiffness = 520f),
                                        )
                                    }
                                    scope.launch {
                                        lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f))
                                    }
                                    scope.launch {
                                        lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f))
                                    }
                                }
                            } else if (dragging) {
                                val velTabsPerSec = (velocityTracker.calculateVelocity().x / cellW).coerceIn(-18f, 18f)
                                val targetTab = resolveScrubTargetTab(position.value, velTabsPerSec, count)
                                if (targetTab != currentSelected) {
                                    if (lastHovered != targetTab) haptic.tick()
                                    gestureState.committedFromDrag = true
                                    gestureState.releaseVelocityTabsPerSec = velTabsPerSec
                                    currentOnSelect(targetTab)
                                } else if (currentReduceMotion) {
                                    scope.launch {
                                        position.snapTo(targetTab.toFloat())
                                        lift.snapTo(0f)
                                        lens.snapTo(0f)
                                    }
                                } else {
                                    scope.launch {
                                        position.animateTo(
                                            targetValue = targetTab.toFloat(),
                                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                                            initialVelocity = velTabsPerSec,
                                        )
                                    }
                                    scope.launch {
                                        lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f))
                                    }
                                    scope.launch {
                                        lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f))
                                    }
                                }
                            } else {
                                val inBounds = lastPointerPos.y in (-rowH * 0.6f)..(rowH * 1.6f) &&
                                    lastPointerPos.x in (-cellW * 0.25f)..(rowW + cellW * 0.25f)
                                if (inBounds) {
                                    val tappedTab = tabIndexForX(lastPointerPos.x, rowW, count)
                                    if (tappedTab != currentSelected) {
                                        haptic.tick()
                                        currentOnSelect(tappedTab)
                                    } else {
                                        currentOnSelect(tappedTab)
                                        if (currentReduceMotion) {
                                            scope.launch {
                                                lift.snapTo(0f)
                                                lens.snapTo(0f)
                                            }
                                        } else {
                                            scope.launch {
                                                lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f))
                                            }
                                            scope.launch {
                                                lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f))
                                            }
                                        }
                                    }
                                } else {
                                    if (currentReduceMotion) {
                                        scope.launch {
                                            lift.snapTo(0f)
                                            lens.snapTo(0f)
                                        }
                                    } else {
                                        scope.launch {
                                            lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f))
                                        }
                                        scope.launch {
                                            lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f))
                                        }
                                    }
                                }
                            }
                        }
                    },
            ) {
                val inactiveColor = p.label.copy(alpha = if (isDark) 0.72f else 0.78f)
                items.forEachIndexed { index, item ->
                    TabCell(
                        item = item,
                        active = index == visualActive,
                        color = inactiveColor,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onSelect = {
                            if (index != currentSelected) haptic.tick()
                            // The owner decides reselect semantics (settings returns to its root).
                            currentOnSelect(index)
                        },
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val frame = computeTabIndicatorFrame(
                        canvasWidth = size.width,
                        canvasHeight = size.height,
                        bleedPx = bleed.toPx(),
                        insetPx = inset.toPx(),
                        extraPadPx = extraPad.toPx(),
                        tabCount = items.size,
                        position = if (reduceMotion) selected.toFloat() else position.value,
                        velocityTabsPerSec = if (reduceMotion) 0f else position.velocity,
                        lift = if (reduceMotion) 0f else lift.value,
                        baseFillAlpha = p.tabIndicator.alpha,
                        isDark = isDark,
                        lens = if (reduceMotion) 0f else lens.value,
                    )
                    insideClipPath.rewind()
                    insideClipPath.addRoundRect(
                        RoundRect(
                            left = frame.left,
                            top = frame.top,
                            right = frame.left + frame.width,
                            bottom = frame.top + frame.height,
                            cornerRadius = CornerRadius(frame.height / 2f),
                        ),
                    )
                    clipPath(insideClipPath, clipOp = ClipOp.Intersect) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(bleed)
                    .padding(horizontal = inset)
                    .clearAndSetSemantics {},
            ) {
                items.forEachIndexed { index, item ->
                    TabCell(
                        item = item,
                        active = index == visualActive,
                        color = p.accent,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onSelect = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCell(
    item: TabItem,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    // 选中图标轻微放大带一点回弹，和液态滑块同频。
    val reduceMotion = LocalReduceMotion.current
    val targetScale = if (active) 1f else 0.92f
    val iconScale = animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "tabIconSize",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = active
                onClick {
                    onSelect()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val glyphModifier = Modifier.size(23.dp).graphicsLayer {
            val scale = if (reduceMotion) targetScale else iconScale.value
            scaleX = scale
            scaleY = scale
        }
        Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
            val glyph = item.glyph
            if (glyph != null) {
                // 动态图标自己管动效（内部也读 减少动效），外面只负责选中缩放。
                TabGlyphIcon(glyph = glyph, active = active, tint = color, modifier = glyphModifier)
            } else {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = glyphModifier,
                    tint = color,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = AbType.caption2.copy(
                fontSize = 11.sp,
                lineHeight = 12.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = color,
            maxLines = 1,
        )
    }
}

/** 悬浮底栏上方的呼吸间距，避免滚到底部时最后一张卡片贴死 Dock 上沿或被投影压住。 */
private val dockClearance = 24.dp

/**
 * 页面骨架：大标题在上、内容在下，胶囊 Tab 栏由宿主（MainActivity）浮动在内容之上。
 *
 * 大标题块的高度跟着滚动进度收缩（见 LargeTitleBar），所以内容不会被
 * 常驻的大标题顶下去，滚到底也不会出现「标题已收起但内容还空一截」的错位。
 *
 * 这里**不再**画 Tab 栏、也不画任何底部遮罩：Tab 栏是真正悬浮的，内容从它
 * 下面穿过去（iOS 的观感）。以前这里叠过一条 `BottomFade(solidBelow=true)`，
 * 底端是不透明的 pageBg，结果在胶囊下面压出一条实色横带，看着像旧版
 * NavigationBar 挡内容 —— 已删除。
 */
@Composable
fun PageScaffold(
    title: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
    floatingHeader: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    /**
     * 传非 null 时改用固定子页导航栏（居中标题、右动作；系统返回），
     * 大标题的滚动收缩随之关闭。
     *
     * 明细这种**压在其他 Tab 之上的整页**必须走这条路：大标题栏只在滚动时
     * 才铺底色，静止时标题区是透明的，一旦离开会被下层页面「透」出来。
     */
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    // Tab 栏离屏幕底边的距离：导航条 inset + 视觉留白（别贴着右下角）。
    // 内容底部再额外留一档，滚到最后一行不会被胶囊压住。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (floatingHeader && onBack == null) {
        FloatingPageScaffold(title = title, bottomPadding = dockHeight() + navBottom + AbLayout.pageBottom + dockClearance,
            modifier = modifier,
            onTitleClick = onTitleClick, actions = actions, content = content)
        return
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().align(Alignment.TopCenter)) {
            if (onBack != null) {
                SubPageBar(title = title, onBack = onBack, actions = actions)
            } else {
                ScrollingTitleBar(
                    title = title,
                    listState = listState,
                    subtitle = subtitle,
                    compact = compact,
                    actions = actions,
                )
            }
            content(
                androidx.compose.foundation.layout.PaddingValues(
                    // 明细页把胶囊 Tab 栏留在原位（可以在明细里直接切页），所以照样留白。
                    top = if (onBack != null) AbLayout.pageTop else 0.dp,
                    bottom = dockHeight() + navBottom + AbLayout.pageBottom + dockClearance,
                ),
            )
        }
    }
}

/** Read scroll state in the title scope, leaving the page/list content untouched. */
@Composable
private fun ScrollingTitleBar(
    title: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    subtitle: String?,
    compact: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    val offset by remember(listState) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (listState.firstVisibleItemIndex > 0) 140f
            else listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(140f)
        }
    }
    LargeTitleBar(title = title, scrollOffset = offset, subtitle = subtitle, compact = compact, actions = actions)
}

/**
 * 子页导航栏：独立居中标题与右侧动作，系统负责返回。
 *
 * 用于设置里的二级页 —— iOS 里子页标题是居中的 17sp，不是大标题。
 */
@Composable
fun SubPageBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    val separatorColor = p.separator
    // The title owns the geometric centre, independently of trailing actions.
    androidx.compose.ui.layout.SubcomposeLayout(
        modifier
            .fillMaxWidth()
            .background(if (reduce) p.cardBg else p.glassTint)
            .drawBehind {
                val stroke = 0.5.dp.toPx()
                drawLine(
                    color = separatorColor,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .statusBarsPadding()
    ) { constraints ->
        val inset = AbSpacing.page.roundToPx()
        val available = (constraints.maxWidth - inset * 2).coerceAtLeast(0)
        val action = subcompose("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s), verticalAlignment = Alignment.CenterVertically, content = actions)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = available / 3))
        val titleWidth = (available - action.width * 2).coerceAtLeast(0)
        val heading = subcompose("title") {
            Text(title, style = AbType.navTitle, color = p.label, textAlign = TextAlign.Center,
                maxLines = 2, modifier = Modifier.fillMaxWidth().semantics { heading() })
        }.first().measure(constraints.copy(minWidth = titleWidth, maxWidth = titleWidth, minHeight = 0))
        val height = maxOf(AbSize.navBarHeight.roundToPx(), heading.height + 8.dp.roundToPx(), action.height)
        layout(constraints.maxWidth, height) {
            heading.placeRelative((constraints.maxWidth - heading.width) / 2, (height - heading.height) / 2)
            action.placeRelative(constraints.maxWidth - inset - action.width, (height - action.height) / 2)
        }
    }
}

/**
 * 子页骨架：固定导航栏 + 可滚动内容。
 *
 * 底部留白和 [PageScaffold] 保持一致：子页上胶囊 Tab 栏照样浮在最上层
 * （由 MainActivity 统一绘制），内容必须给它让出导航条 inset + 胶囊高度。
 */
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 底部是否给悬浮胶囊 Tab 栏留白。
     *
     * 二级页上胶囊照样浮着，所以默认要留；但整页式页面（明细、账单操作）
     * 会先隐藏胶囊，再留这 100dp 就只是列表末尾的一块死白，故传 false。
     */
    reserveDock: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(LocalAbPalette.current.pageBg), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
        SubPageBar(title = title, onBack = onBack, actions = actions)
        content(
            androidx.compose.foundation.layout.PaddingValues(
                top = AbLayout.pageTop,
                bottom = if (reserveDock) dockHeight() + navBottom + AbLayout.pageBottom + dockClearance else navBottom + AbLayout.pageBottom,
            ),
        )
    }
    }
}

/** 统一子页推入/推出动画（与设置页一致：220ms 左右横滑）。 */
@Composable
fun SlideSubPage(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val duration = if (reduceMotion) 0 else AbMotion.normal
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.slideInHorizontally(tween(duration)) { it },
        exit = androidx.compose.animation.slideOutHorizontally(tween(duration)) { it },
    ) {
        content()
    }
}

@Composable
fun <T : Any> SlideSubPageValue(
    value: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val remembered = remember { androidx.compose.runtime.mutableStateOf(value) }
    if (value != null) remembered.value = value
    SlideSubPage(visible = value != null, modifier = modifier) {
        remembered.value?.let { content(it) }
    }
}
