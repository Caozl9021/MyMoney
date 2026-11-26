package io.github.mymoney.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbRadius
import io.github.mymoney.ui.design.AbSize
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSpring
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AppDialog
import io.github.mymoney.ui.design.AppDialogActions
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.abPressFeedback
import io.github.mymoney.ui.design.rememberAbHaptic
import io.github.mymoney.ui.design.tabular
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Local calendar date and hour/minute; only confirmation changes the caller's draft. */
internal fun ledgerTimeMillis(date: LocalDate, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
    require(hour in 0..23 && minute in 0..59)
    return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}

@Composable
internal fun LedgerDateTimeDialog(value: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(value, zone) { Instant.ofEpochMilli(value).atZone(zone) }
    val today = remember(zone) { LocalDate.now(zone) }
    var day by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(initial).toString()) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    val selected = LocalDate.parse(day)
    val shown = YearMonth.parse(month)
    val p = LocalAbPalette.current

    AppDialog(title = "日期与时间", onDismiss = onDismiss) {
        CalendarMonthHeader(
            shown = shown,
            onPrevious = { month = shown.minusMonths(1).toString() },
            onNext = { month = shown.plusMonths(1).toString() },
        )
        CalendarMonthGrid(
            shown = shown,
            today = today,
            rangeStart = selected,
            rangeEnd = selected,
            onPick = { date -> day = date.toString() },
            onPreviousMonth = { month = shown.minusMonths(1).toString() },
            onNextMonth = { month = shown.plusMonths(1).toString() },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
        ) {
            Text("时间", style = AbType.footnote, color = p.secondaryLabel)
            AbTextButton(
                text = "现在",
                onClick = {
                    val now = java.time.ZonedDateTime.now(zone)
                    day = now.toLocalDate().toString()
                    month = YearMonth.from(now).toString()
                    hour = now.hour
                    minute = now.minute
                },
            )
            Spacer(Modifier.weight(1f))
            LiquidWheelTimePicker(
                hour = hour,
                minute = minute,
                onHourChange = { hour = it },
                onMinuteChange = { minute = it },
            )
        }
        AppDialogActions(
            confirmLabel = "确定",
            onConfirm = {
                onConfirm(ledgerTimeMillis(LocalDate.parse(day), hour, minute, zone))
            },
            dismissLabel = "取消",
            onDismiss = onDismiss,
        )
    }
}

/** 自研液态玻璃起止日期区间选择弹窗（替代原生 Material 3 DateRangePicker）。 */
@Composable
internal fun LedgerRangePickerDialog(
    start: String,
    end: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onApply: (String, String) -> Unit,
) {
    val parsedStart = remember(start, today) { runCatching { LocalDate.parse(start) }.getOrDefault(today) }
    val parsedEnd = remember(end, today) { runCatching { LocalDate.parse(end) }.getOrDefault(today) }
    val orderedInitial = remember(parsedStart, parsedEnd) {
        if (parsedStart <= parsedEnd) parsedStart to parsedEnd else parsedEnd to parsedStart
    }
    var rangeStart by rememberSaveable { mutableStateOf(orderedInitial.first.toString()) }
    var rangeEnd by rememberSaveable { mutableStateOf<String?>(orderedInitial.second.toString()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(orderedInitial.second).toString()) }
    val startLocal = LocalDate.parse(rangeStart)
    val endLocal = rangeEnd?.let(LocalDate::parse)
    val shown = YearMonth.parse(month)
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f

    AppDialog(title = "选择起止日期", onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AbRadius.capsule))
                .background(p.fill)
                .padding(horizontal = AbSpacing.m, vertical = AbSpacing.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (endLocal != null) "$startLocal 至 $endLocal" else "$startLocal 至 待选结束日期",
                style = AbType.footnote.copy(fontWeight = FontWeight.Medium).tabular(),
                color = if (endLocal != null) p.label else p.accent,
            )
        }
        CalendarMonthHeader(
            shown = shown,
            onPrevious = { month = shown.minusMonths(1).toString() },
            onNext = { month = shown.plusMonths(1).toString() },
        )
        CalendarMonthGrid(
            shown = shown,
            today = today,
            rangeStart = startLocal,
            rangeEnd = endLocal,
            onPick = { picked ->
                if (endLocal != null) {
                    rangeStart = picked.toString()
                    rangeEnd = null
                } else if (picked < startLocal) {
                    rangeEnd = startLocal.toString()
                    rangeStart = picked.toString()
                } else {
                    rangeEnd = picked.toString()
                }
            },
            onPreviousMonth = { month = shown.minusMonths(1).toString() },
            onNextMonth = { month = shown.plusMonths(1).toString() },
        )
        AppDialogActions(
            confirmLabel = "应用",
            onConfirm = {
                if (endLocal != null) onApply(startLocal.toString(), endLocal.toString())
            },
            dismissLabel = "取消",
            onDismiss = onDismiss,
            confirmEnabled = endLocal != null,
        )
    }
}

