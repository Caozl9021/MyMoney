package com.example.billkit

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把当前窗口的无障碍树压成「有序文本列表」。
 *
 * 关键点：
 * 1. 跳过 isEditable 节点 —— 从设计上不碰输入框（密码、手机号、验证码）。
 * 2. 按屏幕几何位置（top 分桶 + left）排序，得到稳定阅读顺序；
 *    否则不同厂商的树遍历顺序会让"标签→值"的邻接关系错位。
 * 3. 有条数与字数上限，避免大列表页把 CPU 拖死。
 */
class TextCollector(
    private val maxItems: Int = 200,
    private val maxChars: Int = 2000,
    private val rowBucketPx: Int = 24,
    private val maxVisited: Int = 5_000,
) {

    private class Row(val text: String, val top: Int, val left: Int, val order: Int)

    fun collect(root: AccessibilityNodeInfo?): List<String> = collectResult(root).items

    /** Platform-independent seam for traversal limits and privacy regression tests. */
    interface Node {
        val editable: Boolean
        val value: String
        val top: Int
        val left: Int
        val childCount: Int
        fun childAt(index: Int): Node?
    }

    private class AccessibilityNode(private val node: AccessibilityNodeInfo) : Node {
        private val bounds by lazy { Rect().also { node.getBoundsInScreen(it) } }
        override val editable get() = node.isEditable
        override val value get() = node.text?.toString()?.trim().orEmpty()
            .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
        override val top get() = bounds.top
        override val left get() = bounds.left
        override val childCount get() = node.childCount
        override fun childAt(index: Int): Node? = node.getChild(index)?.let { AccessibilityNode(it) }
    }

    fun collectResult(root: AccessibilityNodeInfo?): CollectedText =
        collectNodes(root?.let { AccessibilityNode(it) })

    fun collectNodes(root: Node?): CollectedText {
        if (root == null) return CollectedText(emptyList(), false)

        val rows = ArrayList<Row>(64)
        val budget = TextCaptureBudget(maxItems, maxChars)
        var order = 0
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty()) {
            if (visited++ >= maxVisited) {
                budget.markTruncated()
                break
            }
            val node = stack.removeLast()

            if (node.editable) continue // 输入框整棵子树都不看

            val value = node.value

            if (value.length > 120) budget.markTruncated()
            if (value.isNotEmpty() && value.length <= 120) {
                val duplicateOfPrev = rows.lastOrNull()?.text == value
                if (!duplicateOfPrev) {
                    if (!budget.add(value)) break
                    rows += Row(value, node.top, node.left, order++)
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.childAt(i)?.let { stack.addLast(it) }
            }
        }

        return CollectedText(
            rows.sortedWith(compareBy({ it.top / rowBucketPx }, { it.left }, { it.order })).map { it.text },
            budget.truncated,
        )
    }
}
