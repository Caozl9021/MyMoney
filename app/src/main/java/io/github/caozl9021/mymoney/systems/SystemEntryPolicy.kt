package io.github.caozl9021.mymoney.systems

import io.github.caozl9021.mymoney.data.BillEntity
import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.data.CaptureModeState
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

/** Clock and amount rules shared by the desktop widget, with no Android calls. */
object SystemEntryPolicy {
    data class MonthWindow(val month: YearMonth, val from: Long, val to: Long, val rangeLabel: String = "", val startDay: Int = 1, val shortLabel: String = "")
    data class TilePresentation(val active: Boolean, val subtitle: String)

    fun monthWindow(now: Instant, zone: ZoneId, monthStartDay: Int = 1): MonthWindow {
        val period = io.github.caozl9021.mymoney.data.LedgerMonthPeriod.containing(now.atZone(zone).toLocalDate(), monthStartDay, zone)
        return MonthWindow(period.month, period.fromMillis, period.toMillis, period.label(), monthStartDay, period.shortLabel())
    }

    /** Original expense only: refunds remain separate ledger transactions. */
    fun expenseCents(bills: List<BillEntity>, month: MonthWindow): Long = bills
        .filter { it.type == "EXPENSE" && it.timeMillis >= month.from && it.timeMillis < month.to }
        .sumOf { it.priceCents }

    fun tile(state: CaptureModeState): TilePresentation {
        val mode = when (state.mode) {
            CaptureMode.ROOT -> "Root"
            CaptureMode.ACCESSIBILITY -> "无障碍"
            null -> return TilePresentation(false, "请先选择识别模式")
        }
        return TilePresentation(state.enabled, "$mode · ${if (state.enabled) "开关已开启" else "已暂停"}")
    }
}
