package io.github.mymoney.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * 长按后上下拖动重排。拖动期间用本地顺序，避免父列表刷新导致 from/to 错位。
 * 换位时回调 [onReorder] 传入完整新顺序。
 */
@Composable
fun <T> DragReorderColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    estimatedItemHeight: Dp = 56.dp,
    itemContent: @Composable (item: T, index: Int, dragging: Boolean) -> Unit,
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptic = rememberAbHaptic()
    val density = LocalDensity.current
    val fallbackPx = remember(estimatedItemHeight, density) { with(density) { estimatedItemHeight.toPx() } }
    var localItems by remember { mutableStateOf(items) }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var measuredHeights by remember { mutableStateOf(mapOf<Any, Int>()) }

    LaunchedEffect(items) {
        if (draggingKey == null) localItems = items
    }

    fun indexOfKey(k: Any?): Int = if (k == null) -1 else localItems.indexOfFirst { keyOf(it) == k }

    fun heightAt(index: Int): Float {
        val item = localItems.getOrNull(index) ?: return fallbackPx
        return measuredHeights[keyOf(item)]?.toFloat() ?: fallbackPx
    }

    val dragCorner = 14.dp

    Column(modifier.fillMaxWidth()) {
        localItems.forEachIndexed { index, item ->
            val itemKey = keyOf(item)
            key(itemKey) {
                val isDragging = draggingKey == itemKey
                val liftProgress by animateFloatAsState(
                    targetValue = if (isDragging) 1f else 0f,
                    animationSpec = tween(if (reduceMotion) 0 else AbMotion.fast),
                    label = "drag-lift",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .onSizeChanged { size ->
                            if (measuredHeights[itemKey] != size.height) {
                                measuredHeights = measuredHeights + (itemKey to size.height)
                            }
                        }
                        .graphicsLayer {
                            if (isDragging || liftProgress > 0f) {
                                translationY = if (isDragging) dragOffset else 0f
                                // 轻微内缩横向宽度以露出四角圆角与细微边缘阴影，不使用 Android RenderNode 大黑影
                                scaleX = 1f - 0.016f * liftProgress
                                scaleY = 1f + 0.01f * liftProgress
                            }
                        }
                        .drawWithContent {
                            if (isDragging || liftProgress > 0f) {
                                val r = dragCorner.toPx()
                                val cr = CornerRadius(r, r)
                                // 边缘仅带一圈极浅的柔和外晕（用细描边向外扩散，不叠加实心黑底）
                                val strokePx = 1.2.dp.toPx()
                                for (step in 1..4) {
                                    val spread = (step * 0.9f).dp.toPx()
                                    val alpha = (0.022f / step) * liftProgress
                                    drawRoundRect(
                                        color = Color.Black.copy(alpha = alpha),
                                        topLeft = Offset(-spread, -spread + 0.5.dp.toPx()),
                                        size = Size(size.width + spread * 2, size.height + spread * 2),
                                        cornerRadius = CornerRadius(r + spread, r + spread),
                                        style = Stroke(width = strokePx),
                                    )
                                }
                                drawRoundRect(
                                    color = p.cardBg,
                                    cornerRadius = cr,
                                )
                                drawContent()
                                // 注意：p.separator 自身 alpha 仅 0.18f，绝不能直接 copy(alpha = 0.85f) 覆盖成深黑
                                drawRoundRect(
                                    color = p.separator.copy(alpha = p.separator.alpha * 0.45f * liftProgress),
                                    cornerRadius = cr,
                                    style = Stroke(width = 0.5.dp.toPx()),
                                )
                            } else {
                                drawContent()
                            }
                        }
                        .pointerInput(itemKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.select()
                                    draggingKey = itemKey
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val from = indexOfKey(draggingKey)
                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                    dragOffset += dragAmount.y
                                    val step = heightAt(from).coerceAtLeast(1f)
                                    val delta = (dragOffset / step).roundToInt()
                                    val target = (from + delta).coerceIn(0, localItems.lastIndex)
                                    if (target != from) {
                                        var consumed = 0f
                                        if (target > from) {
                                            for (i in from until target) consumed += heightAt(i)
                                        } else {
                                            for (i in target until from) consumed -= heightAt(i)
                                        }
                                        val next = localItems.toMutableList().also {
                                            it.add(target, it.removeAt(from))
                                        }
                                        localItems = next
                                        dragOffset -= consumed
                                        haptic.tick()
                                        onReorder(next)
                                    }
                                },
                            )
                        },
                ) {
                    itemContent(item, index, isDragging)
                }
            }
        }
    }
}
