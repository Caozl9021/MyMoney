package com.example.autobook.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.autobook.ui.design.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal data class CategoryPickerRequest(val group: String, val subs: List<String>, val anchor: Rect,
    val original: String?, val sliding: Boolean)

@Stable
internal class CategoryPickerState {
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

    fun show(request: CategoryPickerRequest) {
        cancel()
        this.request = request
        regions.clear()
        viewport = Rect.Zero
        gesture = CategorySlideGesture(request.subs.toSet())
        open = true
    }

    fun move(point: Offset) {
        if (!open || request?.sliding != true) return
        pointer = point
        hovered = gesture?.move(point.x, point.y, regions.map { (name, bounds) ->
            val visible = bounds.intersect(viewport)
            CategoryHitRegion(name, visible.left, visible.top, visible.right, visible.bottom)
        })
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
): Modifier = if (!enabled) this else pointerInput(name) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = awaitLongPressOrCancellation(down.id)
        if (held != null) {
            val layout = coordinates()
            if (layout != null && layout.isAttached) {
                held.consume()
                onStart(layout.boundsInRoot())
                onMove(layout.localToRoot(held.position))
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
                        onMove(layout.localToRoot(change.position))
                        change.consume()
                        if (!change.pressed) {
                            onRelease()
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
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val list = rememberLazyListState()
    val currentSelect by rememberUpdatedState(onSelect)
    val duration = if (reduce) 0 else AbMotion.fast
    val visibleRegions = state.regions.toMap()
    LaunchedEffect(visibleRegions, state.viewport) { state.pointer?.let(state::move) }
    LaunchedEffect(state.open, request, state.pointer, state.viewport) {
        if (!state.open || !request.sliding) return@LaunchedEffect
        while (true) {
            val point = state.pointer ?: break
            val viewport = state.viewport
            val edge = with(density) { 32.dp.toPx() }
            val delta = when {
                point.x !in viewport.left..viewport.right -> 0f
                point.y >= viewport.top && point.y < viewport.top + edge -> -10f
                point.y < viewport.bottom && point.y > viewport.bottom - edge -> 10f
                else -> 0f
            }
            if (delta == 0f) break
            if (list.scrollBy(delta) == 0f) break
            delay(32)
        }
    }
    LaunchedEffect(state.open, request) {
        if (state.open) {
            val index = request.subs.indexOf(request.original).coerceAtLeast(0)
            list.scrollToItem(index)
        }
    }
    // Block underlying controls for the entire enter/exit transition. The original held pointer
    // still belongs to its chip; new taps go to this scrim or to the panel above it.
    AnimatedVisibility(
        visible = state.open,
        enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize().background(p.pageBg.copy(alpha = 0.25f))
            .clickable { state.cancel() }.semantics { contentDescription = "取消二级分类选择" })
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val margin = with(density) { 16.dp.toPx() }
        val widthPx = with(density) { minOf(320.dp, maxWidth - 32.dp).toPx() }
        val heightPx = panelSize.height.toFloat()
        val rootHeight = constraints.maxHeight.toFloat()
        val rootWidth = constraints.maxWidth.toFloat()
        val safeTop = maxOf(margin, WindowInsets.safeDrawing.getTop(density).toFloat())
        val safeBottom = maxOf(margin, WindowInsets.safeDrawing.getBottom(density).toFloat())
        val space = categoryPanelSpace(request.anchor.top - hostOrigin.y, request.anchor.bottom - hostOrigin.y,
            rootHeight, safeTop, safeBottom, margin)
        val availableHeight = with(density) { space.height.toDp() }
        val candidateHeight = minOf(336.dp, availableHeight * 0.6f)
        val x = (request.anchor.center.x - hostOrigin.x - widthPx / 2)
            .coerceIn(margin, (rootWidth - widthPx - margin).coerceAtLeast(margin))
        val above = request.anchor.top - hostOrigin.y - heightPx - margin
        val below = request.anchor.bottom - hostOrigin.y + margin
        val y = (if (space.above) above else below)
            .coerceIn(safeTop, (rootHeight - heightPx - safeBottom).coerceAtLeast(safeTop))
        AnimatedVisibility(
            visible = state.open,
            enter = fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = if (reduce) 1f else 0.96f),
            exit = fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = if (reduce) 1f else 0.96f),
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        ) {
            Column(
                Modifier.width(with(density) { widthPx.toDp() }).heightIn(max = availableHeight)
                    .onGloballyPositioned { panelSize = it.size }
                    .shadow(12.dp, RoundedCornerShape(AbRadius.row))
                    .clip(RoundedCornerShape(AbRadius.row)).background(p.elevatedBg)
                    .clickable(enabled = false) {}.padding(AbSpacing.m)
                    .semantics { paneTitle = "${request.group}二级分类"; isTraversalGroup = true },
                verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(request.group, style = AbType.headline, color = p.label, modifier = Modifier.weight(1f))
                    Text("取消", style = AbType.footnote, color = p.secondaryLabel,
                        modifier = Modifier.heightIn(min = 48.dp).clickable { state.cancel() }
                            .padding(horizontal = AbSpacing.m).wrapContentHeight()
                            .semantics { role = Role.Button; contentDescription = "取消二级分类选择" })
                }
                Text(if (request.sliding) "滑动选择，松手确定 · 移出取消" else "点选二级分类",
                    style = AbType.caption1, color = p.secondaryLabel)
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth().heightIn(max = candidateHeight)
                        .weight(1f, fill = false).onGloballyPositioned { state.viewport = it.boundsInRoot() },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(request.subs, key = { it }) { name ->
                        val active = if (request.sliding) state.hovered == name else request.original == name
                        val background by animateColorAsState(
                            if (active) p.accent.copy(alpha = 0.16f) else p.fill,
                            tween(if (reduce) 0 else AbMotion.selection), label = "category-candidate",
                        )
                        DisposableEffect(name, request) { onDispose { state.regions.remove(name) } }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .onGloballyPositioned { state.regions[name] = it.boundsInRoot() }
                                .clip(RoundedCornerShape(AbRadius.row)).background(background)
                                .clickable(enabled = !request.sliding) {
                                    if (state.open) { state.cancel(); currentSelect(request.group, name) }
                                }
                                .semantics {
                                    selected = active; role = Role.Button
                                    contentDescription = "二级分类：$name"
                                    stateDescription = if (active) { if (request.sliding) "松手选择" else "已选中" } else "未选中"
                                }.padding(horizontal = AbSpacing.m, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                        ) {
                            Text(name, style = AbType.body, color = p.label, modifier = Modifier.weight(1f))
                            if (active) Icon(AbIcons.Check, null, Modifier.size(18.dp), tint = p.accent)
                        }
                    }
                }
                Text(if (request.sliding && state.hovered == null) "松手取消" else if (request.sliding) "松手选择：${state.hovered}" else "可上下滚动查看全部分类",
                    style = AbType.caption1, color = p.secondaryLabel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).wrapContentHeight())
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
                runCatching { com.example.autobook.data.LedgerConfig.resetToDefault() }
                    .onSuccess { onRestored(); restoring = false }
                    .onFailure { restoreError = it.message ?: "恢复默认清单失败，请重试" }
            }
        },
    )
}
