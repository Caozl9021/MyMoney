package com.example.autobook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.example.autobook.data.*
import com.example.autobook.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.LocalDate

private sealed interface StatisticsLoad {
    data class Ready(val value: StatisticsSummary) : StatisticsLoad
    data class Failed(val range: StatisticsRange) : StatisticsLoad
}

/** Full local calendar ranges; this page does not depend on the home page's recent window. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(onOpenDetails: () -> Unit = {}) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val dao = remember(context) { AppDb.get(context).bills() }
    val listState = rememberLazyListState()
    var periodName by rememberSaveable { mutableStateOf(StatisticsPeriod.WEEK.name) }
    val clock = rememberLedgerClock()
    var anchorDay by rememberSaveable { mutableLongStateOf(clock.date.toEpochDay()) }
    var type by rememberSaveable { mutableStateOf("EXPENSE") }
    var grouping by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var followsToday by rememberSaveable { mutableStateOf(true) }
    val zone = clock.zone
    val effectiveAnchorDay = if (followsToday) clock.date.toEpochDay() else anchorDay
    val period = StatisticsPeriod.valueOf(periodName)
    val range = remember(period, effectiveAnchorDay, zone) {
        StatisticsRange.containing(LocalDate.ofEpochDay(effectiveAnchorDay), period, zone)
    }
    var result by remember { mutableStateOf<StatisticsLoad?>(null) }
    var selectedDate by remember(range, type) { mutableStateOf<LocalDate?>(null) }
    var selectedGroup by remember(range, type, grouping) { mutableStateOf<String?>(null) }
    LaunchedEffect(dao, range, retry) {
        result = null
        try {
            combine(dao.observeRange(range.fromMillis, range.toMillis), AssetStore.accounts) { bills, accounts ->
                val namesById = accounts.associate { it.id to it.name }
                bills.map { bill -> bill.accountId?.let(namesById::get)?.let { bill.copy(account = it) } ?: bill }
            }.collect { bills ->
                val value = withContext(Dispatchers.Default) { statistics(bills, range) }
                result = StatisticsLoad.Ready(value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = StatisticsLoad.Failed(range)
        }
    }
    // A range tag prevents a new period from briefly displaying the previous period's data.
    val summary = (result as? StatisticsLoad.Ready)?.value?.takeIf { it.range == range }
    val failed = (result as? StatisticsLoad.Failed)?.range == range
    LaunchedEffect(summary, type, grouping) {
        if (grouping == 1 && summary != null && selectedGroup != null && summary.accounts(type).none { it.name == selectedGroup }) {
            selectedGroup = null
        }
    }
    val reduceMotion = LocalReduceMotion.current
    val reveal = remember(range) { Animatable(if (reduceMotion) 1f else 0f) }
    var presented by remember(range) { mutableStateOf(false) }
    LaunchedEffect(range, summary != null, reduceMotion) {
        if (reduceMotion) reveal.snapTo(1f)
        if (summary != null && !presented) {
            presented = true
            if (reduceMotion) reveal.snapTo(1f) else reveal.animateTo(1f, tween(320))
        }
    }
    val progress = if (reduceMotion) 1f else reveal.value
    fun shift(amount: Long) {
        followsToday = false
        anchorDay = range.shift(amount).startDate.toEpochDay()
    }
    // 周期文字必须短到一行放得下：周用「10月第4周」，月用「2026年10月」。
    // 原来周写「2026-10-19 至 2026-10-25」，在 400dp 宽下被省略号截断。
    val label = statisticsPeriodLabel(period, range)
    // 紧凑标题：统计页不在顶部占一块大标题，「统计」压在导航条里，
    // 省下的 69dp 直接给了趋势图（V1-01 的首屏量化目标）。
    PageScaffold(title = "统计", listState = listState, compact = true,
        actions = {
            TextButton(onClick = onOpenDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("明细", color = p.accent)
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().pointerInput(range) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        if (distance > 72.dp.toPx()) shift(-1)
                        else if (distance < -72.dp.toPx()) shift(1)
                    },
                    onHorizontalDrag = { _, delta -> distance += delta },
                )
            },
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = AbSpacing.xs, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // 周期与类型压在同一屏的两行里：一行周期（箭头+标签+回本月），
            // 一行两个分段选择器并排。原来「回到本月」独占一行，白占 48dp。
            stickyHeader(key = "period-and-type") {
                Column(Modifier.background(p.pageBg).padding(bottom = AbSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    // 一行放完周/月、前后周期与回到本月：原来「周/月」独占的第二行
                    // 白占 48dp，是首屏图表被挤下去的主要原因之一。
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                        Box(Modifier.width(96.dp)) {
                            Segmented(listOf("周", "月"), if (period == StatisticsPeriod.WEEK) 0 else 1, {
                                periodName = if (it == 0) StatisticsPeriod.WEEK.name else StatisticsPeriod.MONTH.name
                            })
                        }
                        PeriodArrow(true) { shift(-1) }
                        Text(label, style = AbType.headline, color = p.label,
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (!followsToday) Text(
                            if (period == StatisticsPeriod.WEEK) "本周" else "本月",
                            style = AbType.footnote.copy(fontWeight = FontWeight.Medium),
                            color = p.accent,
                            modifier = Modifier.clip(RoundedCornerShape(AbRadius.capsule))
                                .clickable { followsToday = true; anchorDay = clock.date.toEpochDay() }
                                .heightIn(min = 40.dp)
                                .padding(horizontal = AbSpacing.s, vertical = 10.dp),
                        )
                        PeriodArrow(false) { shift(1) }
                    }
                    Segmented(listOf("支出", "收入", "退款"), listOf("EXPENSE", "INCOME", "REFUND").indexOf(type), {
                        type = listOf("EXPENSE", "INCOME", "REFUND")[it]
                    })
                }
            }
            if (summary == null) {
                item("status") {
                    InsetGroup(Modifier.padding(bottom = AbSpacing.l)) {
                        Column(Modifier.padding(AbSpacing.xl)) {
                            Text(if (failed) "统计加载失败，请重试。" else "正在加载本周期…", color = p.secondaryLabel)
                            if (failed) TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("重新加载", color = p.accent)
                            }
                        }
                    }
                }
            } else {
                // 摘要卡：主金额最大，其余口径两列并排，不再四行逐项纵排。
                item("totals") {
                    InsetGroup(Modifier.padding(bottom = AbSpacing.m)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl, vertical = AbSpacing.m),
                            verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                            val primary = when (type) {
                                "INCOME" -> summary.totals.income
                                "REFUND" -> summary.totals.refund
                                else -> summary.totals.expense
                            }
                            // 笔数/转账口径跟标题同一行，省一行高度给首屏的图表。
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text("${if (followsToday) "本期" else "该周期"}${statisticsTypeLabel(type)}",
                                    style = AbType.footnote, color = p.secondaryLabel, modifier = Modifier.weight(1f))
                                Text("共 ${summary.count} 笔 · 转账 ${summary.transferCount} 笔不计收支",
                                    style = AbType.caption1, color = p.tertiaryLabel,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                            AmountText(formatAmount(primary), AbType.amountHero, amountTint(p, type),
                                Modifier.fillMaxWidth())
                            StatisticsSecondaryGrid(summary, type)
                        }
                    }
                }
                if (summary.count == 0) {
                    item("empty") { Text("本周期没有账单，可用箭头查看其他周期。", color = p.secondaryLabel,
                        modifier = Modifier.padding(bottom = AbSpacing.l)) }
                }
                item("trend") {
                    InsetGroup(Modifier.padding(bottom = AbSpacing.m)) {
                        Column(Modifier.padding(start = AbSpacing.xl, end = AbSpacing.xl, top = AbSpacing.m, bottom = AbSpacing.m),
                            verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                Text("每日${statisticsShortTypeLabel(type)}", style = AbType.headline, color = p.label,
                                    modifier = Modifier.weight(1f))
                                val peak = summary.days.maxOfOrNull { it.amount(type) } ?: 0L
                                Text("最高 ${formatAmount(peak)}", style = AbType.caption1, color = p.secondaryLabel)
                            }
                            // 周看柱状（7 天一次排满），月看热力图（日历网格，
                            // 31 根柱在 400dp 宽里每根只有 8dp，辨识度太低）。
                            if (period == StatisticsPeriod.MONTH) {
                                StatisticsMonthHeatmap(summary.days, type, selectedDate, progress) { selectedDate = it }
                            } else {
                                StatisticsDailyChart(summary.days, type, selectedDate, progress) { selectedDate = it }
                            }
                            val day = summary.days.firstOrNull { it.date == selectedDate }
                            Text(if (day == null) "${summary.days.size} 天 · 合计 ${formatAmount(summary.days.sumOf { it.amount(type) })}"
                                else "${statisticsDayLabel(day.date)} · ${formatAmount(day.amount(type))} · ${day.count(type)} 笔",
                                style = AbType.footnote, color = p.label)
                        }
                    }
                }
                item("grouping") {
                    Row(Modifier.fillMaxWidth().padding(bottom = AbSpacing.l),
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(196.dp)) {
                            Segmented(listOf("分类占比", "账户汇总"), grouping, { grouping = it })
                        }
                    }
                }
                val groups = if (grouping == 0) summary.categories(type) else summary.accounts(type)
                item("ring") {
                    InsetGroup(Modifier.padding(bottom = AbSpacing.l)) {
                        Column(Modifier.fillMaxWidth().padding(AbSpacing.xl), verticalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                            Text("${statisticsTypeLabel(type)} · ${if (grouping == 0) "分类" else "账户"}", style = AbType.headline, color = p.label)
                            if (groups.isEmpty()) Text("本周期没有${statisticsTypeLabel(type)}记录。", color = p.secondaryLabel)
                            else {
                                StatisticsRing(groups, type, selectedGroup, progress) { selectedGroup = it }
                                Text("合计 ${formatAmount(groups.sumOf { it.cents })} · ${groups.sumOf { it.count }} 笔",
                                    style = AbType.footnote, color = p.label)
                                groups.firstOrNull { it.name == selectedGroup }?.let {
                                    Text("已选：${it.name} · ${formatAmount(it.cents)} · ${it.count} 笔 · ${statisticsPercent(it.ratio)}",
                                        style = AbType.footnote, color = p.label)
                                }
                            }
                        }
                    }
                }
                groups.forEachIndexed { index, group ->
                    item("group-$grouping-${group.name}") {
                        CompactStatisticsGroupRow(index + 1, group, selectedGroup == group.name, index == groups.lastIndex) { selectedGroup = group.name }
                    }
                }

            }
        }
    }
}

@Composable
private fun PeriodArrow(previous: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(AbRadius.capsule)).clickable(onClick = onClick)
        .semantics { contentDescription = if (previous) "上一个周期" else "下一个周期" }, contentAlignment = Alignment.Center) {
        Icon(AbIcons.ChevronRight, null, tint = p.label,
            modifier = Modifier.size(18.dp).graphicsLayer(scaleX = if (previous) -1f else 1f))
    }
}

/**
 * 其他口径：标准字号两列并排，大字号自动转一列。
 *
 * 每格是「标签在上、金额在下」的两行，而不是左右对齐的一行 ——
 * 左右对齐在 400dp 宽、长金额（如 −¥12,345.67）时会把标签挤成省略号。
 */
