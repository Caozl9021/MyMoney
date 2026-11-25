package io.github.mymoney.systems

import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AssetEntry
import io.github.mymoney.data.AssetMath
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.Reimburse
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Layout buckets mapped by aspect ratio + dp area so desktop grid differences stay resilient. */
enum class WidgetSizeBucket {
    MINI_ICON,        // ~1x1
    STRIP_VERTICAL,   // ~1x2
    STRIP_HORIZONTAL, // ~2x1
    SQUARE_COMPACT,   // ~2x2
    TALL_CARD,        // ~2x3
    WIDE_MEDIUM,      // ~3x2
    WIDE_FULL,        // ~4x2+
}

enum class AssetWidgetMode(val id: String, val title: String, val subtitle: String) {
    MIXED("mixed", "混合看板", "净资产 + 待报销一目了然"),
    ASSET_ONLY("asset", "纯资产", "净资产与总资产 / 总负债拆解"),
    REIMBURSE_ONLY("reimburse", "报销与账期", "待报销总额笔数 + 本期原支出");

    companion object {
        fun fromId(id: String?): AssetWidgetMode = entries.firstOrNull { it.id == id } ?: MIXED
    }
}

enum class WidgetSurfaceStyle(val id: String, val title: String) {
    GLASS_DARK("glass_dark", "暗透玻璃"),
    GLASS_LIGHT("glass_light", "浅透玻璃"),
    SOLID("solid", "标准实底");

    companion object {
        fun fromId(id: String?): WidgetSurfaceStyle? = when (id) {
            "glass_dark", "frosted" -> GLASS_DARK
            "glass_light" -> GLASS_LIGHT
            "solid" -> SOLID
            else -> null
        }
    }
}

data class SevenDayBar(
    val date: LocalDate,
    val label: String,
    val expenseCents: Long,
    val isToday: Boolean,
)

data class SevenDaySummary(
    val bars: List<SevenDayBar>,
    val totalCents: Long,
    val averageCents: Long,
    val todayCents: Long,
    val maxCents: Long,
)

data class AssetWidgetSnapshot(
    val netCents: Long,
    val assetsCents: Long,
    val debtsCents: Long,
    val hasInitializedAccounts: Boolean,
    val pendingCount: Int,
    val pendingCents: Long,
    val monthExpenseCents: Long,
    val monthLabel: String,
)

/** Pure JVM rules shared by all desktop widgets and unit tests, with no Android dependencies. */
object WidgetPolicy {

    fun classifySizeBucket(widthDp: Float, heightDp: Float): WidgetSizeBucket {
        val w = widthDp.coerceAtLeast(1f)
        val h = heightDp.coerceAtLeast(1f)
        return when {
            w < 110f && h < 110f -> WidgetSizeBucket.MINI_ICON
            w < 110f -> WidgetSizeBucket.STRIP_VERTICAL
            h < 105f && w >= 110f -> WidgetSizeBucket.STRIP_HORIZONTAL
            w < 205f && h >= 200f -> WidgetSizeBucket.TALL_CARD
            w < 205f -> WidgetSizeBucket.SQUARE_COMPACT
            w < 275f -> WidgetSizeBucket.WIDE_MEDIUM
            else -> WidgetSizeBucket.WIDE_FULL
        }
    }

    /** Constrain a raw bucket to the set supported by a specific widget provider. */
    fun constrainBucket(raw: WidgetSizeBucket, supported: Set<WidgetSizeBucket>, fallback: WidgetSizeBucket): WidgetSizeBucket {
        if (raw in supported) return raw
        return when (raw) {
            WidgetSizeBucket.MINI_ICON -> listOf(
                WidgetSizeBucket.STRIP_HORIZONTAL, WidgetSizeBucket.STRIP_VERTICAL, WidgetSizeBucket.SQUARE_COMPACT,
            ).firstOrNull { it in supported } ?: fallback
            WidgetSizeBucket.STRIP_VERTICAL -> listOf(
                WidgetSizeBucket.SQUARE_COMPACT, WidgetSizeBucket.MINI_ICON, WidgetSizeBucket.TALL_CARD, WidgetSizeBucket.STRIP_HORIZONTAL,
            ).firstOrNull { it in supported } ?: fallback
            WidgetSizeBucket.STRIP_HORIZONTAL -> listOf(
                WidgetSizeBucket.SQUARE_COMPACT, WidgetSizeBucket.MINI_ICON, WidgetSizeBucket.WIDE_MEDIUM, WidgetSizeBucket.WIDE_FULL,
            ).firstOrNull { it in supported } ?: fallback
            WidgetSizeBucket.TALL_CARD -> listOf(
                WidgetSizeBucket.SQUARE_COMPACT, WidgetSizeBucket.WIDE_MEDIUM, WidgetSizeBucket.STRIP_VERTICAL,
            ).firstOrNull { it in supported } ?: fallback
            WidgetSizeBucket.WIDE_MEDIUM -> listOf(
                WidgetSizeBucket.WIDE_FULL, WidgetSizeBucket.SQUARE_COMPACT,
            ).firstOrNull { it in supported } ?: fallback
            WidgetSizeBucket.WIDE_FULL -> listOf(
                WidgetSizeBucket.WIDE_MEDIUM, WidgetSizeBucket.SQUARE_COMPACT,
            ).firstOrNull { it in supported } ?: fallback
            WidgetSizeBucket.SQUARE_COMPACT -> supported.firstOrNull() ?: fallback
        }
    }