@Composable
private fun CalendarMonthHeader(
    shown: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val p = LocalAbPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MonthIconAction(AbIcons.ChevronLeft, "上个月", onPrevious)
        Text(
            "${shown.year}年${shown.monthValue}月",
            style = AbType.body.copy(fontWeight = FontWeight.SemiBold).tabular(),
            color = p.label,
            modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally),
        )
        MonthIconAction(AbIcons.ChevronRight, "下个月", onNext)
    }
}

@Composable
private fun CalendarMonthGrid(
    shown: YearMonth,
    today: LocalDate,
    rangeStart: LocalDate,
    rangeEnd: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onPreviousMonth: (() -> Unit)? = null,
    onNextMonth: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    val latestPrev by rememberUpdatedState(onPreviousMonth)
    val latestNext by rememberUpdatedState(onNextMonth)
    val isDark = p.pageBg.luminance() < 0.1f
    val bandColor = p.accent.copy(alpha = if (isDark) 0.24f else 0.14f)
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (onPreviousMonth != null && onNextMonth != null) {
                    Modifier.pointerInput(reduceMotion) {
                        var totalDrag = 0f
                        val thresholdPx = with(density) { 48.dp.toPx() }
                        val maxVisualPx = with(density) { 32.dp.toPx() }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                scope.launch { dragX.stop() }
                            },
                            onDragCancel = {
                                totalDrag = 0f
                                scope.launch {
                                    if (reduceMotion) dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                                    else dragX.animateTo(0f, AbSpring.navigationFloat(false))
                                }
                            },
                            onDragEnd = {
                                val shift = when {
                                    totalDrag > thresholdPx -> -1
                                    totalDrag < -thresholdPx -> 1
                                    else -> 0
                                }
                                totalDrag = 0f
                                if (shift < 0) {
                                    haptic.tick()
                                    latestPrev?.invoke()
                                } else if (shift > 0) {
                                    haptic.tick()
                                    latestNext?.invoke()
                                }
                                scope.launch {
                                    if (reduceMotion) {
                                        dragX.animateTo(0f, tween(90, easing = AbEasing.EaseOut))
                                    } else if (shift != 0) {
                                        val incoming = with(density) { 24.dp.toPx() } * if (shift < 0) -1f else 1f
                                        dragX.snapTo(incoming)
                                        dragX.animateTo(0f, AbSpring.navigationFloat(false))
                                    } else {
                                        dragX.animateTo(0f, AbSpring.navigationFloat(false))
                                    }
                                }
                            },
                        ) { change, delta ->
                            change.consume()
                            totalDrag += delta
                            if (!reduceMotion) {
                                val visual = (totalDrag * 0.34f).coerceIn(-maxVisualPx, maxVisualPx)
                                scope.launch { dragX.snapTo(visual) }
                            }
                        }
                    }
                } else Modifier,
            )
            .graphicsLayer {
                if (!reduceMotion) translationX = dragX.value
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(
                    weekday,
                    style = AbType.caption1,
                    color = p.secondaryLabel,
                    modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally),
                )
            }
        }
        val offset = shown.atDay(1).dayOfWeek.value - 1
        repeat((offset + shown.lengthOfMonth() + 6) / 7) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val n = week * 7 + column - offset + 1
                    if (n !in 1..shown.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(40.dp))
                    } else {
                        val date = shown.atDay(n)
                        val isStart = date == rangeStart
                        val isEnd = rangeEnd != null && date == rangeEnd
                        val isEndpoint = isStart || isEnd
                        val inRange = rangeEnd != null && rangeStart != rangeEnd && !date.isBefore(rangeStart) && !date.isAfter(rangeEnd)
                        val isToday = date == today

                        val dayInteraction = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = AbSize.minTouch)
                                .abPressFeedback(dayInteraction, pressedScale = 0.92f, pressedAlpha = 0.72f)
                                .clickable(
                                    interactionSource = dayInteraction,
                                    indication = null,
                                ) {
                                    haptic.tick()
                                    onPick(date)
                                }
                                .semantics {
                                    role = Role.Button
                                    this.selected = isEndpoint
                                    contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inRange) {
                                val bandShape = RoundedCornerShape(
                                    topStart = if (isStart || column == 0) 20.dp else 0.dp,
                                    bottomStart = if (isStart || column == 0) 20.dp else 0.dp,
                                    topEnd = if (isEnd || column == 6) 20.dp else 0.dp,
                                    bottomEnd = if (isEnd || column == 6) 20.dp else 0.dp,
                                )
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                        .padding(
                                            start = if (isStart) 4.dp else 0.dp,
                                            end = if (isEnd) 4.dp else 0.dp,
                                        )
                                        .clip(bandShape)
                                        .background(bandColor),
                                )
                            }
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .then(
                                        when {
                                            isEndpoint -> Modifier.background(p.accent)
                                            isToday -> Modifier.border(1.dp, p.accent.copy(alpha = 0.5f), CircleShape)
                                            else -> Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    n.toString(),
                                    style = AbType.body.copy(
                                        fontWeight = if (isEndpoint || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                    ).tabular(),
                                    color = when {
                                        isEndpoint -> p.onAccent
                                        isToday -> p.accent
                                        else -> p.label
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthIconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .sizeIn(minWidth = AbSize.minTouch, minHeight = AbSize.minTouch)
            .abPressFeedback(interaction, pressedScale = 0.92f, pressedAlpha = 0.7f)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.select()
                onClick()
            }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(p.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = p.label)
        }
    }
}

