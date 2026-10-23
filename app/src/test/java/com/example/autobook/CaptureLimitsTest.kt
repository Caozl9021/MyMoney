package com.example.autobook

import com.example.billkit.*
import org.junit.Assert.*
import org.junit.Test

class CaptureLimitsTest {
    private data class Node(
        override val value: String = "",
        override val editable: Boolean = false,
        override val top: Int = 0,
        override val left: Int = 0,
        val children: List<Node> = emptyList(),
    ) : TextCollector.Node {
        override val childCount get() = children.size
        override fun childAt(index: Int) = children[index]
    }

    @Test fun `tree exact limits are complete but a further distinct value is truncated`() {
        val collector = TextCollector(maxItems = 2, maxChars = 4)
        val exact = Node(children = listOf(Node("ab"), Node("cd")))
        assertEquals(CollectedText(listOf("ab", "cd"), false), collector.collectNodes(exact))
        val over = exact.copy(children = exact.children + Node("ef"))
        assertEquals(CollectedText(listOf("ab", "cd"), true), collector.collectNodes(over))
    }

    @Test fun `tree never overshoots character budget with the last node`() {
        val result = TextCollector(maxChars = 5).collectNodes(Node(children = listOf(Node("abc"), Node("def"))))
        assertEquals(listOf("abc"), result.items)
        assertTrue(result.truncated)
    }

    @Test fun `tree access budget reports incomplete traversal even without text`() {
        val result = TextCollector(maxVisited = 2).collectNodes(Node(children = listOf(Node(), Node("hidden"))))
        assertTrue(result.truncated)
        assertTrue(result.items.isEmpty())
    }

    @Test fun `tree editable subtree stays private and does not imply truncation`() {
        val root = Node(children = listOf(Node(editable = true, children = listOf(Node("secret"))), Node("bill")))
        assertEquals(CollectedText(listOf("bill"), false), TextCollector().collectNodes(root))
    }

    @Test fun `tree sorting remains geometric and overlong text reports loss`() {
        val root = Node(children = listOf(Node("bottom", top = 48), Node("top"), Node("x".repeat(121))))
        assertEquals(CollectedText(listOf("top", "bottom"), true), TextCollector().collectNodes(root))
    }

    @Test fun `OCR exact limits do not imply truncation after noise and duplicates`() {
        assertEquals(CollectedText(listOf("abc"), false), OcrNormalizer.normalizeResult(listOf("abc", "abc", "·"), maxItems = 1, maxChars = 3))
    }

    @Test fun `OCR item and character overflow report actual loss and preserve strict bounds`() {
        assertEquals(CollectedText(listOf("abc"), true), OcrNormalizer.normalizeResult(listOf("abc", "def"), maxChars = 5))
        assertEquals(CollectedText(listOf("abc"), true), OcrNormalizer.normalizeResult(listOf("abc", "def"), maxItems = 1))
    }

    @Test fun `OCR recommendation cutoff reports omitted source text`() {
        assertEquals(CollectedText(listOf("支付成功"), true), OcrNormalizer.normalizeResult(listOf("支付成功", "猜你喜欢", "商品")))
    }

    @Test fun `OCR visit cap also bounds noise and split currency lookahead`() {
        assertTrue(OcrNormalizer.normalizeResult(listOf("·", "·", "bill"), maxVisited = 2).truncated)
        assertEquals(CollectedText(emptyList(), true), OcrNormalizer.normalizeResult(listOf("¥", "12.34"), maxVisited = 1))
    }

    @Test fun `legacy OCR interface retains the same normalized items`() {
        val raw = listOf("9:50", "5G", "支付成功", "¥", "12.34")
        assertEquals(OcrNormalizer.normalizeResult(raw).items, OcrNormalizer.normalize(raw))
    }

    @Test fun `BillKit annotates skipped draft duplicate and throttled diagnostics`() {
        try {
            BillKit.install(BillKitConfig(minIntervalMs = 1500), sink = {})
            BillKit.offer(UiSnapshot("test", null, listOf("not a bill"), truncated = true), now = 10000)
            assertEquals("skip:not-a-bill-page [截断]", DebugBus.state.value?.outcome)
            val bill = Samples.snapshot(Packages.WECHAT, Samples.wechatQrPay).copy(truncated = true)
            assertTrue(BillKit.offer(bill, now = 10000) is BillPipeline.Outcome.Draft)
            assertTrue(DebugBus.state.value!!.outcome.startsWith("draft:"))
            assertTrue(DebugBus.state.value!!.outcome.endsWith(" [截断]"))
            BillKit.offer(bill, now = 10001)
            assertEquals("skip:throttled [截断]", DebugBus.state.value?.outcome)
            BillKit.offer(bill, now = 12000)
            assertEquals("skip:duplicate [截断]", DebugBus.state.value?.outcome)
        } finally {
            BillKit.reset()
            DebugBus.clear()
        }
    }
}