@Composable
private fun StatisticsSecondaryGrid(summary: StatisticsSummary, type: String) {
    val p = LocalAbPalette.current
    val fontScale = LocalDensity.current.fontScale
    val cells = buildList {
        if (type != "EXPENSE") add(Triple("原支出", formatAmount(summary.totals.expense), amountTint(p, "EXPENSE")))
        if (type != "INCOME") add(Triple("收入", formatAmount(summary.totals.income), amountTint(p, "INCOME")))
        if (type != "REFUND") add(Triple("退款", formatAmount(summary.totals.refund), amountTint(p, "REFUND")))
        add(Triple("结余", signedStatisticsAmount(summary.totals.balance), p.label))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AbSpacing.l
        // 三格（收入/退款/结余）在标准字号下能并成一行就并成一行，
        // 少两行文字，首屏就多出两行给图表。大字号/窄屏自动退回两列、一列。
        val minimumCell = 88.dp * fontScale.coerceAtLeast(1f)
        val columns = when {
            maxWidth >= minimumCell * 3 + gap * 2 -> 3
            maxWidth >= minimumCell * 2 + gap -> 2
            else -> 1
        }
        val cellWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (label, value, tint) ->
                        Row(Modifier.width(cellWidth), verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                            Text(label, style = AbType.caption1, color = p.secondaryLabel)
                            Text(value, style = AbType.subhead.copy(fontWeight = FontWeight.Medium), color = tint,
                                maxLines = 1)
                        }
                    }
                    // 最后一行不足一列时补位，避免单格被拉满整行。
                    repeat(columns - row.size) { Spacer(Modifier.width(cellWidth)) }
                }
            }
        }
    }
}