/**
 * iOS 风格双向吸附时间滚轮（免弹出数字键盘手输）：
 * 显示前/中/后 3 档，居中行配轻透胶囊高亮，支持滑动刻度触感与点击微调，并保留无障碍设值语义。
 */
@Composable
private fun LiquidWheelTimePicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
) {
    val p = LocalAbPalette.current
    val pillShape = RoundedCornerShape(AbRadius.chip)
    Box(
        Modifier.width(142.dp).height(108.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(pillShape)
                .background(p.fill),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            TimeWheelColumn(
                count = 24,
                value = hour.coerceIn(0, 23),
                label = "小时",
                onValueChange = onHourChange,
                modifier = Modifier.weight(1f),
            )
            Text(
                ":",
                style = AbType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = p.label,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            TimeWheelColumn(
                count = 60,
                value = minute.coerceIn(0, 59),
                label = "分钟",
                onValueChange = onMinuteChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimeWheelColumn(
    count: Int,
    value: Int,
    label: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val itemHeight = 36.dp
    val baseOffset = remember(count) {
        val mid = Int.MAX_VALUE / 2
        mid - (mid % count)
    }
    val initialIndex = remember(count) { baseOffset + value.coerceIn(0, count - 1) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) initialIndex
            else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) * 0.5f
                items.minByOrNull { abs((it.offset + it.size * 0.5f) - center) }?.index ?: initialIndex
            }
        }
    }

    var programmaticSync by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .drop(1)
            .collect { idx ->
                if (programmaticSync) return@collect
                val picked = ((idx % count) + count) % count
                haptic.tick()
                onValueChange(picked)
            }
    }

    LaunchedEffect(value) {
        val cur = ((centeredIndex % count) + count) % count
        if (!listState.isScrollInProgress && cur != value) {
            val delta = ((value - cur + count + count / 2) % count) - count / 2
            val target = centeredIndex + delta
            programmaticSync = true
            try {
                if (reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
            } finally {
                programmaticSync = false
            }
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight),
        modifier = modifier
            .height(itemHeight * 3)
            .semantics {
                contentDescription = label
                editableText = AnnotatedString(value.toString().padStart(2, '0'))
                setText { incoming ->
                    val parsed = incoming.text.trim().toIntOrNull()
                    if (parsed != null && parsed in 0 until count) {
                        val cur = ((centeredIndex % count) + count) % count
                        val delta = ((parsed - cur + count + count / 2) % count) - count / 2
                        scope.launch { listState.scrollToItem(centeredIndex + delta) }
                        onValueChange(parsed)
                        true
                    } else false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(Int.MAX_VALUE) { index ->
            val number = ((index % count) + count) % count
            val cellInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .abPressFeedback(cellInteraction, pressedScale = 0.94f, pressedAlpha = 0.72f)
                    .clickable(
                        interactionSource = cellInteraction,
                        indication = null,
                    ) {
                        if (index != centeredIndex) {
                            scope.launch {
                                if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
                            }
                        }
                    }
                    .graphicsLayer {
                        val info = listState.layoutInfo
                        val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                        val normalizedDist = if (itemInfo != null && itemInfo.size > 0) {
                            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) * 0.5f
                            val itemCenter = itemInfo.offset + itemInfo.size * 0.5f
                            (abs(itemCenter - viewportCenter) / itemInfo.size.toFloat()).coerceIn(0f, 2f)
                        } else {
                            abs(index - centeredIndex).toFloat().coerceIn(0f, 2f)
                        }
                        val t = (1f - normalizedDist.coerceIn(0f, 1f))
                        val smoothScale = 0.86f + 0.20f * t
                        scaleX = smoothScale
                        scaleY = smoothScale
                        alpha = when {
                            normalizedDist <= 1f -> 0.42f + 0.58f * t
                            else -> (0.42f - 0.26f * (normalizedDist - 1f)).coerceAtLeast(0.16f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString().padStart(2, '0'),
                    style = AbType.headline.copy(fontWeight = FontWeight.Medium).tabular(),
                    color = p.label,
                )
            }
        }
    }
}
