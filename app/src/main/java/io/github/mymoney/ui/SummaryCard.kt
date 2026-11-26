package io.github.mymoney.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.BillQuerySummary
import io.github.mymoney.data.LedgerDateWindow
import io.github.mymoney.data.ledgerDateWindow
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbPalette
import io.github.mymoney.ui.design.AbRadius
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSpring
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AmountText
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.abPressFeedback
import io.github.mymoney.ui.design.displayTracking
import io.github.mymoney.ui.design.formatAmount
import io.github.mymoney.ui.design.tabular
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface SummaryState {
    data object Loading : SummaryState
    data object Failed : SummaryState
    data class Ready(val summary: BillQuerySummary, val window: LedgerDateWindow) : SummaryState
}

/** The period summary uses unbounded DAO aggregation, independently of the paged daily list. */
@Composable
private fun rememberSummary(start: LocalDate, end: LocalDate, clock: LedgerClock, retry: Int): SummaryState {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { AppDb.get(context).bills() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = remember(start, end, clock.zone) { ledgerDateWindow(start, end, clock.zone) }
    var state by remember(dao, retry) { mutableStateOf<SummaryState>(SummaryState.Loading) }
    LaunchedEffect(dao, lifecycle, window, clock.zone, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dao.observeFilteredSummary("%%", window.fromMillis, window.toMillis, null, null)
                .map<BillQuerySummary, SummaryState> { SummaryState.Ready(it, window) }
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
    period: io.github.mymoney.data.LedgerMonthPeriod,
    back: Int,
    maxBack: Int? = null,
    onPeriodBackChange: (Int) -> Unit,
) {
    val p = LocalAbPalette.current
    val monthStartDay = period.day
    var retry by remember { mutableIntStateOf(0) }
    val target = period.month
    val expectedWindow = remember(period.startDate, period.endDate, clock.zone) {
        ledgerDateWindow(period.startDate, period.endDate, clock.zone)
    }
    val state = rememberSummary(period.startDate, period.endDate, clock, retry)
    val shape = RoundedCornerShape(AbRadius.group)

    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    var pendingSlideDir by remember { mutableIntStateOf(0) }
    var lastSlideDir by remember { mutableIntStateOf(0) }
    var displayedReady by remember { mutableStateOf<SummaryState.Ready?>(null) }
    var displayedBack by remember { mutableIntStateOf(back) }
    var displayedTarget by remember { mutableStateOf(target) }
    val latestBack by rememberUpdatedState(back)
    val latestMaxBack by rememberUpdatedState(maxBack)
    val latestOnChange by rememberUpdatedState(onPeriodBackChange)

    LaunchedEffect(state, expectedWindow, back, target) {
        if (state is SummaryState.Ready && state.window == expectedWindow) {
            if (pendingSlideDir != 0) {
                lastSlideDir = pendingSlideDir
                pendingSlideDir = 0
            }
            displayedReady = state
            displayedBack = back
            displayedTarget = target
            if (dragX.value != 0f) {
                scope.launch {
                    if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                    else dragX.animateTo(0f, AbSpring.navigationFloat(false))
                }
            }
        } else if (state is SummaryState.Failed && dragX.value != 0f) {
            pendingSlideDir = 0
            scope.launch {
                if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                else dragX.animateTo(0f, AbSpring.navigationFloat(false))
            }
        }
    }

    val shownBack = if (displayedReady != null) displayedBack else back
    val shownTarget = if (displayedReady != null) displayedTarget else target
    val monthName = if (monthStartDay == 1) monthLabel(shownBack, shownTarget) else when (shownBack) { 0 -> "本期"; 1 -> "上期"; else -> "历史期" }
    val resolvedMaxBack = (maxBack ?: 0).coerceAtLeast(shownBack)
    val showDots = maxBack != null && maxBack > 0

    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.cardBg)
            .pointerInput(reduceMotion) {
                val tracker = VelocityTracker()
                var distance = 0f
                val thresholdPx = with(density) { 56.dp.toPx() }
                val maxDragPx = with(density) { 56.dp.toPx() }
                val maxEdgePx = with(density) { 12.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragStart = {
                        distance = 0f
                        tracker.resetTracking()
                        scope.launch { dragX.stop() }
                    },
                    onDragCancel = {
                        distance = 0f
                        scope.launch {
                            if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                            else dragX.animateTo(0f, AbSpring.navigationFloat(false))
                        }
                    },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().x
                        val curBack = latestBack
                        val limitBack = latestMaxBack
                        val canGoOlder = limitBack == null || curBack < limitBack
                        val dir = when {
                            (distance > thresholdPx || velocity > 520f) && canGoOlder -> 1
                            (distance < -thresholdPx || velocity < -520f) && curBack > 0 -> -1
                            else -> 0
                        }
                        distance = 0f
                        if (dir != 0) {
                            pendingSlideDir = -dir
                            latestOnChange(curBack + dir)
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
                            val limitBack = latestMaxBack
                            val atNewestEdge = latestBack == 0 && distance < 0f
                            val atOldestEdge = limitBack != null && latestBack >= limitBack && distance > 0f
                            val visual = if (atNewestEdge || atOldestEdge) {
                                val sign = if (distance < 0f) -1f else 1f
                                val over = abs(distance)
                                sign * maxEdgePx * (over / (over + maxEdgePx * 2f))
                            } else {
                                (distance * 0.42f).coerceIn(-maxDragPx, maxDragPx)
                            }
                            scope.launch { dragX.snapTo(visual) }
                        }
                    },
                )
            },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(AbSpring.morphSize(reduceMotion))
                .padding(top = AbCardDensity.content, bottom = if (showDots) AbSpacing.s else AbCardDensity.content),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
        ) {
            val headerInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = AbCardDensity.content)
                    .clip(RoundedCornerShape(AbRadius.row))
                    .abPressFeedback(headerInteraction, enabled = shownBack > 0, pressedScale = 0.985f, pressedAlpha = 0.72f)
                    .clickable(interactionSource = headerInteraction, indication = null, enabled = shownBack > 0, onClickLabel = "回到本期") {
                        pendingSlideDir = 1
                        onPeriodBackChange(0)
                    }
                    .heightIn(min = 40.dp)
                    .semantics(mergeDescendants = true) {
                        if (shownBack > 0) contentDescription = "${period.label()}，点击回到本期"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    AnimatedContent(
                        targetState = monthName,
                        transitionSpec = {
                            fadeIn(tween(if (reduceMotion) 90 else 130, easing = AbEasing.EaseOut)) togetherWith
                                fadeOut(tween(if (reduceMotion) 70 else 95, easing = AbEasing.EaseOut))
                        },
                        label = "summary-period-title",
                    ) { title ->
                        Text("${title}支出", style = AbType.headline.copy(fontSize = 16.sp), color = if (shownBack > 0) p.accent else p.label)
                    }
                }
                if (shownBack > 0) {
                    Text(
                        period.shortLabel(),
                        style = AbType.caption1.tabular(),
                        color = p.secondaryLabel,
                    )
                }
            }
            when {
                state is SummaryState.Failed -> {
                    Column(Modifier.padding(horizontal = AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                        Text("${monthName}账单读取失败", style = AbType.subhead, color = p.label)
                        AbTextButton("重新加载", onClick = { retry++ }, color = p.label)
                    }
                }
                displayedReady != null -> {
                    val activeReady = displayedReady!!
                    Box(Modifier.fillMaxWidth().clipToBounds()) {
                        AnimatedContent(
                            targetState = Triple(shownBack, shownTarget, activeReady),
                            contentKey = { it.third.window },
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { if (!reduceMotion) translationX = dragX.value },
                            transitionSpec = {
                                val dir = lastSlideDir
                                val transform = if (reduceMotion || dir == 0) {
                                    fadeIn(tween(if (reduceMotion) 110 else 150, easing = AbEasing.EaseOut)) togetherWith
                                        fadeOut(tween(if (reduceMotion) 90 else 110, easing = AbEasing.EaseOut))
                                } else {
                                    val spec = AbSpring.navigationOffset(false)
                                    (slideInHorizontally(spec) { w -> dir * w } +
                                        fadeIn(tween(150, easing = AbEasing.EaseOut))) togetherWith
                                        (slideOutHorizontally(spec) { w -> -dir * w } +
                                            fadeOut(tween(110, easing = AbEasing.EaseOut)))
                                }
                                transform using SizeTransform(clip = false)
                            },
                            label = "summary-period-body",
                        ) { (itemBack, itemTarget, readyItem) ->
                            val itemMonthName = if (monthStartDay == 1) monthLabel(itemBack, itemTarget) else when (itemBack) { 0 -> "本期"; 1 -> "上期"; else -> "历史期" }
                            val summary = readyItem.summary
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = AbCardDensity.content),
                                verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
                            ) {
                                // 主数字：整块宽度可用，金额不省略、不缩到读不出（AmountText 自身有下限）。
                                SummaryHeroAmount(formatAmount(summary.expense), "${itemMonthName}支出", p)
                                SummarySecondary(summary, p)
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        "正在读取${monthName}账单…",
                        style = AbType.subhead,
                        color = p.secondaryLabel,
                        modifier = Modifier.padding(horizontal = AbCardDensity.content),
                    )
                }
            }
            if (showDots) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SummaryPeriodDots(back = shownBack, maxBack = resolvedMaxBack)
                }
            }
        }
    }
}

