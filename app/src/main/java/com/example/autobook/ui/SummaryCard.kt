package com.example.autobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.autobook.data.AppDb
import com.example.autobook.data.BillQuerySummary
import com.example.autobook.data.ledgerDateWindow
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbPalette
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.AmountText
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.formatAmount
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

private sealed interface SummaryState {
    data object Loading : SummaryState
    data object Failed : SummaryState
    data class Ready(val summary: BillQuerySummary) : SummaryState
}

/** Both summaries use the same unbounded DAO aggregation, independently of the recent list. */
@Composable
private fun rememberSummary(start: LocalDate, end: LocalDate, clock: LedgerClock, retry: Int): SummaryState {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { AppDb.get(context).bills() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = remember(start, end, clock.zone) { ledgerDateWindow(start, end, clock.zone) }
    var state by remember(window, clock.zone, retry) { mutableStateOf<SummaryState>(SummaryState.Loading) }
    LaunchedEffect(dao, lifecycle, window, clock.zone, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dao.observeFilteredSummary("%%", window.fromMillis, window.toMillis, null, null)
                .map<BillQuerySummary, SummaryState> { SummaryState.Ready(it) }
                .catch { emit(SummaryState.Failed) }
                .collect { state = it }
        }
    }
    return state
}

/** 月份标签：当月说「本月」，历史月给完整年月，避免把 9 月写成「本月」。 */
private fun monthLabel(back: Int, target: YearMonth): String =
    if (back == 0) "本月" else "${target.year}年${target.monthValue}月"

/**
 * 首页主视觉：本月支出。
 *
 * 这一张卡就是首页最大的数字，不需要先展开。底色是两档同色相浅珊瑚渐变
 * （深色主题换成两档暗红），文字用深一档的同色相墨色而不是纯黑，
 * 避免整块强色，也避免白字压在浅底上的低对比。
 *
 * 口径：支出是本地自然月 EXPENSE 总额，不扣退款；结余 = 收入 − 原支出 + 退款；
 * 转账不计收支。退款非零才单独出现，不为了「统一四行」硬凑一个 ¥0.00。
 */
@Composable
internal fun MonthlySummaryCard(clock: LedgerClock) {
    val p = LocalAbPalette.current
    var back by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val currentMonth = YearMonth.from(clock.date)
    val target = currentMonth.minusMonths(back.toLong())
    val state = rememberSummary(target.atDay(1), target.atEndOfMonth(), clock, retry)
    val shape = RoundedCornerShape(AbRadius.card)
    val interactive = remember { MutableInteractionSource() }

    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.heroSurfaceTop, p.heroSurfaceBottom)))
            .border(1.dp, p.heroStroke, shape)
            .padding(start = AbSpacing.xl, end = AbSpacing.l, top = AbSpacing.l, bottom = AbSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${monthLabel(back, target)}支出",
                style = AbType.footnote.copy(fontWeight = FontWeight.Medium),
                color = p.heroSecondaryLabel,
                modifier = Modifier.weight(1f),
            )
            if (back > 0) Text(
                "回到本月",
                style = AbType.caption1.copy(fontWeight = FontWeight.Medium),
                color = p.heroAmount,
                modifier = Modifier
                    .clip(RoundedCornerShape(AbRadius.capsule))
                    .clickable(interactionSource = interactive, indication = null) { back = 0 }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = AbSpacing.s, vertical = 10.dp),
            )
            MonthArrow(p.heroAmount, "上个月") { back++ }
            MonthArrow(p.heroAmount, "下个月", enabled = back > 0) { back-- }
        }
        when (state) {
            SummaryState.Loading -> {
                Text("正在读取${monthLabel(back, target)}账单…", style = AbType.subhead, color = p.heroSecondaryLabel)
            }
            SummaryState.Failed -> {
                Text("${monthLabel(back, target)}账单读取失败", style = AbType.subhead, color = p.heroAmount)
                TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重新加载", color = p.heroAmount)
                }
            }
            is SummaryState.Ready -> {
                val summary = state.summary
                // 主数字：整块宽度可用，金额不省略、不缩到读不出（AmountText 自身有下限）。
                AmountText(
                    value = formatAmount(summary.expense),
                    style = AbType.amountHero.copy(fontSize = 42.sp, lineHeight = 48.sp),
                    color = p.heroAmount,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${monthLabel(back, target)}支出 ${formatAmount(summary.expense)}" },
                )
                SummarySecondary(summary, p)
                Text(
                    buildString {
                        append(if (summary.count == 0) "${monthLabel(back, target)}还没有记录" else "${monthLabel(back, target)}已记 ${summary.count} 笔")
                        if (summary.refund != 0L) append(" · 退款不计入支出")
                    },
                    style = AbType.caption1,
                    color = p.heroSecondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun MonthArrow(tint: Color, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    Box(
        Modifier.size(40.dp)
            .clip(RoundedCornerShape(AbRadius.capsule))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (label == "上个月") AbIcons.ChevronLeft else AbIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) tint else p.heroSecondaryLabel.copy(alpha = 0.45f),
        )
    }
}

/** 次级口径：收入与结余默认可读；退款非零才出现；宽屏/窄屏都按可用宽度分列。 */
@Composable
private fun SummarySecondary(summary: BillQuerySummary, p: AbPalette) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cells = buildList {
            add("收入" to formatAmount(summary.income))
            add("结余" to (if (summary.balance < 0) "−" + formatAmount(-summary.balance) else formatAmount(summary.balance)))
            if (summary.refund != 0L) add("退款" to formatAmount(summary.refund))
        }
        val gap = AbSpacing.l
        val minimumCell = 96.dp * fontScale.coerceAtLeast(1f)
        val columns = when {
            maxWidth >= minimumCell * 3 + gap * 2 -> 3
            maxWidth >= minimumCell * 2 + gap -> 2
            else -> 1
        }
        val cellWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.width(cellWidth), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(label, style = AbType.caption1, color = p.heroSecondaryLabel)
                            Text(value, style = AbType.subhead.copy(fontWeight = FontWeight.Medium), color = p.heroAmount)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 今日辅助摘要：一行，不再是首页最大的数字。
 *
 * 区间固定是「今天」，不随上面的月份切换变化 —— 看 9 月的月支出时，
 * 这一行仍然是今天的支出，两者口径不同，不能混。
 */
@Composable
internal fun TodaySummaryCard(clock: LedgerClock) {
    val p = LocalAbPalette.current
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberSummary(clock.date, clock.date, clock, retry)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xs).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Text("今日支出", style = AbType.footnote, color = p.secondaryLabel)
        when (state) {
            SummaryState.Loading -> Text("读取中…", style = AbType.footnote, color = p.secondaryLabel)
            SummaryState.Failed -> TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("今日读取失败，重试", color = p.red, style = AbType.footnote)
            }
            is SummaryState.Ready -> {
                val summary = state.summary
                Text(
                    formatAmount(summary.expense),
                    style = AbType.amountRow.copy(fontWeight = FontWeight.SemiBold),
                    color = if (summary.expense == 0L) p.secondaryLabel else p.expense,
                )
                Text(
                    if (summary.count == 0) "今天还没有记录" else "· ${summary.count} 笔",
                    style = AbType.caption1,
                    color = p.tertiaryLabel,
                    modifier = Modifier.weight(1f),
                )
                if (summary.count != 0) Text(
                    clock.date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日", java.util.Locale.CHINA)),
                    style = AbType.caption1,
                    color = p.tertiaryLabel,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