private fun signedStatisticsAmount(cents: Long) =
    (if (cents < 0) "−" else "") + formatAmount(if (cents < 0) -cents else cents)

@Composable
private fun CompactStatisticsGroupRow(
    index: Int, group: StatisticsGroup, isSelected: Boolean, last: Boolean, onClick: () -> Unit,
) {
    val p = LocalAbPalette.current
    val shape = RoundedCornerShape(topStart = if (index == 1) AbRadius.group else 0.dp,
        topEnd = if (index == 1) AbRadius.group else 0.dp,
        bottomStart = if (last) AbRadius.group else 0.dp,
        bottomEnd = if (last) AbRadius.group else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.cardBg)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (isSelected) p.fill else p.cardBg)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { selected = isSelected }
            .padding(horizontal = AbSpacing.l, vertical = AbSpacing.m),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(10.dp)
                    .background(AbAccentSwatches[(index - 1) % AbAccentSwatches.size], RoundedCornerShape(3.dp)))
                Text("$index. ${group.name}${if (isSelected) " · 已选" else ""}",
                    style = AbType.subhead, color = p.label, modifier = Modifier.weight(1f))
            }
            Text("${formatAmount(group.cents)} · ${group.count} 笔 · ${statisticsPercent(group.ratio)}",
                style = AbType.footnote, color = p.secondaryLabel)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.fill)) {
                if (group.ratio > 0) Box(Modifier.fillMaxWidth(group.ratio.toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight().background(AbAccentSwatches[(index - 1) % AbAccentSwatches.size]))
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(horizontal = AbSpacing.l), color = p.separator, thickness = 0.5.dp)
    }
}

