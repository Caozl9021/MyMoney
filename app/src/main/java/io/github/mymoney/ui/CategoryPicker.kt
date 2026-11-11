package io.github.mymoney.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mymoney.ui.design.*
import kotlin.math.roundToInt

internal data class CategoryPickerRequest(val group: String, val subs: List<String>, val anchor: Rect,
    val original: String?, val sliding: Boolean, val row: Rect = anchor)

/** 1–2 横排，3 等边三角形，4 正方形，其后按约定行数；超过 10 个每行 4 个。 */
internal fun subcategoryRowCounts(count: Int): List<Int> = when (count) {
    0 -> emptyList()
    1, 2 -> listOf(count)
    3 -> listOf(1, 2)
    4 -> listOf(2, 2)
    5 -> listOf(3, 2)
    6 -> listOf(3, 3)
    7 -> listOf(2, 3, 2)
    8 -> listOf(2, 3, 3)
    9 -> listOf(3, 3, 3)
    10 -> listOf(3, 4, 3)
    else -> List((count + 3) / 4) { index -> minOf(4, count - index * 4) }
}

/** 一行贴住分类行；两行用第一行；三行及以上用中间那一行。 */
internal fun subcategoryAlignRow(rowCount: Int): Int = when {
    rowCount <= 2 -> 0
    else -> rowCount / 2
}

@Stable
internal class CategoryPickerState {
    var generation by mutableIntStateOf(0)
        private set
    var request by mutableStateOf<CategoryPickerRequest?>(null)
        private set
    var open by mutableStateOf(false)
        private set
    var hovered by mutableStateOf<String?>(null)
        private set
    var pointer by mutableStateOf<Offset?>(null)
        private set
    var viewport by mutableStateOf(Rect.Zero)
    val regions = mutableStateMapOf<String, Rect>()
    private var gesture: CategorySlideGesture? = null
    var onHoverEnter: (() -> Unit)? = null

    fun show(request: CategoryPickerRequest) {
        cancel()
        generation++
        this.request = request
        regions.clear()
        viewport = Rect.Zero
        gesture = CategorySlideGesture(request.subs.toSet())
        open = true
    }

    fun move(point: Offset) {
        if (!open || request?.sliding != true) return
        pointer = point
        val next = gesture?.move(point.x, point.y, regions.map { (name, bounds) ->
            val visible = bounds.intersect(viewport)
            CategoryHitRegion(name, visible.left, visible.top, visible.right, visible.bottom)
        })
        if (next != null && next != hovered) onHoverEnter?.invoke()
        hovered = next
    }

    fun release(): Pair<String, String>? {
        if (!open) return null
        val result = gesture?.release()?.let { request!!.group to it }
        cancel()
        return result
    }

    fun cancel() {
        gesture?.cancel()
        open = false
        hovered = null
        pointer = null
    }
}

/** Added alongside clickable: short taps and pre-long-press scrolling keep their existing handlers. */
internal fun Modifier.categorySlide(
    name: String,
    enabled: Boolean,
    coordinates: () -> LayoutCoordinates?,
    onStart: (Rect) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onPress: () -> Unit = {},
): Modifier = if (!enabled) this else pointerInput(name) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress()
        val held = awaitLongPressOrCancellation(down.id)
        if (held != null) {
            val layout = coordinates()
            if (layout != null && layout.isAttached) {
                held.consume()
                onStart(layout.boundsInRoot())
                val origin = held.position
                var moved = false
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.any { it.id != down.id && it.pressed } || change == null || !layout.isAttached) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        // ACTION_CANCEL and a consumed drag are cancellations, not implicit up selections.
                        if (change.isConsumed) break
                        if ((change.position - origin).getDistance() > viewConfiguration.touchSlop / 2f) moved = true
                        if (moved) onMove(layout.localToRoot(change.position))
                        change.consume()
                        if (!change.pressed) {
                            if (moved) onRelease() else onCancel()
                            released = true
                            break
                        }
                    }
                } finally {
                    if (!released) onCancel()
                }
            }
        }
    }
}

