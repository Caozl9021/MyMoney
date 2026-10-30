package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.data.CaptureModeState
import io.github.caozl9021.mymoney.systems.SystemEntryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

class SystemEntryPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val october = SystemEntryPolicy.monthWindow(Instant.parse("2026-10-22T00:00:00Z"), zone)

    @Test fun `月份依据设备时区并跨年`() {
        val instant = Instant.parse("2026-12-31T16:00:00Z")
        val local = SystemEntryPolicy.monthWindow(instant, zone)
        val utc = SystemEntryPolicy.monthWindow(instant, ZoneId.of("UTC"))
        assertEquals(YearMonth.of(2027, 1), local.month)
        assertEquals(YearMonth.of(2026, 12), utc.month)
        assertEquals(instant.toEpochMilli(), local.from)
    }

    @Test fun `跨夏令时按当地零点计算月底`() {
        val month = SystemEntryPolicy.monthWindow(Instant.parse("2026-03-10T12:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-01T05:00:00Z").toEpochMilli(), month.from)
        assertEquals(Instant.parse("2026-04-01T04:00:00Z").toEpochMilli(), month.to)
    }

    @Test fun `原支出排除退款收入转账并遵循左闭右开`() {
        val rows = listOf(
            bill("EXPENSE", 1999, october.from),
            bill("EXPENSE", 1, october.to - 1),
            bill("EXPENSE", 10000, october.from - 1),
            bill("EXPENSE", 10000, october.to),
            bill("REFUND", 1999, october.from),
            bill("INCOME", 50000, october.from),
            bill("TRANSFER", 50000, october.from),
        )
        assertEquals(2000L, SystemEntryPolicy.expenseCents(rows, october))
        assertEquals(0L, SystemEntryPolicy.expenseCents(emptyList(), october))
    }

    @Test fun `磁贴未选模式即使旧总开关为真也不显示已开启`() {
        val tile = SystemEntryPolicy.tile(CaptureModeState(null, true))
        assertFalse(tile.active)
        assertEquals("请先选择识别模式", tile.subtitle)
    }

    @Test fun `磁贴显示所选模式和总开关而非声称权限可用`() {
        val root = SystemEntryPolicy.tile(CaptureModeState(CaptureMode.ROOT, true))
        assertTrue(root.active)
        assertEquals("Root · 开关已开启", root.subtitle)
        val accessibility = SystemEntryPolicy.tile(CaptureModeState(CaptureMode.ACCESSIBILITY, false))
        assertFalse(accessibility.active)
        assertEquals("无障碍 · 已暂停", accessibility.subtitle)
    }

    private fun bill(type: String, cents: Long, time: Long) = BillEntity(
        priceCents = cents, type = type, timeMillis = time, merchant = null, method = null,
        orderTail = null, sourcePkg = "test", strategy = "manual", fingerprint = "$type-$time-$cents", createdAt = time,
    )
}