/**
 * 周期标签：周 =「10月第4周」（按周一所在月份与月内序号），月 =「2026年10月」。
 *
 * 不带年份的周标签是本轮明确要求：原来的「2026-10-19 至 2026-10-25」
 * 在一行里放不下，末尾会被省略号吃掉。
 */
internal fun statisticsPeriodLabel(period: StatisticsPeriod, range: StatisticsRange): String =
    if (period == StatisticsPeriod.MONTH) {
        "${range.startDate.year}年${range.startDate.monthValue}月"
    } else {
        val monday = range.startDate
        "${monday.monthValue}月第${statisticsWeekOfMonth(monday)}周"
    }

/**
 * 周一所在月份里的第几「行」日历周。
 *
 * 按该月 1 号是周几先留出空位再数行，例如 2026-10-01 是周四，
 * 10/19 就落在第 4 行 —— 与日历上看到的位置一致；
 * 直接用「(day-1)/7+1」会把 10/19 数成第 3 周，与日历不符。
 */
internal fun statisticsWeekOfMonth(monday: java.time.LocalDate): Int {
    val offset = monday.withDayOfMonth(1).dayOfWeek.value - 1
    return (monday.dayOfMonth + offset - 1) / 7 + 1
}

/** 点选后的日期，例如「10月24日 周六」，不重复年份。 */
internal fun statisticsDayLabel(date: LocalDate): String {
    val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
    return "${date.monthValue}月${date.dayOfMonth}日 $weekday"
}

internal fun statisticsTypeLabel(type: String) = when (type) { "INCOME" -> "收入"; "REFUND" -> "退款"; else -> "支出" }

/** 图表/环图用的短标签：「每日支出」而不是「每日原支出」。 */
internal fun statisticsShortTypeLabel(type: String) = when (type) { "INCOME" -> "收入"; "REFUND" -> "退款"; else -> "支出" }
internal fun statisticsPercent(ratio: Double) = String.format(java.util.Locale.US, "%.1f%%", ratio * 100)
