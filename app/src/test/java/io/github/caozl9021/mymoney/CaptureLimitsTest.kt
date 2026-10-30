package io.github.caozl9021.mymoney

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

    @Test fun `explicit distinct transaction is not swallowed by page throttle`() {
        try {
            val delivered = mutableListOf<com.example.billkit.BillDraft>()
            BillKit.install(BillKitConfig(minIntervalMs = 1500), sink = { delivered += it })
            BillKit.offer(Samples.snapshot(Packages.WECHAT, Samples.wechatBill), now = 10000)
            val second = Samples.wechatBill.map { if (it == "4200001234202410211234567890") "4200001234202410211234567891" else it }
            BillKit.offer(Samples.snapshot(Packages.WECHAT, second), now = 10001)
            assertEquals(2, delivered.size)
            assertNotEquals(delivered[0].transactionKey, delivered[1].transactionKey)
        } finally { BillKit.reset(); DebugBus.clear() }
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
            // The identical screen offered again is recognised as the same page first.
            BillKit.offer(bill, now = 10001)
            assertEquals("skip:same-page [截断]", DebugBus.state.value?.outcome)
            // A page without an explicit independent transaction ID is still throttled.
            val other = Samples.snapshot(Packages.WECHAT, Samples.wechatBill.filterNot { it == "交易单号" || it == "4200001234202410211234567890" }).copy(truncated = true)
            BillKit.offer(other, now = 10002)
            assertEquals("skip:throttled [截断]", DebugBus.state.value?.outcome)
            // Leaving the page ends the session; the 90s fingerprint window still catches the same bill.
            BillKit.offer(bill, now = 12000)
            assertEquals("skip:duplicate [截断]", DebugBus.state.value?.outcome)
        } finally {
            BillKit.reset()
            DebugBus.clear()
        }
    }
}
