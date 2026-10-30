package com.example.billkit.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 读通知的去重语义。
 *
 * 为什么需要它：`RootNotificationIngress` 每 5 秒把整份通知列表重读一遍，同一条通知会在连续多轮里
 * 反复出现。没有去重就是「同一笔付款被反复落库」，而这层是唯一挡住它的地方。
 */
class NotificationDumpDedupTest {

    private fun dumped(
        packageName: String = "com.sankuai.meituan",
        title: String = "【示例月付】成功支付12.34元",
        text: String = "查看示例使用详情>",
        postTime: Long = 1_791_372_831_508L,
        slot: String = "",
    ) = NotificationDumpParser.Dumped(packageName, title, text, postTime, slot)

    /**
     * 真机事故回归：微信把一笔 ¥0.01 付款的通知并入 `[2条]` 分组后重新 post，
     * `text` 多了 `[2条]微信支付: ` 前缀、`when` 被改写成重新触发时刻。
     * 只按内容做键时这是「一条新通知」，于是同一笔付款被记了两次账
     * （`bills #3638` 与 `#3639`，间隔 11 分钟）。
     */
    @Test
    fun `同一条通知被重新渲染成带分组前缀时不得重复处理`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        val slot = "0|com.tencent.mm|-656511598|null|10299"
        assertTrue(dedup.accept(dumped("com.tencent.mm", "微信支付", "已支付¥0.01", 1_791_310_117_154L, slot), now))
        // 11 分钟后系统归组重发：正文与时间都变了，槽位没变。
        assertFalse(
            dedup.accept(
                dumped("com.tencent.mm", "微信支付", "[2条]微信支付: 已支付¥0.01", 1_791_310_804_738L, slot),
                now + 11 * 60_000L,
            ),
        )
    }

    /** 有槽位时不去看内容：内容不同也认成同一条通知，这正是要的语义。 */
    @Test
    fun `槽位相同时正文不同也算同一条通知`() {
        val slot = "0|com.tencent.mm|1|null|10299"
        assertEquals(
            NotificationDumpDedup.keyOf(dumped("com.tencent.mm", "微信支付", "已支付¥1.00", 1L, slot)),
            NotificationDumpDedup.keyOf(dumped("com.tencent.mm", "微信支付", "已支付¥9.99", 2L, slot)),
        )
    }

    /** 槽位不同就是两条通知——同一应用连着两笔付款靠这个分开。 */
    @Test
    fun `槽位不同视为两条独立通知`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(
            dedup.accept(dumped("com.tencent.mm", "微信支付", "已支付¥1.00", 1L, "0|com.tencent.mm|1|null|10299"), now),
        )
        assertTrue(
            dedup.accept(dumped("com.tencent.mm", "微信支付", "已支付¥1.00", 2L, "0|com.tencent.mm|2|null|10299"), now + 1_000L),
        )
    }

    /** 老 ROM 没有 key 字段时退回内容哈希，行为与改动前一致。 */
    @Test
    fun `没有槽位时退回内容哈希`() {
        assertNotEquals(
            NotificationDumpDedup.keyOf(dumped()),
            NotificationDumpDedup.keyOf(dumped(title = "【示例月付】成功支付26.78元")),
        )
        assertFalse(NotificationDumpDedup.keyOf(dumped()).startsWith("slot:"))
    }

    @Test
    fun `同一轮里重复读到的同一条通知只处理一次`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(), now))
        assertFalse(dedup.accept(dumped(), now + 1_200L))
        assertFalse(dedup.accept(dumped(), now + 60_000L))
        assertEquals(1, dedup.size())
    }

    @Test
    fun `超过十五分钟后同一条文案可以再次处理`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(), now))
        // TTL 边界取「到点即过期」：14 分 59 秒仍算重复，满 15 分钟就算新的。
        // 取闭区间会把边界留成一处含糊，这里明确成半开区间，测试跟着钉住。
        assertFalse(dedup.accept(dumped(), now + 15 * 60 * 1000L - 1))
        assertTrue(dedup.accept(dumped(), now + 15 * 60 * 1000L))
    }

    @Test
    fun `不同应用发同样文案不会互相顶掉`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(packageName = "com.sankuai.meituan"), now))
        assertTrue(dedup.accept(dumped(packageName = "com.mipay.wallet"), now))
        assertEquals(2, dedup.size())
    }

    @Test
    fun `同毫秒的多条通知按正文区分`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(text = "子通知一"), now))
        assertTrue(dedup.accept(dumped(text = "子通知二"), now))
        assertFalse(dedup.accept(dumped(text = "子通知一"), now + 1_200L))
        assertEquals(2, dedup.size())
    }

    @Test
    fun `发布时间不同就算另一条通知`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(postTime = 1_791_372_831_508L), now))
        assertTrue(dedup.accept(dumped(postTime = 1_791_372_899_508L), now))
    }

    @Test
    fun `clear 之后同一条通知能重新处理`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(), now))
        assertFalse(dedup.accept(dumped(), now))
        dedup.clear()
        assertEquals(0, dedup.size())
        assertTrue(dedup.accept(dumped(), now))
    }

    @Test
    fun `去重键稳定且不含明文`() {
        val a = NotificationDumpDedup.keyOf(dumped())
        val b = NotificationDumpDedup.keyOf(dumped())
        assertEquals(a, b)
        assertFalse(a.contains("示例月付"))
        assertFalse(a.contains("12.34"))
        assertEquals(64, a.length)
        assertNotEquals(a, NotificationDumpDedup.keyOf(dumped(title = "【示例月付】成功支付26.78元")))
    }

    @Test
    fun `表满后淘汰最老的条目而不是无限增长`() {
        val dedup = NotificationDumpDedup(maxEntries = 8)
        var now = 1_000_000L
        repeat(20) { index ->
            assertTrue(dedup.accept(dumped(title = "通知 $index"), now))
            now += 1_000L
        }
        assertTrue(dedup.size() <= 8)
        // 最新的那条必须还在，否则刚处理过的通知会被重复处理
        assertFalse(dedup.accept(dumped(title = "通知 19"), now))
    }
}