    /** Inclusive 7-day local calendar window: [today - 6 days, today + 1 day). */
    fun sevenDayWindow(now: Instant, zone: ZoneId): Pair<Long, Long> {
        val today = now.atZone(zone).toLocalDate()
        val start = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to endExclusive
    }

    fun sevenDaySummary(bills: List<BillEntity>, now: Instant, zone: ZoneId): SevenDaySummary {
        val today = now.atZone(zone).toLocalDate()
        val firstDay = today.minusDays(6)
        val totalsByDay = LongArray(7)
        for (bill in bills) {
            if (!Reimburse.countsInExpense(bill)) continue
            val day = Instant.ofEpochMilli(bill.timeMillis).atZone(zone).toLocalDate()
            if (day < firstDay || day > today) continue
            val index = (day.toEpochDay() - firstDay.toEpochDay()).toInt()
            if (index in 0..6) totalsByDay[index] += bill.priceCents
        }
        val bars = (0..6).map { offset ->
            val date = firstDay.plusDays(offset.toLong())
            val isToday = offset == 6
            val label = if (isToday) "今" else shortWeekdayLabel(date.dayOfWeek)
            SevenDayBar(
                date = date,
                label = label,
                expenseCents = totalsByDay[offset],
                isToday = isToday,
            )
        }
        val total = totalsByDay.sum()
        val average = (total + 3L) / 7L
        val max = totalsByDay.maxOrNull() ?: 0L
        return SevenDaySummary(
            bars = bars,
            totalCents = total,
            averageCents = average,
            todayCents = totalsByDay[6],
            maxCents = max,
        )
    }

    fun buildAssetSnapshot(
        accounts: List<AssetAccount>,
        entries: List<AssetEntry>,
        pendingCount: Int,
        pendingCents: Long,
        monthExpenseCents: Long,
        monthLabel: String,
    ): AssetWidgetSnapshot {
        val summary = AssetMath.summary(accounts, entries)
        val hasInitialized = accounts.any { it.included && it.initialized }
        return AssetWidgetSnapshot(
            netCents = summary.net,
            assetsCents = summary.assets,
            debtsCents = summary.debts,
            hasInitializedAccounts = hasInitialized,
            pendingCount = pendingCount.coerceAtLeast(0),
            pendingCents = pendingCents.coerceAtLeast(0L),
            monthExpenseCents = monthExpenseCents.coerceAtLeast(0L),
            monthLabel = monthLabel,
        )
    }

    fun formatCurrency(cents: Long, masked: Boolean = false, signed: Boolean = false): String {
        if (masked) return "¥****"
        val neg = cents < 0
        val abs = kotlin.math.abs(cents)
        val formatted = String.format(Locale.US, "%,d.%02d", abs / 100, abs % 100)
        return when {
            neg -> "-¥$formatted"
            signed && cents > 0 -> "+¥$formatted"
            else -> "¥$formatted"
        }
    }

    fun formatCompactAmount(cents: Long): String {
        if (cents <= 0L) return "0"
        val yuan = cents / 100.0
        return when {
            cents >= 100_000_000L -> String.format(Locale.US, "%.1fw", yuan / 10_000.0)
            cents >= 1_000_000L -> String.format(Locale.US, "%.1fk", yuan / 1_000.0)
            cents % 100L == 0L -> (cents / 100L).toString()
            else -> String.format(Locale.US, "%.1f", yuan)
        }
    }

    private fun shortWeekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "一"
        DayOfWeek.TUESDAY -> "二"
        DayOfWeek.WEDNESDAY -> "三"
        DayOfWeek.THURSDAY -> "四"
        DayOfWeek.FRIDAY -> "五"
        DayOfWeek.SATURDAY -> "六"
        DayOfWeek.SUNDAY -> "日"
    }
}
