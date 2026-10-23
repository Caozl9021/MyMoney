package com.example.autobook.export

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 导出范围的计算。
 *
 * 单独抽出来是为了能测：`[from, to)` 的边界、跨年、以及"当月还没过完"这几种情况
 * 全是最容易写错一位的地方（比如把上月算成两个月）。
 */
object ExportRange {

    enum class Kind(val label: String) {
        THIS_MONTH("本月"),
        LAST_MONTH("上月"),
        LAST_3_MONTHS("最近 3 个月"),
        ALL("全部"),
    }

    /** 毫秒区间，左闭右开；[ALL] 返回 0 到 `Long.MAX_VALUE` 让 SQL 全通过 */
    data class Span(val from: Long, val to: Long)

    fun span(kind: Kind, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Span {
        val thisMonth = YearMonth.from(today)
        return when (kind) {
            Kind.THIS_MONTH -> Span(startOf(thisMonth, zone), startOf(thisMonth.plusMonths(1), zone))
            Kind.LAST_MONTH -> {
                val last = thisMonth.minusMonths(1)
                Span(startOf(last, zone), startOf(thisMonth, zone))
            }
            // 含本月，所以是 3 个月而不是 3 个"过去月份"
            Kind.LAST_3_MONTHS -> Span(startOf(thisMonth.minusMonths(2), zone), startOf(thisMonth.plusMonths(1), zone))
            Kind.ALL -> Span(0L, Long.MAX_VALUE)
        }
    }

    private fun startOf(month: YearMonth, zone: ZoneId): Long =
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** 给 UI 的一行说明，例如「2026年9月1日 – 9月30日」；[ALL] 返回「全部记录」 */
    fun describe(kind: Kind, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        if (kind == Kind.ALL) return "全部记录"
        val span = span(kind, today, zone)
        val from = Instant.ofEpochMilli(span.from).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(span.to - 1).atZone(zone).toLocalDate()
        return if (from == to) "${from}（一天）" else "$from – $to"
    }
}
