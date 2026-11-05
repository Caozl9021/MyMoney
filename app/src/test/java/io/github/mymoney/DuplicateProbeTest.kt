package io.github.mymoney

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.data.AccountNormalizer
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.DuplicateProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E 阶段「记账前查账本」纯逻辑回归（不依赖 Room）：
 * - [AccountNormalizer] 账户模糊匹配（工行 ↔ 工商银行储蓄卡(5821)）；
 * - [DuplicateProbe.evaluate] 时间容差 ±120000ms 边界、同额两笔超容差不误并（F6）。
 */
class DuplicateProbeTest {

    private fun bill(
        id: Long, cents: Long, time: Long, account: String?, type: BillType = BillType.EXPENSE,
        merchant: String? = "美团",
    ) = BillEntity(
        id = id, priceCents = cents, type = type.name, timeMillis = time, merchant = merchant,
        method = null, orderTail = null, sourcePkg = "com.tencent.mm", strategy = "wechat",
        fingerprint = "fp-$id", createdAt = time, account = account,
    )

    private fun expense(cents: Long, time: Long, account: String?) = BillDraft(
        priceCents = cents, type = BillType.EXPENSE, timeMillis = time, merchant = "美团",
        method = null, orderTail = null, sourcePkg = "com.tencent.mm", strategy = "wechat",
        account = account, hasExplicitTime = true,
    )

    // ---- AccountNormalizer ----

    @Test fun `工行与工商银行储蓄卡同一账户`() {
        assertTrue(AccountNormalizer.sameAccount("工商银行储蓄卡(5821)", "工行"))
        assertTrue(AccountNormalizer.sameAccount("工行", "工商银行储蓄卡(5821)"))
    }

    @Test fun `尾号不同的同银行是不同账户`() {
        assertFalse(AccountNormalizer.sameAccount("工商银行储蓄卡(5821)", "工商银行储蓄卡(9999)"))
    }

    @Test fun `不同银行不同账户`() {
        assertFalse(AccountNormalizer.sameAccount("工商银行储蓄卡(5821)", "建设银行储蓄卡(5821)"))
    }

    @Test fun `零钱与工商银行不同账户`() {
        assertFalse(AccountNormalizer.sameAccount("零钱", "工商银行储蓄卡(5821)"))
    }

    @Test fun `任一方账户为空时不因缺信息误判为不同`() {
        // 账户信息不足时保守返回 true（宁可提示让用户判断，也不因缺账户漏标）
        assertTrue(AccountNormalizer.sameAccount(null, "工商银行储蓄卡(5821)"))
        assertTrue(AccountNormalizer.sameAccount("", "工行"))
        assertTrue(AccountNormalizer.sameAccount(null, null))
    }

    // ---- DuplicateProbe 时间容差边界 ----

    @Test fun `时间差119999毫秒命中疑似重复`() {
        val t = 1_700_000_000_000L
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 1999, t, "零钱")), expense(1999, t + 119_999, "零钱"))
        assertEquals(1, hits.size)
    }

    @Test fun `时间差120000毫秒命中疑似重复`() {
        val t = 1_700_000_000_000L
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 1999, t, "零钱")), expense(1999, t + 120_000, "零钱"))
        assertEquals(1, hits.size)
    }

    @Test fun `时间差120001毫秒不命中`() {
        val t = 1_700_000_000_000L
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 1999, t, "零钱")), expense(1999, t + 120_001, "零钱"))
        assertTrue(hits.isEmpty())
    }

    // ---- F6：同额两笔超容差不误并 ----

    @Test fun `同额两笔时间超容差不误并`() {
        val t = 1_700_000_000_000L
        // 两瓶水各 3 元，间隔 5 分钟（远超 ±2 分钟）→ 新采集那笔不应被标成重复
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 300, t, "零钱")), expense(300, t + 300_000, "零钱"))
        assertTrue(hits.isEmpty())
    }

    @Test fun `同额同时间同账户才提示疑似重复`() {
        val t = 1_700_000_000_000L
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 300, t, "工商银行储蓄卡(5821)")),
            expense(300, t + 30_000, "工行"))
        assertEquals(1, hits.size)
        assertEquals(1L, hits.single().billId)
    }

    @Test fun `金额不同不命中`() {
        val t = 1_700_000_000_000L
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 1999, t, "零钱")), expense(2000, t, "零钱"))
        assertTrue(hits.isEmpty())
    }

    @Test fun `类型不同不命中`() {
        val t = 1_700_000_000_000L
        val income = expense(1999, t, "零钱").copy(type = BillType.INCOME)
        val hits = DuplicateProbe.evaluate(listOf(bill(1, 1999, t, "零钱")), income)
        assertTrue(hits.isEmpty())
    }

    @Test fun `多条命中按时间距离由近到远`() {
        val t = 1_700_000_000_000L
        val candidates = listOf(bill(1, 1999, t - 100_000, "零钱"), bill(2, 1999, t + 10_000, "零钱"))
        val hits = DuplicateProbe.evaluate(candidates, expense(1999, t, "零钱"))
        assertEquals(listOf(2L, 1L), hits.map { it.billId })
    }
}
