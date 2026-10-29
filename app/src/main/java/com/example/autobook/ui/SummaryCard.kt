package com.example.autobook.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.autobook.data.AppDb
import com.example.autobook.data.BillQuerySummary
import com.example.autobook.data.ledgerDateWindow
import com.example.autobook.ui.design.AbCardDensity
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

/** The period summary uses unbounded DAO aggregation, independently of the paged daily list. */
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
 * 中性紧凑卡保留账期汇总；自然日小计由下方日卡呈现。
 *
 * 口径：支出是所选本地账期 EXPENSE 总额，不扣退款；结余 = 收入 − 原支出 + 退款；
 * 转账不计收支。退款非零才单独出现，不为了「统一四行」硬凑一个 ¥0.00。
 */
@Composable
internal fun MonthlySummaryCard(
    clock: LedgerClock,
    period: com.example.autobook.data.LedgerMonthPeriod,
    back: Int,
    onPeriodBackChange: (Int) -> Unit,
) {
    val p = LocalAbPalette.current
    val monthStartDay = period.day
    var retry by remember { mutableIntStateOf(0) }
    val target = period.month
    val monthName = if (monthStartDay == 1) monthLabel(back, target) else when (back) { 0 -> "本期"; 1 -> "上期"; else -> "历史期" }
    val state = rememberSummary(period.startDate, period.endDate, clock, retry)
    val shape = RoundedCornerShape(AbRadius.group)

    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(p.cardBg)
            .padding(AbCardDensity.content),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(AbRadius.row))
                .clickable(enabled = back > 0, onClickLabel = "回到本期") { onPeriodBackChange(0) }
                .heightIn(min = 48.dp).semantics(mergeDescendants = true) {
                    if (back > 0) contentDescription = "${period.label()}，点击回到本期"
                }, verticalArrangement = Arrangement.Center) {
                Text("${monthName}支出", style = AbType.footnote.copy(fontWeight = FontWeight.Medium), color = if (back > 0) p.accent else p.label)
                if (monthStartDay != 1) Text(period.label(), style = AbType.caption1, color = p.secondaryLabel)
            }
            MonthArrow(p.label, if (monthStartDay == 1) "上个月" else "上一期", previous = true) { onPeriodBackChange(back + 1) }
            MonthArrow(p.label, if (monthStartDay == 1) "下个月" else "下一期", previous = false, enabled = back > 0) { onPeriodBackChange(back - 1) }
        }
        when (state) {
            SummaryState.Loading -> {
                Text("正在读取${monthName}账单…", style = AbType.subhead, color = p.secondaryLabel)
            }
            SummaryState.Failed -> {
                Text("${monthName}账单读取失败", style = AbType.subhead, color = p.label)
                TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重新加载", color = p.label)
                }
            }
            is SummaryState.Ready -> {
                val summary = state.summary
                // 主数字：整块宽度可用，金额不省略、不缩到读不出（AmountText 自身有下限）。
                SummaryHeroAmount(formatAmount(summary.expense), "${monthName}支出", p)
                SummarySecondary(summary, p)
            }
        }
    }
}

@Composable
private fun MonthArrow(tint: Color, label: String, previous: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    Box(
        Modifier.size(48.dp)
            .clip(RoundedCornerShape(AbRadius.capsule))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (previous) AbIcons.ChevronLeft else AbIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) tint else p.secondaryLabel.copy(alpha = 0.45f),
        )
    }
}

/** Keep the existing width fitting contract, with a smaller currency symbol on the same baseline. */
@Composable
private fun SummaryHeroAmount(value: String, label: String, p: AbPalette) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = "$label $value" }) {
        val fitted = minOf(36f, maxWidth.value / (value.length.coerceAtLeast(1) * 0.66f * fontScale)).coerceAtLeast(10f)
        val text = androidx.compose.ui.text.buildAnnotatedString {
            value.forEachIndexed { index, character ->
                if (index == 0 && character == '¥') {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = (fitted * 0.62f).sp))
                    append(character); pop()
                } else append(character)
            }
        }
        Text(text, style = AbType.amountHero.copy(fontSize = fitted.sp, lineHeight = 42.sp),
            color = p.expense, maxLines = 1)
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
            add("已记" to "${summary.count} 笔")
            if (summary.refund != 0L) add("退款" to formatAmount(summary.refund))
        }
        val gap = AbCardDensity.gap
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
                            Text(label, style = AbType.caption1, color = when (label) { "收入" -> p.income; "退款" -> p.refund; else -> p.secondaryLabel })
                            Text(value, style = AbType.subhead.copy(fontWeight = FontWeight.Medium),
                                color = when (label) { "收入" -> p.income; "退款" -> p.refund; else -> p.label })
                        }
                    }
                }
            }
        }
    }
}