/**
 * 底部自适应三点分页引导：
 * - 最新期（back == 0）最右侧点亮；
 * - 真正最早一期（back >= maxBack）最左侧点亮；
 * - 中间期高亮始终居于中点，并随滑动窗口推移接力动画提示可继续左右翻页。
 */
@Composable
private fun SummaryPeriodDots(back: Int, maxBack: Int) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val count = (maxBack + 1).coerceAtLeast(2)
    val activeIdx = (maxBack - back).coerceIn(0, maxBack)
    val visibleSlots = minOf(3, count)
    val windowStart = if (visibleSlots < 3) 0 else (activeIdx - 1).coerceIn(0, maxBack - 2)
    val animActive by animateFloatAsState(
        activeIdx.toFloat(),
        if (reduceMotion) tween(100, easing = AbEasing.EaseOut) else AbSpring.morphFloat(false),
        label = "summary-dot-active",
    )
    val animWindow by animateFloatAsState(
        windowStart.toFloat(),
        if (reduceMotion) tween(100, easing = AbEasing.EaseOut) else AbSpring.morphFloat(false),
        label = "summary-dot-window",
    )
    val widthDp = if (visibleSlots == 2) 32.dp else 46.dp
    val inactiveColor = p.secondaryLabel.copy(alpha = 0.28f)
    Canvas(
        Modifier
            .width(widthDp)
            .height(12.dp)
            .clipToBounds()
            .semantics { contentDescription = "可左右滑动切换账期" },
    ) {
        val stepPx = 11.dp.toPx()
        val cy = size.height / 2f
        val centerOffset = (visibleSlots - 1) / 2f
        val centerX = size.width / 2f
        val minDot = (floor(animWindow).toInt() - 1).coerceAtLeast(0)
        val maxDot = (ceil(animWindow).toInt() + visibleSlots).coerceAtMost(maxBack)
        val idleW = 5.dp.toPx()
        val activeW = 12.dp.toPx()
        val idleH = 5.dp.toPx()
        val activeH = 5.5.dp.toPx()
        for (i in minDot..maxDot) {
            val slot = i - animWindow
            val edgeFade = if (visibleSlots < 3) 1f else {
                minOf((slot + 0.35f) / 0.45f, (visibleSlots - 1 + 0.35f - slot) / 0.45f).coerceIn(0f, 1f)
            }
            if (edgeFade <= 0.01f) continue
            val activeFactor = (1f - abs(i - animActive)).coerceIn(0f, 1f)
            val scale = 0.55f + 0.45f * edgeFade
            val w = (idleW + (activeW - idleW) * activeFactor) * scale
            val h = (idleH + (activeH - idleH) * activeFactor) * scale
            val cx = centerX + (slot - centerOffset) * stepPx
            val color = lerp(inactiveColor, p.accent, activeFactor).let { it.copy(alpha = it.alpha * edgeFade) }
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(h / 2f),
            )
        }
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
        Text(text, style = AbType.amountHero.displayTracking(fitted).copy(lineHeight = (fitted * 1.16f).sp),
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
                            Text(value, style = AbType.subhead.copy(fontWeight = FontWeight.Medium).tabular(),
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = when (label) { "收入" -> p.income; "退款" -> p.refund; else -> p.label })
                        }
                    }
                }
            }
        }
    }
}
