package io.github.mymoney.systems

import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AssetEntry
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.Reimburse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WidgetPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `六种尺寸分桶与受限组件降级映射正确`() {
        assertEquals(WidgetSizeBucket.MINI_ICON, WidgetPolicy.classifySizeBucket(68f, 70f))
        assertEquals(WidgetSizeBucket.STRIP_VERTICAL, WidgetPolicy.classifySizeBucket(68f, 150f))
        assertEquals(WidgetSizeBucket.STRIP_HORIZONTAL, WidgetPolicy.classifySizeBucket(150f, 70f))
        assertEquals(WidgetSizeBucket.SQUARE_COMPACT, WidgetPolicy.classifySizeBucket(156f, 156f))
        assertEquals(WidgetSizeBucket.TALL_CARD, WidgetPolicy.classifySizeBucket(156f, 230f))
        assertEquals(WidgetSizeBucket.WIDE_MEDIUM, WidgetPolicy.classifySizeBucket(240f, 156f))
        assertEquals(WidgetSizeBucket.WIDE_FULL, WidgetPolicy.classifySizeBucket(320f, 156f))
        assertEquals(WidgetSurfaceStyle.GLASS_DARK, WidgetSurfaceStyle.fromId("frosted"))
        assertEquals(WidgetSurfaceStyle.GLASS_LIGHT, WidgetSurfaceStyle.fromId("glass_light"))
        assertEquals(WidgetSurfaceStyle.SOLID, WidgetSurfaceStyle.fromId("solid"))

        val sevenDaySupported = setOf(
            WidgetSizeBucket.STRIP_HORIZONTAL,
            WidgetSizeBucket.SQUARE_COMPACT,
            WidgetSizeBucket.TALL_CARD,
            WidgetSizeBucket.WIDE_MEDIUM,
            WidgetSizeBucket.WIDE_FULL,
        )
        assertEquals(
            WidgetSizeBucket.STRIP_HORIZONTAL,
            WidgetPolicy.constrainBucket(WidgetSizeBucket.MINI_ICON, sevenDaySupported, WidgetSizeBucket.WIDE_MEDIUM),
        )
        assertEquals(
            WidgetSizeBucket.SQUARE_COMPACT,
            WidgetPolicy.constrainBucket(WidgetSizeBucket.STRIP_VERTICAL, sevenDaySupported, WidgetSizeBucket.WIDE_MEDIUM),
        )
    }

    @Test
    fun `七日窗口与原支出汇总排除退款转账和已报销且跨天准确`() {
        val now = LocalDate.of(2026, 11, 25).atTime(15, 0).atZone(zone).toInstant()
        val (from, to) = WidgetPolicy.sevenDayWindow(now, zone)
        assertEquals(LocalDate.of(2026, 11, 19).atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(LocalDate.of(2026, 11, 26).atStartOfDay(zone).toInstant().toEpochMilli(), to)

        val day19 = LocalDate.of(2026, 11, 19).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val day22 = LocalDate.of(2026, 11, 22).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val day25 = LocalDate.of(2026, 11, 25).atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        val outsideOld = LocalDate.of(2026, 11, 18).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()

        val rows = listOf(
            bill("EXPENSE", 1400, day19),
            bill("EXPENSE", 3500, day22, reimburse = Reimburse.PENDING),
            bill("EXPENSE", 2100, day25),
            bill("EXPENSE", 9900, day25, reimburse = Reimburse.REIMBURSED),
            bill("REFUND", 500, day25),
            bill("INCOME", 8000, day25),
            bill("TRANSFER", 12000, day25),
            bill("EXPENSE", 5000, outsideOld),
        )

        val summary = WidgetPolicy.sevenDaySummary(rows, now, zone)
        assertEquals(7, summary.bars.size)
        assertEquals(7000L, summary.totalCents)
        assertEquals(1000L, summary.averageCents)
        assertEquals(2100L, summary.todayCents)
        assertEquals(3500L, summary.maxCents)
        assertEquals("今", summary.bars.last().label)
        assertTrue(summary.bars.last().isToday)
        assertFalse(summary.bars.first().isToday)
        assertEquals(1400L, summary.bars[0].expenseCents)
        assertEquals(3500L, summary.bars[3].expenseCents)
        assertEquals(2100L, summary.bars[6].expenseCents)
    }

    @Test
    fun `资产与待报销看板快照统计与脱敏格式化正确`() {
        val now = 1_700_000_000_000L
        val accounts = listOf(
            AssetAccount(id = "a1", name = "招行", kind = "SAVINGS", included = true, initialized = true, createdAt = now, updatedAt = now),
            AssetAccount(id = "a2", name = "花呗", kind = "CREDIT", included = true, initialized = true, createdAt = now, updatedAt = now),
            AssetAccount(id = "a3", name = "不计入", kind = "SAVINGS", included = false, initialized = true, createdAt = now, updatedAt = now),
        )
        val entries = listOf(
            AssetEntry(id = "e1", operationId = "o1", accountId = "a1", deltaCents = 500_000L, kind = "OPEN", note = "", createdAt = now),
            AssetEntry(id = "e2", operationId = "o2", accountId = "a2", deltaCents = -120_000L, kind = "OPEN", note = "", createdAt = now),
            AssetEntry(id = "e3", operationId = "o3", accountId = "a3", deltaCents = 999_000L, kind = "OPEN", note = "", createdAt = now),
        )
        val snap = WidgetPolicy.buildAssetSnapshot(
            accounts = accounts,
            entries = entries,
            pendingCount = 2,
            pendingCents = 64050L,
            monthExpenseCents = 312000L,
            monthLabel = "本月原支出",
        )
        assertTrue(snap.hasInitializedAccounts)
        assertEquals(500_000L, snap.assetsCents)
        assertEquals(120_000L, snap.debtsCents)
        assertEquals(380_000L, snap.netCents)
        assertEquals(2, snap.pendingCount)
        assertEquals(64050L, snap.pendingCents)

        val uninit = WidgetPolicy.buildAssetSnapshot(
            accounts = listOf(accounts[0].copy(initialized = false)),
            entries = emptyList(),
            pendingCount = 0,
            pendingCents = 0L,
            monthExpenseCents = 0L,
            monthLabel = "本月原支出",
        )
        assertFalse(uninit.hasInitializedAccounts)

        assertEquals("¥3,800.00", WidgetPolicy.formatCurrency(380_000L, masked = false))
        assertEquals("-¥12.50", WidgetPolicy.formatCurrency(-1250L, masked = false))
        assertEquals("¥****", WidgetPolicy.formatCurrency(380_000L, masked = true))
        assertEquals("128", WidgetPolicy.formatCompactAmount(12800L))
        assertEquals("45.5", WidgetPolicy.formatCompactAmount(4550L))
        assertEquals("12.3k", WidgetPolicy.formatCompactAmount(1_234_000L))
    }

    private fun bill(
        type: String,
        cents: Long,
        time: Long,
        reimburse: String = Reimburse.NONE,
    ) = BillEntity(
        priceCents = cents,
        type = type,
        timeMillis = time,
        merchant = null,
        method = null,
        orderTail = null,
        sourcePkg = "test",
        strategy = "manual",
        fingerprint = "$type-$time-$cents-$reimburse",
        createdAt = time,
        reimburseStatus = reimburse,
    )
}
