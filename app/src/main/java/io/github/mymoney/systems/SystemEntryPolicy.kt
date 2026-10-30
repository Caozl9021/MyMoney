package io.github.mymoney.systems

import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.CaptureModeState
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

/** Clock and amount rules shared by the desktop widget, with no Android calls. */
object SystemEntryPolicy {
    data class MonthWindow(val month: YearMonth, val from: Long, val to: Long, val rangeLabel: String = "", val startDay: Int = 1, val shortLabel: String = "")
    data class TilePresentation(val active: Boolean, val subtitle: String)

    fun monthWindow(now: Instant, zone: ZoneId, monthStartDay: Int = 1): MonthWindow {
        val period = io.github.mymoney.data.LedgerMonthPeriod.containing(now.atZone(zone).toLocalDate(), monthStartDay, zone)
        return MonthWindow(period.month, period.fromMillis, period.toMillis, period.label(), monthStartDay, period.shortLabel())
    }

    /** Original expense only: refunds remain separate ledger transactions. */
    fun expenseCents(bills: List<BillEntity>, month: MonthWindow): Long = bills
        .filter { it.type == "EXPENSE" && it.timeMillis >= month.from && it.timeMillis < month.to }
        .sumOf { it.priceCents }

    /**
     * 磁贴副标题不再报「Root / 无障碍」：那两个字取自采集预设，而预设退化为界面概念之后，
     * 同一台机器上通知可能走 listener、页面走无障碍、凭证走模块，写一个预设名反而误导。
     */
    fun tile(state: CaptureModeState): TilePresentation = when {
        !state.configured -> TilePresentation(false, "请先开启自动记账")
        state.enabled -> TilePresentation(true, "自动记账已开启")
        else -> TilePresentation(false, "自动记账已暂停")
    }
}
