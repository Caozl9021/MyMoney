package com.example.autobook.systems

import com.example.autobook.data.BillEntity
import com.example.autobook.data.CaptureMode
import com.example.autobook.data.CaptureModeState
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

/** Clock and amount rules shared by the desktop widget, with no Android calls. */
object SystemEntryPolicy {
    data class MonthWindow(val month: YearMonth, val from: Long, val to: Long)
    data class TilePresentation(val active: Boolean, val subtitle: String)

    fun monthWindow(now: Instant, zone: ZoneId): MonthWindow {
        val month = YearMonth.from(now.atZone(zone))
        return MonthWindow(
            month,
            month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
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
