package com.example.billkit.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基线（baseline）语义：**启动时就已经躺在通知栏里的通知不算新通知**。
 *
 * 为什么单独钉住这条：`NotificationDumpDedup` 是内存态，进程一重启（装包、被系统回收、
 * 手动强停）就空了，而通知栏里几十小时前的旧通知还在。没有基线的话，每次重启都会把
 * 这些旧通知当新通知重新走一遍规则。
 *
 * 真机实测就是这么多出重复行的：同一份「商品已送达」通知在 app_data 里出现三次
 * （#19 / #20 / #21，payload.t 完全相同），每次都是装包重启那一刻。
 *
 * 复现基线之所以要「先登记、后判定」，是因为规则引擎异步初始化期间
 * `SpecRuleStore.covers()` 还是空集——若先按它过滤，基线轮一条都登记不到，
 * 引擎就绪后旧通知照样被重新投递（这正是 #21 的成因）。
 * 这里用纯逻辑模拟两种顺序，把差别钉死。
 */
class RootNotificationBaselineTest {

    private fun dumped(title: String = "【示例月付】成功支付12.34元", postTime: Long = 1_791_372_831_508L) =
        NotificationDumpParser.Dumped("com.sankuai.meituan", title, "查看示例使用详情>", postTime)

    /** 基线轮：无论规则引擎是否就绪，只要进了白名单就要登记进去重表。 */
    private fun baselineRound(dedup: NotificationDumpDedup, items: List<NotificationDumpParser.Dumped>, now: Long) {
        for (item in items) dedup.accept(item, now)
    }

    @Test
    fun `先登记再判定：引擎没就绪也不会让旧通知变成新通知`() {
        val dedup = NotificationDumpDedup()
        val existing = dumped()
        val now = 1_000_000L
        // 基线轮：引擎还没就绪（covers() 为空），但基线不问引擎，一律登记
        baselineRound(dedup, listOf(existing), now)
        // 引擎就绪后的第一轮：同一条通知已经在表里，不该再投递
        assertFalse(dedup.accept(existing, now + 5_000L))
        assertFalse(dedup.accept(existing, now + 10_000L))
        assertEquals(1, dedup.size())
    }

    @Test
    fun `先判定再登记：引擎没就绪会让基线轮白跑，旧通知被重新投递`() {
        val dedup = NotificationDumpDedup()
        val existing = dumped()
        val now = 1_000_000L
        // 反例：基线轮先被 covers() 过滤掉（引擎没就绪），所以一条也没登记
        val engineReady = false
        if (engineReady) baselineRound(dedup, listOf(existing), now)
        // 引擎就绪后同一条通知被当成新的 —— 这就是真机上多出来的那一行
        assertTrue(dedup.accept(existing, now + 5_000L))
    }

    @Test
    fun `基线之后新出现的通知照常处理`() {
        val dedup = NotificationDumpDedup()
        val now = 1_000_000L
        baselineRound(dedup, listOf(dumped(title = "老通知")), now)
        assertTrue(dedup.accept(dumped(title = "付款成功 12.34 元"), now + 5_000L))
        assertEquals(2, dedup.size())
    }

    @Test
    fun `基线登记的条目同样受十五分钟 TTL 约束`() {
        val dedup = NotificationDumpDedup()
        val item = dumped()
        val now = 1_000_000L
        baselineRound(dedup, listOf(item), now)
        // 十五分钟后仍在通知栏里的同一条通知：视为新的一轮，可以再次处理。
        // 这是有意为之——超时的通知栏内容不保证还是同一笔，宁可提交待核对。
        assertTrue(dedup.accept(item, now + 15 * 60 * 1000L))
    }
}
