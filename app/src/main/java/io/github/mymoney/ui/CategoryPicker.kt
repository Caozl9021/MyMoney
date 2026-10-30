package io.github.mymoney.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mymoney.ui.design.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

internal data class CategoryPickerRequest(val group: String, val subs: List<String>, val anchor: Rect,
    val original: String?, val sliding: Boolean, val row: Rect = anchor)

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
    val list = rememberScrollState()
    val currentSelect by rememberUpdatedState(onSelect)
    val duration = if (reduce) 0 else AbMotion.fast
    val visibleRegions = state.regions.toMap()
    LaunchedEffect(visibleRegions, state.viewport) { state.pointer?.let(state::move) }
    LaunchedEffect(state.open, request, state.pointer, state.viewport) {
        if (!state.open || !request.sliding) return@LaunchedEffect
        while (true) {
            val point = state.pointer ?: break
            val viewport = state.viewport
            val edge = with(density) { 24.dp.toPx() }
            val delta = when {
                point.y < viewport.top || point.y >= viewport.bottom -> 0f
                point.x >= viewport.left && point.x < viewport.left + edge -> -8f
                point.x < viewport.right && point.x > viewport.right - edge -> 8f
                else -> 0f
            }
            if (delta == 0f || list.scrollBy(delta) == 0f) break
            delay(32)
        }
    }
    LaunchedEffect(state.open, state.generation) {
        if (state.open) {
            list.scrollTo(0)
            if (!request.sliding && request.original != null) {
                val (viewport, item) = snapshotFlow { state.viewport to state.regions[request.original] }
                    .first { (viewport, item) -> viewport.width > 0f && item != null }
                item?.let { list.scrollBy((it.left - viewport.left - with(density) { 4.dp.toPx() }).coerceAtLeast(0f)) }
            }
        }
    }
    AnimatedVisibility(visible = state.open, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration)),
        modifier = Modifier.matchParentSize()) {
        // Transparent interaction shield; never dim the entire sheet for a category choice.
        Box(Modifier.fillMaxSize().clickable { state.cancel() }.semantics { contentDescription = "取消二级分类选择" })
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val margin = with(density) { 20.dp.toPx() }
        val row = request.row.takeIf { it.width > 0f } ?: request.anchor
        val x = (row.left - hostOrigin.x + with(density) { 8.dp.toPx() }).coerceIn(margin, (constraints.maxWidth - margin - 48f).coerceAtLeast(margin))
        val width = (constraints.maxWidth - margin - x).coerceAtLeast(48f)
        val y = (request.anchor.top - hostOrigin.y).coerceAtLeast(WindowInsets.safeDrawing.getTop(density).toFloat())
        AnimatedVisibility(visible = state.open,
            enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration)),
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }) {
            key(state.generation) {
            val outerShape = RoundedCornerShape(28.dp)
            Box(
                modifier = Modifier.widthIn(max = with(density) { width.toDp() })
                    .shadow(if (reduce) 0.dp else 6.dp, outerShape)
                    .clip(outerShape).background(p.elevatedBg)
                    .border(0.5.dp, p.separator.copy(alpha = .18f), outerShape)
                    .onGloballyPositioned { state.viewport = it.boundsInRoot() }
                    .semantics { paneTitle = "二级分类"; isTraversalGroup = true }
                    .testTag("category-inline-bubble"),
            ) {
                val activeName = if (request.sliding) state.hovered else request.original
                val target = activeName?.let { state.regions[it] }?.takeIf { state.viewport.width > 0f }?.let {
                    // Content coordinates stay stable during scrolling: the fill tracks its text without lag.
                    it.translate(Offset(list.value.toFloat() - state.viewport.left, -state.viewport.top))
                }
                CategorySelectionSlider(target, list.value, state.open)
                Row(Modifier.horizontalScroll(list).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    request.subs.forEach { name -> key(name) {
                        val active = activeName == name
                        val foreground by animateColorAsState(if (active) p.accent else p.label,
                            tween(if (reduce) 0 else 100), label = "category-foreground")
                        DisposableEffect(name, request) { onDispose { state.regions.remove(name) } }
                        Box(Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp)
                            .onGloballyPositioned { state.regions[name] = it.boundsInRoot() }
                            .clickable(enabled = !request.sliding) { if (state.open) { state.cancel(); currentSelect(request.group, name) } }
                            .semantics { selected = active; role = Role.Button; contentDescription = "二级分类：$name" }
                            .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(name, style = AbType.footnote, color = foreground,
                                modifier = Modifier.widthIn(max = 220.dp))
                        }
                    } }
                }
            }
            }
        }
    }
}

/** One decorative fill moves between measured candidates; its animation never changes hit regions. */
@Composable
private fun BoxScope.CategorySelectionSlider(target: Rect?, scroll: Int, open: Boolean) {
    val p = LocalAbPalette.current
    val reduce = LocalReduceMotion.current
    val frame = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val alpha = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduce, open) {
        if (target == null || !open) {
            alpha.animateTo(0f, tween(if (reduce) 0 else 80))
        } else coroutineScope {
            launch { alpha.animateTo(1f, tween(if (reduce) 0 else 90)) }
            if (!placed || reduce) { frame.snapTo(target); placed = true }
            else frame.animateTo(target, spring(dampingRatio = .72f, stiffness = 520f))
        }
    }
    Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
        if (!placed || alpha.value <= 0f) return@Canvas
        val rect = frame.value.translate(Offset(-scroll.toFloat(), 0f))
        val inset = 4.dp.toPx()
        clipRect(left = inset, top = inset, right = (size.width - inset).coerceAtLeast(inset), bottom = (size.height - inset).coerceAtLeast(inset)) {
            drawRoundRect(p.accent.copy(alpha = .15f * alpha.value), rect.topLeft, rect.size, CornerRadius(24.dp.toPx()))
            drawRoundRect(p.accent.copy(alpha = .10f * alpha.value), rect.topLeft, rect.size, CornerRadius(24.dp.toPx()), style = Stroke(.6.dp.toPx()))
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
    if (adding) androidx.compose.material3.AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("新增$label") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it; addError = null },
                label = { Text("${label}名") }, singleLine = true, isError = addError != null)
            if (addError != null) InlineMessage(addError.orEmpty(), isError = true, announce = true)
        } },
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank() && enabled,
            onClick = {
                addError = null
                runCatching { onAdd(name.trim()) }
                    .onSuccess { name = ""; adding = false }
                    .onFailure { addError = it.message ?: "新增${label}失败，请重试" }
            }) { Text("添加") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { adding = false }) { Text("取消") } },
    )
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
