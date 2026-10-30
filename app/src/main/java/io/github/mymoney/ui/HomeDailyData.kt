package io.github.mymoney.ui

import io.github.mymoney.data.BillEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

internal const val HOME_DAILY_PAGE_SIZE = 50

internal data class HomeDailyDay(
    val date: LocalDate,
    val bills: List<BillEntity>,
    /** Original expenses only: refunds, income and transfers remain visible as rows. */
    val expenseCents: Long,
)

internal data class HomeDailySnapshot(
    val days: List<HomeDailyDay>,
    val loadedCount: Int,
    val hasMore: Boolean,
)

/** A loaded day never disappears just because new transactions push the anchor window forward. */
internal class HomeDailyBoundary {
    var earliestFrom: Long? = null
        private set

    fun include(anchor: List<BillEntity>, zone: ZoneId): Long? {
        val oldest = anchor.minOfOrNull { it.timeMillis } ?: return earliestFrom
        val midnight = Instant.ofEpochMilli(oldest).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        earliestFrom = earliestFrom?.let { minOf(it, midnight) } ?: midnight
        return earliestFrom
    }
}

internal fun homeDailyGroups(rows: List<BillEntity>, zone: ZoneId): List<HomeDailyDay> =
    rows.sortedWith(compareByDescending<BillEntity> { it.timeMillis }.thenByDescending { it.id })
        .groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }
        .map { (date, bills) ->
            HomeDailyDay(date, bills, bills.filter { it.type == "EXPENSE" }.sumOf { it.priceCents })
        }.sortedByDescending { it.date }

/** Complete-day expansion may have loaded thousands of rows beyond the original anchor limit. */
internal fun nextHomeDailyAnchorLimit(currentLimit: Int, loadedCount: Int): Int =
    (maxOf(currentLimit, loadedCount).toLong() + HOME_DAILY_PAGE_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * The anchor chooses a calendar boundary; it is never used for display or daily totals.
 * The outer query includes that whole day, without a row limit or an artificial 24-hour duration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun homeDailySnapshots(
    anchor: Flow<List<BillEntity>>,
    totalCount: Flow<Int>,
    zone: ZoneId,
    boundary: HomeDailyBoundary,
    observeFrom: (Long) -> Flow<List<BillEntity>>,
    fromInclusive: Long = Long.MIN_VALUE,
    toExclusive: Long = Long.MAX_VALUE,
): Flow<HomeDailySnapshot> = anchor.flatMapLatest { rows ->
    require(fromInclusive < toExclusive)
    val from = boundary.include(rows.filter { it.timeMillis >= fromInclusive && it.timeMillis < toExclusive }, zone)
        ?.coerceAtLeast(fromInclusive)
    if (from == null || from >= toExclusive) flowOf(HomeDailySnapshot(emptyList(), 0, false))
    else combine(observeFrom(from), totalCount) { completeRows, total ->
        withContext(Dispatchers.Default) {
            val bounded = completeRows.filter { it.timeMillis >= from && it.timeMillis < toExclusive }
            HomeDailySnapshot(homeDailyGroups(bounded, zone), bounded.size, total > bounded.size)
        }
    }
}
