package io.github.mymoney.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/** 二级分类外轮廓：圆角矩形。 */
internal class ClusterShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(clusterOuter(size))
}

internal fun clusterOuter(size: Size): Path = rounded(Rect(0f, 0f, size.width, size.height), shellRadius(size))

/**
 * 选中色块：格子圆角矩形，外缘留出 [clusterGap]/2。
 * 贴外胶囊的角用「外壳圆角 − 间距」同心半径，色块边缘与外轮廓等距。
 */
internal fun clusterCell(count: Int, index: Int, size: Size, gap: Float): Path {
    val geometry = clusterCellGeometry(count, index, size, gap) ?: return Path()
    return Path().apply {
        addRoundRect(
            RoundRect(
                geometry.rect,
                topLeft = CornerRadius(geometry.topLeft),
                topRight = CornerRadius(geometry.topRight),
                bottomRight = CornerRadius(geometry.bottomRight),
                bottomLeft = CornerRadius(geometry.bottomLeft),
            )
        )
    }
}

internal fun clusterGap(size: Size): Float = min(size.width, size.height) * 0.045f

internal fun clusterCellRect(count: Int, index: Int, size: Size, gap: Float): Rect = cellRect(count, index, size, gap)

internal data class ClusterCellGeometry(
    val rect: Rect,
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
)

internal fun clusterCellGeometry(count: Int, index: Int, size: Size, gap: Float): ClusterCellGeometry? {
    val edges = cellEdges(count, index) ?: return null
    val rect = cellRect(count, index, size, gap)
    val inset = gap / 2f
    val outer = (shellRadius(size) - inset).coerceAtLeast(0f)
    val inner = cellRadius(rect)
    fun corner(onHorizontalEdge: Boolean, onVerticalEdge: Boolean): Float =
        if (onHorizontalEdge && onVerticalEdge) outer else inner
    var topLeft = corner(edges.left, edges.top)
    var topRight = corner(edges.right, edges.top)
    var bottomRight = corner(edges.right, edges.bottom)
    var bottomLeft = corner(edges.left, edges.bottom)
    // 单角可吃满对边；只保证相邻角半径之和不超过边长，避免被半高误砍掉外壳同心圆角。
    fun fit(a: Float, b: Float, span: Float): Pair<Float, Float> {
        if (span <= 0f) return 0f to 0f
        val sum = a + b
        if (sum <= span) return a to b
        val scale = span / sum
        return a * scale to b * scale
    }
    fit(topLeft, topRight, rect.width).also { (l, r) -> topLeft = l; topRight = r }
    fit(bottomLeft, bottomRight, rect.width).also { (l, r) -> bottomLeft = l; bottomRight = r }
    fit(topLeft, bottomLeft, rect.height).also { (t, b) -> topLeft = t; bottomLeft = b }
    fit(topRight, bottomRight, rect.height).also { (t, b) -> topRight = t; bottomRight = b }
    return ClusterCellGeometry(rect, topLeft, topRight, bottomRight, bottomLeft)
}

internal fun shellRadius(size: Size): Float = min(size.width, size.height) * 0.18f

private fun cellRadius(rect: Rect): Float = min(rect.width, rect.height) * 0.22f

private data class CellEdges(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean)

private fun cellEdges(count: Int, index: Int): CellEdges? {
    val rows = subcategoryRowCounts(count)
    if (rows.isEmpty()) return null
    var cursor = 0
    rows.forEachIndexed { row, cols ->
        if (index < cursor + cols) {
            val col = index - cursor
            return CellEdges(
                left = col == 0,
                top = row == 0,
                right = col == cols - 1,
                bottom = row == rows.lastIndex,
            )
        }
        cursor += cols
    }
    return null
}

private fun rounded(rect: Rect, radius: Float): Path {
    val span = min(rect.width, rect.height).coerceAtLeast(0f)
    val corner = radius.coerceIn(0f, span / 2f)
    return Path().apply { addRoundRect(RoundRect(rect, CornerRadius(corner))) }
}

private fun cellRect(count: Int, index: Int, size: Size, gap: Float): Rect {
    val rows = subcategoryRowCounts(count)
    if (rows.isEmpty()) return Rect(0f, 0f, size.width, size.height)
    val rowH = size.height / rows.size
    var cursor = 0
    rows.forEachIndexed { row, cols ->
        if (index < cursor + cols) {
            val col = index - cursor
            val cellW = size.width / cols
            val left = col * cellW + gap / 2f
            val right = (col + 1) * cellW - gap / 2f
            val top = row * rowH + gap / 2f
            val bottom = (row + 1) * rowH - gap / 2f
            return Rect(left, top, right, bottom)
        }
        cursor += cols
    }
    return Rect(gap / 2f, gap / 2f, size.width - gap / 2f, size.height - gap / 2f)
}
