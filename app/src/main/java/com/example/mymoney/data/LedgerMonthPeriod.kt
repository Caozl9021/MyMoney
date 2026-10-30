package com.example.mymoney.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The month key is never derived from a clamped previous boundary. */
data class LedgerMonthPeriod(val month: YearMonth, val day: Int, val zone: ZoneId = ZoneId.systemDefault()) {
    init { require(day in 1..31) { "每月起始日须为1–31日" } }
    private fun boundary(key: YearMonth): LocalDate = key.atDay(minOf(day, key.lengthOfMonth()))
    val startDate: LocalDate get() = boundary(month)
    val endExclusiveDate: LocalDate get() = boundary(month.plusMonths(1))
    val endDate: LocalDate get() = endExclusiveDate.minusDays(1)
    val fromMillis: Long get() = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val toMillis: Long get() = endExclusiveDate.atStartOfDay(zone).toInstant().toEpochMilli()
    fun shift(amount: Long) = copy(month = month.plusMonths(amount))
    fun label(): String = "${startDate} – ${endDate}"
    fun shortLabel(): String = "${startDate.monthValue}/${startDate.dayOfMonth}–${endDate.monthValue}/${endDate.dayOfMonth}"
    companion object {
        fun containing(date: LocalDate, day: Int = 1, zone: ZoneId = ZoneId.systemDefault()): LedgerMonthPeriod {
            val candidate = LedgerMonthPeriod(YearMonth.from(date), day, zone)
            return if (date < candidate.startDate) candidate.shift(-1) else candidate
        }
        /** Missing backup fields preserve local configuration; explicit invalid fields are rejected. */
        fun restoredDay(current: Int, incoming: Int?): Int {
            require(current in 1..31 && (incoming == null || incoming in 1..31)) { "每月起始日须为1–31日" }
            return incoming ?: current
        }
    }
}
