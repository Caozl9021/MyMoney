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

    /**
     * 槽位即身份：同一通知位上正文更新不算新通知。
     *
     * 这是有意的取舍——归组、补字段、改措辞比重用同一位发第二笔常见得多。
     * 代价写在类注释里：真若出现「同槽位复用发第二笔」，这一层会压掉它；
     * 到那时应改成「内容变化 → 待核对」而不是在这里直接放行。
     */
    @Test
    fun `槽位相同时正文不同也算同一条通知`() {
        val slot = "0|com.tencent.mm|1|null|10299"
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertEquals(
            NotificationDumpDedup.keyOf(dumped("com.tencent.mm", "微信支付", "已支付¥1.00", 1L, slot)),
            NotificationDumpDedup.keyOf(dumped("com.tencent.mm", "微信支付", "已支付¥9.99", 2L, slot)),
        )
        assertTrue(dedup.accept(dumped("com.tencent.mm", "微信支付", "已支付¥1.00", 1L, slot), now))
        assertFalse(dedup.accept(dumped("com.tencent.mm", "微信支付", "已支付¥9.99", 2L, slot), now + 5_000L))
    }

    /** 没有槽位的老 ROM 靠内容做身份，归一化必须抹掉归组前缀，否则同一笔会记两次。 */
    @Test
    fun `没有槽位时分组前缀不会产生第二条`() {
        val plain = dumped("com.tencent.mm", "微信支付", "已支付¥0.01", 1L)
        val grouped = dumped("com.tencent.mm", "微信支付", "[2条]微信支付: 已支付¥0.01", 1L)
        val fullWidth = dumped("com.tencent.mm", "微信支付", "【3条消息】微信支付：　已支付¥0.01", 1L)
        assertEquals(NotificationDumpDedup.keyOf(plain), NotificationDumpDedup.keyOf(grouped))
        assertEquals(NotificationDumpDedup.keyOf(plain), NotificationDumpDedup.keyOf(fullWidth))
    }

    /** 归一化不能过头：金额与商户差异必须保留，否则真实第二笔会被合并掉。 */
    @Test
    fun `归一化不吞掉真实差异`() {
        val base = NotificationDumpDedup.normalize("微信支付", "已支付¥0.01")
        assertNotEquals(base, NotificationDumpDedup.normalize("微信支付", "已支付¥0.02"))
        assertNotEquals(base, NotificationDumpDedup.normalize("收款方", "已支付¥0.01"))
        assertNotEquals(base, NotificationDumpDedup.normalize("微信支付", "已收款¥0.01"))
    }

    /** 归一化只抹装饰，不改内容本身。 */
    @Test
    fun `归一化抹掉分组前缀与空白差异`() {
        val plain = NotificationDumpDedup.normalize("微信支付", "已支付¥0.01")
        assertEquals(plain, NotificationDumpDedup.normalize("微信支付", "[2条]微信支付: 已支付¥0.01"))
        assertEquals(plain, NotificationDumpDedup.normalize("微信支付", "【3条消息】微信支付：　已支付¥0.01"))
        assertEquals(plain, NotificationDumpDedup.normalize(" 微信支付 ", "已支付¥0.01  "))
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

    /**
     * 旧实现把 TTL 当「抑制窗口」：同一条通知只要在通知栏里挂满 15 分钟就会被重放一次。
     * 现在的 TTL 是「记住多久」——每轮被读到就续命，挂再久也只处理一次。
     */
    @Test
    fun `仍在通知栏中的旧通知不会被重放`() {
        val dedup = NotificationDumpDedup()
        var now = 1_000_000L
        assertTrue(dedup.accept(dumped(), now))
        repeat(60) {
            now += 60_000L // 连续一小时，每轮都还在通知栏里
            assertFalse(dedup.accept(dumped(), now))
        }
        assertEquals(1, dedup.size())
    }

    /** 消失超过保留窗口后再出现，才算新通知。 */
    @Test
    fun `消失超过保留窗口后重现视为新通知`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        assertTrue(dedup.accept(dumped(), now))
        // 边界取半开区间：满 15 分钟即视为已遗忘。
        assertFalse(dedup.accept(dumped(), now + 1_000L))
        assertTrue(dedup.accept(dumped(), now + 15 * 60 * 1000L + 2_000L))
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
