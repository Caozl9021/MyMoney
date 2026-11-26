package io.github.mymoney.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.*
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToLong

private sealed interface StatisticsLoad {
    data class Ready(val value: StatisticsSummary) : StatisticsLoad
    data class Failed(val range: StatisticsRange) : StatisticsLoad
}

/** Full local calendar ranges; this page does not depend on the home page's recent window. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(onOpenDetails: () -> Unit = {}, onOpenGroup: (StatisticsDrilldown) -> Unit = {}) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val db = remember(context) { AppDb.get(context) }
    val dao = remember(db) { db.bills() }
    val listState = rememberLazyListState()
    var periodName by rememberSaveable { mutableStateOf(StatisticsPeriod.WEEK.name) }
    val clock = rememberLedgerClock()
    val monthStartDay = rememberMonthStartDay()
    var anchorDay by rememberSaveable { mutableLongStateOf(clock.date.toEpochDay()) }
    var type by rememberSaveable { mutableStateOf("EXPENSE") }
    var grouping by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var followsToday by rememberSaveable { mutableStateOf(true) }
    val zone = clock.zone
    val period = StatisticsPeriod.valueOf(periodName)
    var monthDayAtAnchor by rememberSaveable { mutableIntStateOf(monthStartDay) }
    val effectiveAnchorDay = if (followsToday || period == StatisticsPeriod.MONTH && monthDayAtAnchor != monthStartDay)
        clock.date.toEpochDay() else anchorDay
    LaunchedEffect(monthStartDay, period) {
        if (period == StatisticsPeriod.MONTH && monthDayAtAnchor != monthStartDay) {
            followsToday = true; anchorDay = clock.date.toEpochDay(); monthDayAtAnchor = monthStartDay
        }
    }
    val range = remember(period, effectiveAnchorDay, zone, if (period == StatisticsPeriod.MONTH) monthStartDay else 1) {
        StatisticsRange.containing(LocalDate.ofEpochDay(effectiveAnchorDay), period, zone, monthStartDay)
    }
    val currentRange = remember(period, clock.date, zone, if (period == StatisticsPeriod.MONTH) monthStartDay else 1) {
        StatisticsRange.containing(clock.date, period, zone, monthStartDay)
    }
    var result by remember { mutableStateOf<StatisticsLoad?>(null) }
    var retainedSummary by remember { mutableStateOf<StatisticsSummary?>(null) }
    var selectedDate by remember(range, type) { mutableStateOf<LocalDate?>(null) }
    var selectedGroup by remember(range, type, grouping) { mutableStateOf<String?>(null) }
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val progress = 1f
    val dragX = remember { Animatable(0f) }
    var pendingSlideDir by remember { mutableLongStateOf(0L) }
    var lastSlideDir by remember { mutableIntStateOf(0) }
    var slideGeneration by remember { mutableIntStateOf(0) }
    var cachedWeekSummary by remember { mutableStateOf<StatisticsSummary?>(null) }
    var cachedMonthSummary by remember { mutableStateOf<StatisticsSummary?>(null) }
    val periodMorphT = remember { Animatable(if (period == StatisticsPeriod.MONTH) 1f else 0f) }
    var isMorphAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(periodName) {
        pendingSlideDir = 0L
        lastSlideDir = 0
        if (dragX.value != 0f) dragX.snapTo(0f)
    }
    LaunchedEffect(dao, range, retry) {
        try {
            combine(dao.observeRange(range.fromMillis, range.toMillis), db.assets().observeAccounts()) { bills, accounts ->
                val namesById = accounts.associate { it.id to it.name }
                bills.map { bill -> bill.accountId?.let(namesById::get)?.let { bill.copy(account = it) } ?: bill }
            }.collect { bills ->
                val value = withContext(Dispatchers.Default) { statistics(bills, range) }
                if (pendingSlideDir != 0L) {
                    lastSlideDir = if (pendingSlideDir < 0L) -1 else 1
                    pendingSlideDir = 0L
                    slideGeneration++
                    cachedWeekSummary = if (value.range.period == StatisticsPeriod.WEEK) value else null
                    cachedMonthSummary = if (value.range.period == StatisticsPeriod.MONTH) value else null
                    isMorphAnimating = false
                    periodMorphT.snapTo(if (value.range.period == StatisticsPeriod.MONTH) 1f else 0f)
                } else {
                    if (value.range.period == StatisticsPeriod.WEEK) cachedWeekSummary = value
                    else cachedMonthSummary = value
                }
                retainedSummary = value
                result = StatisticsLoad.Ready(value)
                if (dragX.value != 0f) {
                    scope.launch {
                        if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                        else dragX.animateTo(0f, AbSpring.navigationFloat(false))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pendingSlideDir = 0L
            result = StatisticsLoad.Failed(range)
            if (dragX.value != 0f) {
                scope.launch {
                    if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                    else dragX.animateTo(0f, AbSpring.navigationFloat(false))
                }
            }
        }
    }
    val targetMorphT = if (period == StatisticsPeriod.MONTH) 1f else 0f
    LaunchedEffect(period, slideGeneration, cachedWeekSummary != null, cachedMonthSummary != null, reduceMotion) {
        if (reduceMotion) {
            periodMorphT.snapTo(targetMorphT)
            isMorphAnimating = false
            return@LaunchedEffect
        }
        if (cachedWeekSummary == null || cachedMonthSummary == null) {
            isMorphAnimating = false
            return@LaunchedEffect
        }
        if (kotlin.math.abs(periodMorphT.value - targetMorphT) <= 0.002f) {
            periodMorphT.snapTo(targetMorphT)
            isMorphAnimating = false
            return@LaunchedEffect
        }
        isMorphAnimating = true
        try {
            periodMorphT.animateTo(
                targetMorphT,
                spring(dampingRatio = 0.88f, stiffness = 360f, visibilityThreshold = 0.002f),
            )
        } finally {
            if (kotlin.math.abs(periodMorphT.value - targetMorphT) <= 0.005f) {
                isMorphAnimating = false
            }
        }
    }
    val failed = (result as? StatisticsLoad.Failed)?.range == range
    val cachedForPeriod = if (period == StatisticsPeriod.WEEK) cachedWeekSummary else cachedMonthSummary
    val summary = if (failed) null else ((result as? StatisticsLoad.Ready)?.value?.takeIf { it.range == range } ?: cachedForPeriod?.takeIf { it.range == range } ?: retainedSummary)
    LaunchedEffect(summary, type, grouping) {
        if (grouping == 1 && summary != null && selectedGroup != null && summary.accounts(type).none { it.name == selectedGroup }) {
            selectedGroup = null
        }
    }
    fun shift(amount: Long) {
        if (amount != 0L) pendingSlideDir = amount
        followsToday = false
        anchorDay = range.shift(amount).startDate.toEpochDay()
    }
    // 周期文字必须短到一行放得下：周用「10月第4周」，月用「2026年10月」。
    // 原来周写「2026-10-19 至 2026-10-25」，在 400dp 宽下被省略号截断。
    val shownRange = summary?.range ?: range
    val label = statisticsPeriodLabel(shownRange.period, shownRange)
    // 紧凑标题：统计页不在顶部占一块大标题，「统计」压在导航条里，
    // 省下的 69dp 直接给了趋势图（V1-01 的首屏量化目标）。
    PageScaffold(title = "统计", listState = listState, compact = true, floatingHeader = true,
        actions = {
            io.github.mymoney.ui.design.FloatingHeaderAction(AbIcons.List, "查看明细", onOpenDetails)
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            // Pass topPadding through contentPadding so scrolled cards glide under the floating glass header.
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(range, reduceMotion) {
                    val tracker = VelocityTracker()
                    var distance = 0f
                    val maxDragPx = 72.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            distance = 0f
                            tracker.resetTracking()
                            scope.launch { dragX.stop() }
                        },
                        onDragCancel = {
                            distance = 0f
                            scope.launch {
                                if (reduceMotion) dragX.snapTo(0f) else dragX.animateTo(0f, AbSpring.navigationFloat(false))
                            }
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().x
                            val distThreshold = 72.dp.toPx()
                            val shiftAmount = when {
                                distance > distThreshold || velocity > 560f -> -1L
                                distance < -distThreshold || velocity < -560f -> 1L
                                else -> 0L
                            }
                            distance = 0f
                            if (shiftAmount != 0L) {
                                shift(shiftAmount)
                            } else {
                                scope.launch {
                                    if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                                    else dragX.animateTo(0f, AbSpring.navigationFloat(false), initialVelocity = velocity * 0.25f)
                                }
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            distance += delta
                            tracker.addPosition(change.uptimeMillis, Offset(distance, 0f))
                            if (!reduceMotion) {
                                val visual = (distance * 0.42f).coerceIn(-maxDragPx, maxDragPx)
                                scope.launch { dragX.snapTo(visual) }
                            }
                        },
                    )
                },
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Navigation keeps the same structure in current and historical periods.
            // At large font sizes the period moves to its own row without squeezing the label.
            item(key = "period-and-type") {
                Column(Modifier.padding(horizontal = AbSpacing.page).padding(bottom = AbSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 260.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val switch: @Composable () -> Unit = {
                            Segmented(
                                options = listOf("周", "月"),
                                selected = if (period == StatisticsPeriod.WEEK) 0 else 1,
                                onSelect = {
                                    val next = if (it == 0) StatisticsPeriod.WEEK.name else StatisticsPeriod.MONTH.name
                                    if (periodName != next) {
                                        periodName = next
                                    }
                                },
                                compact = true,
                                modifier = Modifier.width(112.dp),
                            )
                        }
                        val navigation: @Composable RowScope.() -> Unit = {
                            PeriodArrow(true) { shift(-1) }
                            val labelInteraction = remember { MutableInteractionSource() }
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .abPressFeedback(labelInteraction, enabled = !followsToday, pressedScale = 0.96f, pressedAlpha = 0.72f)
                                .clip(RoundedCornerShape(AbRadius.capsule))
                                .clickable(interactionSource = labelInteraction, indication = null, enabled = !followsToday, onClickLabel = "回到当前周期") {
                                    val todayEpoch = clock.date.toEpochDay()
                                    pendingSlideDir = if (anchorDay <= todayEpoch) 1L else -1L
                                    followsToday = true; anchorDay = todayEpoch
                                }.semantics(mergeDescendants = true) {
                                    contentDescription = if (followsToday) label else "$label，点击回到当前周期"
                                }, contentAlignment = Alignment.Center) {
                                AnimatedContent(
                                    targetState = label,
                                    transitionSpec = {
                                        fadeIn(tween(if (reduceMotion) 100 else 150, easing = AbEasing.EaseOut)) togetherWith
                                            fadeOut(tween(if (reduceMotion) 80 else 110, easing = AbEasing.EaseOut))
                                    },
                                    label = "stats-period-label",
                                ) { shownLabel ->
                                    Text(shownLabel, style = AbType.footnote.copy(fontWeight = FontWeight.SemiBold).tabular(),
                                        color = if (followsToday) p.label else p.accent,
                                        maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                            PeriodArrow(false) { shift(1) }
                        }
                        if (stacked) Column {
                            switch()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = navigation)
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                            switch()
                            navigation()
                        }
                    }
                    Segmented(listOf("支出", "收入", "退款"), listOf("EXPENSE", "INCOME", "REFUND").indexOf(type), {
                        type = listOf("EXPENSE", "INCOME", "REFUND")[it]
                    }, compact = true)
                }
            }
            if (summary == null) {
                item("status") {
                    InsetGroup(Modifier.padding(horizontal = AbSpacing.page).padding(bottom = AbCardDensity.gap)) {
                        Column(Modifier.padding(AbCardDensity.content)) {
                            Text(if (failed) "统计加载失败，请重试。" else "正在加载本周期…", color = p.secondaryLabel)
                            if (failed) AbTextButton("重新加载", onClick = { retry++ })
                        }
                    }
                }
            } else {
                item("cards") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clipToBounds(),
                    ) {
                        AnimatedContent(
                            targetState = summary,
                            contentKey = { slideGeneration },
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { if (!reduceMotion) translationX = dragX.value },
                            transitionSpec = {
                                val dir = lastSlideDir
                                val transform = if (reduceMotion || dir == 0) {
                                    fadeIn(tween(if (reduceMotion) 110 else 160, easing = AbEasing.EaseOut)) togetherWith
                                        fadeOut(tween(if (reduceMotion) 90 else 120, easing = AbEasing.EaseOut))
                                } else {
                                    val spec = AbSpring.navigationOffset(false)
                                    (slideInHorizontally(spec) { w -> dir * w } +
                                        fadeIn(tween(150, easing = AbEasing.EaseOut))) togetherWith
                                        (slideOutHorizontally(spec) { w -> -dir * w } +
                                            fadeOut(tween(110, easing = AbEasing.EaseOut)))
                                }
                                transform using SizeTransform(clip = false)
                            },
                            label = "stats-period-cards",
                        ) { activeSummary ->
                            val activeRange = activeSummary.range
                            val activePeriod = activeRange.period
                            val activeLabel = statisticsPeriodLabel(activePeriod, activeRange)
                            val activeIsCurrent = activeRange == currentRange
                            Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.page)) {
                                // 摘要卡：主金额最大，其余口径并排。
                                InsetGroup(Modifier.padding(bottom = AbCardDensity.gap)) {
                                    Column(Modifier.fillMaxWidth().padding(AbCardDensity.content),
                                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        val primary = when (type) {
                                            "INCOME" -> activeSummary.totals.income
                                            "REFUND" -> activeSummary.totals.refund
                                            else -> activeSummary.totals.expense
                                        }
                                        val rolledPrimary = rememberRolledLong(primary)
                                        val rolledCount = rememberRolledLong(activeSummary.count.toLong()).toInt()
                                        val primaryTint by animateColorAsState(
                                            amountTint(p, type),
                                            tween(if (reduceMotion) 110 else 260, easing = AbEasing.EaseOut),
                                            label = "stats-hero-tint",
                                        )
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${if (activeIsCurrent) "本期" else "该周期"}${statisticsTypeLabel(type)}",
                                                style = AbType.headline.copy(fontSize = 16.sp), color = p.label, modifier = Modifier.weight(1f))
                                            Text("共 $rolledCount 笔",
                                                style = AbType.caption1.tabular(), color = p.secondaryLabel,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                        }
                                        AmountText(formatAmount(rolledPrimary), AbType.amountHero, primaryTint,
                                            Modifier.fillMaxWidth())
                                        StatisticsSecondaryGrid(activeSummary, type)
                                    }
                                }
                            if (activeSummary.count == 0) {
                                Text("本周期没有账单，可用箭头查看其他周期。", color = p.secondaryLabel,
                                    modifier = Modifier.padding(bottom = AbCardDensity.gap))
                            }
                            InsetGroup(Modifier.padding(bottom = AbCardDensity.gap)) {
                                Column(Modifier.padding(AbCardDensity.content),
                                    verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                    val day = activeSummary.days.firstOrNull { it.date == selectedDate }
                                    val peak = activeSummary.days.maxOfOrNull { it.amount(type) } ?: 0L
                                    val rolledPeak = rememberRolledLong(peak)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                        Text("每日${statisticsShortTypeLabel(type)}", style = AbType.headline, color = p.label,
                                            modifier = Modifier.weight(1f))
                                        Text(
                                            if (day == null) "最高 ${formatAmount(rolledPeak)}"
                                            else "${statisticsDayLabel(day.date)} · ${formatAmount(day.amount(type))} · ${day.count(type)} 笔",
                                            style = AbType.caption1.tabular(),
                                            color = if (day == null) p.secondaryLabel else p.label,
                                        )
                                    }
                                    val morphWeek = cachedWeekSummary
                                    val morphMonth = cachedMonthSummary
                                    val showMorph = !reduceMotion && morphWeek != null && morphMonth != null &&
                                        (isMorphAnimating || kotlin.math.abs(periodMorphT.value - targetMorphT) > 0.002f)
                                    if (showMorph) {
                                        StatisticsWeekMonthMorphChart(
                                            weekDays = morphWeek!!.days,
                                            monthDays = morphMonth!!.days,
                                            type = type,
                                            morphT = periodMorphT.value,
                                        )
                                    } else if (activePeriod == StatisticsPeriod.MONTH) {
                                        StatisticsMonthHeatmap(activeSummary.days, type, selectedDate, progress) {
                                            selectedDate = if (selectedDate == it) null else it
                                        }
                                    } else {
                                        StatisticsDailyChart(activeSummary.days, type, selectedDate, progress) {
                                            selectedDate = if (selectedDate == it) null else it
                                        }
                                    }
                                }
                            }
                            val groups = if (grouping == 0) activeSummary.categories(type) else activeSummary.accounts(type)
                            InsetGroup(Modifier.padding(bottom = AbCardDensity.gap).animateContentSize(AbSpring.morphSize(reduceMotion))) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = AbCardDensity.content,
                                            end = AbCardDensity.content,
                                            top = AbCardDensity.headerVertical,
                                            bottom = AbSpacing.xs,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("构成占比", style = AbType.headline, color = p.label, modifier = Modifier.weight(1f))
                                    Box(Modifier.width(116.dp)) {
                                        Segmented(listOf("分类", "账户"), grouping, { grouping = it }, compact = true)
                                    }
                                }
                                if (groups.isEmpty()) {
                                    Text(
                                        "本周期没有${statisticsTypeLabel(type)}记录。",
                                        style = AbType.subhead,
                                        color = p.secondaryLabel,
                                        modifier = Modifier.padding(
                                            start = AbCardDensity.content,
                                            end = AbCardDensity.content,
                                            bottom = AbCardDensity.content,
                                        ),
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = AbCardDensity.content, vertical = AbSpacing.xs),
                                    ) {
                                        StatisticsRing(groups, type, selectedGroup, progress, period = activePeriod) {
                                            selectedGroup = if (selectedGroup == it) null else it
                                        }
                                    }
                                    InsetDivider(inset = AbCardDensity.content)
                                    groups.forEachIndexed { index, group ->
                                        StatisticsBreakdownRow(
                                            index = index,
                                            group = group,
                                            isAccount = grouping == 1,
                                            isSelected = selectedGroup == group.name,
                                            last = index == groups.lastIndex,
                                        ) {
                                            onOpenGroup(
                                                StatisticsDrilldown(
                                                    activeRange.fromMillis,
                                                    activeRange.toMillis,
                                                    type,
                                                    group.name,
                                                    grouping == 1,
                                                    activeLabel,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberRolledLong(target: Long): Long {
    val reduceMotion = LocalReduceMotion.current
    var settled by remember { mutableStateOf<Long?>(null) }
    var from by remember { mutableLongStateOf(target) }
    var animGen by remember { mutableIntStateOf(0) }
    var startedGen by remember { mutableIntStateOf(0) }
    val animT = remember { Animatable(1f) }
    val prev = settled
    if (prev == null) {
        settled = target
    } else if (prev != target) {
        if (reduceMotion) {
            settled = target
            from = target
            startedGen = animGen
        } else {
            val curT = if (startedGen == animGen) animT.value.coerceIn(0f, 1f) else 0f
            from = if (startedGen == animGen && curT < 0.998f) {
                from + ((prev - from) * curT).roundToLong()
            } else {
                prev
            }
            settled = target
            animGen++
        }
    }
    LaunchedEffect(animGen, reduceMotion) {
        if (animGen == 0 || reduceMotion) {
            animT.snapTo(1f)
            startedGen = animGen
            from = target
            return@LaunchedEffect
        }
        animT.snapTo(0f)
        startedGen = animGen
        animT.animateTo(1f, tween(340, easing = AbEasing.EaseOut))
        if (startedGen == animGen) from = target
    }
    val activeTarget = settled ?: target
    val t = if (animGen == 0) 1f else if (startedGen == animGen) animT.value.coerceIn(0f, 1f) else 0f
    return from + ((activeTarget - from) * t).roundToLong()
}

@Composable
private fun PeriodArrow(previous: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    val dark = p.pageBg.luminance() < 0.1f
    val reduceMotion = LocalReduceMotion.current
    val haptic = rememberAbHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.90f else 1f,
        AbSpring.bouncyFloat(reduceMotion),
        label = "period-arrow-press",
    )
    Box(
        Modifier.size(AbSize.minTouch)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
            ) {
                haptic.select()
                onClick()
            }
            .semantics { contentDescription = if (previous) "上一个周期" else "下一个周期" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(
                    elevation = if (dark) 3.dp else 1.5.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.08f),
                )
                .background(p.cardBg, CircleShape)
                .border(0.85.dp, liquidSpecularRimBrush(isDark = dark, alphaScale = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AbIcons.ChevronRight,
                null,
                tint = p.label,
                modifier = Modifier.size(16.dp).graphicsLayer(scaleX = if (previous) -1f else 1f),
            )
        }
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
    val rolledExpense = rememberRolledLong(summary.totals.expense)
    val rolledIncome = rememberRolledLong(summary.totals.income)
    val rolledRefund = rememberRolledLong(summary.totals.refund)
    val rolledBalance = rememberRolledLong(summary.totals.balance)
    val cells = buildList {
        if (type != "EXPENSE") add(Triple("原支出", formatAmount(rolledExpense), amountTint(p, "EXPENSE")))
        if (type != "INCOME") add(Triple("收入", formatAmount(rolledIncome), amountTint(p, "INCOME")))
        if (type != "REFUND") add(Triple("退款", formatAmount(rolledRefund), amountTint(p, "REFUND")))
        add(Triple("结余", signedStatisticsAmount(rolledBalance), p.label))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AbCardDensity.gap
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
                            Text(value, style = AbType.subhead.copy(fontWeight = FontWeight.Medium).tabular(), color = tint,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
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
private fun StatisticsBreakdownRow(
    index: Int,
    group: StatisticsGroup,
    isAccount: Boolean,
    isSelected: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val swatch = swatchFor(group.name, index)
    val interaction = remember { MutableInteractionSource() }
    val rowBg by animateColorAsState(
        if (isSelected) p.fill.copy(alpha = 0.65f) else p.cardBg,
        tween(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut),
        label = "stats-row-bg",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .abPressFeedback(interaction, pressedScale = 0.988f, tintOverlay = true)
                .background(rowBg)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics(mergeDescendants = true) { selected = isSelected }
                .padding(horizontal = AbCardDensity.content, vertical = AbCardDensity.rowVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            if (isAccount) {
                val accounts by io.github.mymoney.data.AssetStore.accounts.collectAsStateWithLifecycle()
                val matchedAccount = remember(accounts, group.name) { accounts.firstOrNull { it.name == group.name } }
                if (matchedAccount != null) {
                    AccountAvatar(account = matchedAccount, size = AbCardDensity.icon)
                } else {
                    AccountAvatar(name = group.name, size = AbCardDensity.icon)
                }
            } else {
                CategoryBadge(category = group.name, tint = swatch, size = AbCardDensity.icon)
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
                ) {
                    Text(
                        group.name,
                        style = AbType.subhead.copy(fontWeight = FontWeight.Medium),
                        color = p.label,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        statisticsPercent(group.ratio),
                        style = AbType.caption1.tabular(),
                        color = p.secondaryLabel,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(p.fill),
                ) {
                    if (group.ratio > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth(group.ratio.toFloat().coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(swatch, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AmountText(formatAmount(group.cents), style = AbType.amountRow, color = p.label)
                    Text("${group.count} 笔", style = AbType.caption1.tabular(), color = p.secondaryLabel)
                }
                Icon(
                    AbIcons.ChevronRight,
                    contentDescription = null,
                    tint = p.tertiaryLabel,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
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
        if (range.monthStartDay == 1) "${range.startDate.year}年${range.startDate.monthValue}月"
        else io.github.mymoney.data.LedgerMonthPeriod.containing(range.startDate, range.monthStartDay, range.zone).shortLabel()
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
