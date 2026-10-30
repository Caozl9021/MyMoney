package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.export.ExportRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 导出区间边界（左闭右开）。
 *
 * 这几条最容易写错：上月写成两个月、跨年、以及"最近 3 个月"到底含不含当月。
 */
class ExportRangeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    private fun from(span: ExportRange.Span) =
        java.time.Instant.ofEpochMilli(span.from).atZone(zone).toLocalDate()

    private fun to(span: ExportRange.Span) =
        java.time.Instant.ofEpochMilli(span.to - 1).atZone(zone).toLocalDate()

    @Test
    fun `本月是当月的 1 号到下月 1 号`() {
        val span = ExportRange.span(ExportRange.Kind.THIS_MONTH, day(2026, 10, 21), zone)
        assertEquals(day(2026, 10, 1), from(span))
        assertEquals(day(2026, 10, 31), to(span))
    }

    @Test
    fun `上月不是两个月`() {
        val span = ExportRange.span(ExportRange.Kind.LAST_MONTH, day(2026, 10, 21), zone)
        assertEquals(day(2026, 9, 1), from(span))
        assertEquals(day(2026, 9, 30), to(span))
    }

    @Test
    fun `一月的上月是去年十二月`() {
        val span = ExportRange.span(ExportRange.Kind.LAST_MONTH, day(2026, 1, 5), zone)
        assertEquals(day(2025, 12, 1), from(span))
        assertEquals(day(2025, 12, 31), to(span))
    }

    @Test
    fun `最近 3 个月含本月而不是只算过去 3 个月`() {
        val span = ExportRange.span(ExportRange.Kind.LAST_3_MONTHS, day(2026, 10, 21), zone)
        assertEquals(day(2026, 8, 1), from(span))
        assertEquals(day(2026, 10, 31), to(span))
    }

    @Test
    fun `二月边界与闰年`() {
        val span = ExportRange.span(ExportRange.Kind.THIS_MONTH, day(2024, 2, 10), zone)
        assertEquals(day(2024, 2, 1), from(span))
        assertEquals(day(2024, 2, 29), to(span))
    }

    @Test
    fun `全部是全通过区间而不是抛异常`() {
        val span = ExportRange.span(ExportRange.Kind.ALL, day(2026, 10, 21), zone)
        assertEquals(0L, span.from)
        assertTrue(span.to > span.from)
    }

    @Test
    fun `描述文本不含时间只含日期`() {
        val text = ExportRange.describe(ExportRange.Kind.LAST_MONTH, day(2026, 10, 21), zone)
        assertEquals("2026-09-01 – 2026-09-30", text)
        assertEquals("全部记录", ExportRange.describe(ExportRange.Kind.ALL, day(2026, 10, 21), zone))
    }
}
