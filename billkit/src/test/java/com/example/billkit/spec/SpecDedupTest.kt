package com.example.billkit.spec

import com.example.billkit.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §7.4 第二层去重的契约。
 *
 * 这里守的是一条底线：**只有跨来源才合并**。同一来源在窗口内报两次同额，可能是
 * 连着买了两杯同样的咖啡，也可能是用户翻回旧页面被抓了第二次——无法区分时，
 * 宁可多留一条待核对，也不能把第二笔真实消费吞掉。
 */
class SpecDedupTest {

    private val t0 = 1_791_372_831_000L

    @Test
    fun `同包名同金额同时间桶得到同一个身份`() {
        val a = SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, t0)
        val b = SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, t0 + 1_000)
        assertEquals(a, b)
    }

    @Test
    fun `金额或方向或包名不同就是不同身份`() {
        val base = SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, t0)
        assertNotEquals(base, SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2678, t0))
        assertNotEquals(base, SpecDedup.key("com.tencent.mm", BillType.INCOME, 2677, t0))
        assertNotEquals(base, SpecDedup.key("com.eg.android.AlipayGphone", BillType.EXPENSE, 2677, t0))
    }

    @Test
    fun `跨时间桶不合并`() {
        val a = SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, t0)
        val b = SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, t0 + SpecDedup.WINDOW_MS)
        assertNotEquals(a, b)
    }

    @Test
    fun `不可信输入不产生身份`() {
        assertNull(SpecDedup.key("", BillType.EXPENSE, 2677, t0))
        assertNull(SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 0, t0))
        assertNull(SpecDedup.key("com.tencent.mm", BillType.EXPENSE, -1, t0))
        assertNull(SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, 0))
    }

    @Test
    fun `只有跨来源才合并`() {
        val key = SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 2677, t0)
        // 通知先到、微信 hook 后到：两个来源都报了同一笔，合并。
        assertTrue(SpecDedup.shouldMerge(key, "PAGE", listOf("NOTIFICATION")))
        // 同一个来源报两次：可能是两笔真实消费，不合并。
        assertFalse(SpecDedup.shouldMerge(key, "NOTIFICATION", listOf("NOTIFICATION")))
        // 三个来源都到过，第四个来源仍可合并到同一条。
        assertTrue(SpecDedup.shouldMerge(key, "SMS", listOf("PAGE", "NOTIFICATION")))
    }

    @Test
    fun `真实订单号不参与这套合成判定`() {
        // 订单号身份是 "transaction:..."，不是 "dup:" 前缀，永远不按金额时间合并。
        assertFalse(SpecDedup.isSynthetic("transaction:abc"))
        assertFalse(SpecDedup.shouldMerge("transaction:abc", "PAGE", listOf("NOTIFICATION")))
        assertTrue(SpecDedup.isSynthetic(SpecDedup.key("com.tencent.mm", BillType.EXPENSE, 1, t0)))
    }
}