/** Same Compose root as the sheet: opening the panel never transfers the held pointer to a new window. */
@Composable
internal fun BoxScope.CategoryPicker(
    state: CategoryPickerState,
    hostOrigin: Offset,
    onSelect: (String, String) -> Unit,
) {
    val request = state.request ?: return
    val p = LocalAbPalette.current
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val haptic = rememberAbHaptic()
    DisposableEffect(state, haptic) {
        val enter: () -> Unit = { haptic.tick() }
        state.onHoverEnter = enter
        onDispose { if (state.onHoverEnter === enter) state.onHoverEnter = null }
    }
    val currentSelect by rememberUpdatedState(onSelect)
    val duration = if (reduce) 0 else AbMotion.fast
    val visibleRegions = state.regions.toMap()
    LaunchedEffect(visibleRegions, state.viewport) { state.pointer?.let(state::move) }
    AnimatedVisibility(visible = state.open, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration)),
        modifier = Modifier.matchParentSize()) {
        Box(Modifier.fillMaxSize().clickable { state.cancel() }.semantics { contentDescription = "取消二级分类选择" })
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val margin = with(density) { 12.dp.toPx() }
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val counts = subcategoryRowCounts(request.subs.size)
        val alignIndex = subcategoryAlignRow(counts.size)
        val widest = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val bubbleW = with(density) { (widest * 78).dp.coerceAtMost((maxW - margin * 2f).toDp()) }
        val bubbleH = (counts.size * 52).dp
        val bubbleHpx = with(density) { bubbleH.toPx() }
        val alignCenter = bubbleHpx * (alignIndex + 0.5f) / counts.size.coerceAtLeast(1)
        var bubbleWidth by remember(state.generation) { mutableFloatStateOf(with(density) { bubbleW.toPx() }) }
        var bubbleHeight by remember(state.generation) { mutableFloatStateOf(bubbleHpx) }
        val anchor = request.anchor
        val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
        val x = (anchor.center.x - hostOrigin.x - bubbleWidth / 2f)
            .coerceIn(margin, (maxW - margin - bubbleWidth).coerceAtLeast(margin))
        val anchorMid = anchor.center.y - hostOrigin.y
        val y = (anchorMid - alignCenter).coerceIn(safeTop, (maxH - margin - bubbleHeight).coerceAtLeast(safeTop))
        AnimatedVisibility(visible = state.open,
            enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration)),
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged {
                bubbleWidth = it.width.toFloat()
                bubbleHeight = it.height.toFloat()
            }) {
            key(state.generation) {
            val outerShape = ClusterShape()
            Box(
                modifier = Modifier.size(bubbleW, bubbleH)
                    .shadow(if (reduce) 0.dp else 8.dp, outerShape)
                    .clip(outerShape).background(p.elevatedBg)
                    .border(0.5.dp, p.separator.copy(alpha = .22f), outerShape)
                    .onGloballyPositioned { state.viewport = it.boundsInRoot() }
                    .semantics { paneTitle = "二级分类"; isTraversalGroup = true }
                    .testTag("category-inline-bubble"),
            ) {
                val activeName = if (request.sliding) state.hovered else request.original
                val grid = Size(with(density) { bubbleW.toPx() }, bubbleHpx)
                val gap = clusterGap(grid)
                Canvas(Modifier.matchParentSize()) {
                    val activeIndex = request.subs.indexOf(activeName)
                    if (activeIndex < 0 || size.minDimension <= 0f) return@Canvas
                    val cellGap = clusterGap(size)
                    drawPath(clusterCell(request.subs.size, activeIndex, size, cellGap), p.accent.copy(alpha = 0.36f))
                }
                request.subs.forEachIndexed { index, name -> key(name) {
                    val active = activeName == name
                    val foreground by animateColorAsState(if (active) p.accent else p.label,
                        tween(if (reduce) 0 else 100), label = "category-foreground")
                    val rect = clusterCellRect(request.subs.size, index, grid, gap)
                    DisposableEffect(name, request) { onDispose { state.regions.remove(name) } }
                    Box(
                        Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                            .onGloballyPositioned { state.regions[name] = it.boundsInRoot() }
                            .clickable(enabled = !request.sliding) { if (state.open) { state.cancel(); currentSelect(request.group, name) } }
                            .semantics { selected = active; role = Role.Button; contentDescription = "二级分类：$name" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(name, style = AbType.footnote, color = foreground, maxLines = 1)
                    }
                } }
            }
            }
        }
    }
}

/** Empty picker actions are explicit writes, with a second confirmation for the full reset. */
@Composable
internal fun EmptyLedgerPickerActions(label: String, enabled: Boolean, onAdd: (String) -> Unit, onRestored: () -> Unit) {
    var adding by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var restoring by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl)) {
        Text("还没有$label，可新增或恢复默认清单", style = AbType.footnote, color = p.secondaryLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            androidx.compose.material3.TextButton(enabled = enabled, onClick = { addError = null; adding = true }) { Text("新增$label") }
            androidx.compose.material3.TextButton(enabled = enabled, onClick = { restoreError = null; restoring = true }) { Text("恢复默认清单") }
        }
    }
    if (adding) AppDialog(title = "新增$label", onDismiss = { adding = false }) {
        Text("${label}名", style = AbType.footnote, color = p.secondaryLabel)
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.fill)
                .padding(horizontal = AbSpacing.m, vertical = 12.dp),
        ) {
            AbTextField(value = name, onValueChange = { name = it; addError = null },
                placeholder = "例如：${label}名",
                modifier = Modifier.fillMaxWidth())
        }
        if (addError != null) InlineMessage(addError.orEmpty(), isError = true, announce = true)
        AppDialogActions(
            confirmLabel = "添加",
            onConfirm = {
                addError = null
                runCatching { onAdd(name.trim()) }
                    .onSuccess { name = ""; adding = false }
                    .onFailure { addError = it.message ?: "新增${label}失败，请重试" }
            },
            onDismiss = { adding = false },
            confirmEnabled = name.isNotBlank() && enabled,
        )
    }
    if (restoring) RestoreLedgerDefaultsDialog(
        onDismiss = { restoring = false },
        errorMessage = restoreError,
        onConfirm = {
            if (enabled) {
                restoreError = null
                runCatching { io.github.mymoney.data.LedgerConfig.resetToDefault() }
                    .onSuccess { onRestored(); restoring = false }
                    .onFailure { restoreError = it.message ?: "恢复默认清单失败，请重试" }
            }
        },
    )
}
