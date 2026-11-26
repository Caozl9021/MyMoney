package io.github.mymoney.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mymoney.data.StatisticsDay
import io.github.mymoney.data.StatisticsGroup
import io.github.mymoney.data.StatisticsPeriod
import io.github.mymoney.ui.design.*
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign

/**
 * 支出 / 收入 / 退款切换时的两段式可打断过渡：先快速收拢回零，再切换展示口径并以物理弹簧长出；
 * 若中途切回原口径则直接由当前进度原路回弹，不闪切也不卡顿。
 */
@Composable
private fun rememberInterruptibleTypeProgress(type: String): Pair<String, Float> {
    val reduceMotion = LocalReduceMotion.current
    var shownType by remember { mutableStateOf(type) }
    val anim = remember { Animatable(1f) }
    val collapseEase = remember { CubicBezierEasing(0.4f, 0f, 0.85f, 1f) }
    LaunchedEffect(type, reduceMotion) {
        if (reduceMotion) {
            shownType = type
            anim.snapTo(1f)
            return@LaunchedEffect
        }
        if (shownType != type) {
            if (anim.value > 0.02f) {
                anim.animateTo(0f, tween(110, easing = collapseEase))
            } else {
                anim.snapTo(0f)
            }
            shownType = type
        }
        if (anim.value < 0.998f) {
            anim.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 420f, visibilityThreshold = 0.002f))
        }
    }
    return shownType to anim.value.coerceIn(0f, 1f)
}

/**
 * 周视图：7 根柱一次排满宽度，不横向滚动。
 *
 * 月视图改用 [StatisticsMonthHeatmap]；这里只承担「一周 7 天」的形态，
 * 所以用 weight 均分而不是固定 48dp × 7（固定宽度加间距会超出卡片内宽，
 * 最后一天被裁掉，这正是之前「7 天显示不全」的原因）。
 */
@Composable
internal fun StatisticsDailyChart(
    days: List<StatisticsDay>,
    type: String,
    selectedDate: LocalDate?,
    progress: Float,
    onSelect: (LocalDate) -> Unit,
) {
    val p = LocalAbPalette.current
    val (activeType, typeProgress) = rememberInterruptibleTypeProgress(type)
    val effectiveProgress = progress * typeProgress
    val max = days.maxOfOrNull { it.amount(activeType) }?.coerceAtLeast(1L) ?: 1L
    // 语义色：支出珊瑚、收入压暗绿、退款青蓝；不再借用错误红/动态 accent。
    val tint = amountTint(p, activeType)
    val hasSelection = selectedDate != null
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AbSpacing.xs
        val cell = (maxWidth - gap * (days.size - 1).coerceAtLeast(0)) / days.size.coerceAtLeast(1)
        val barWidth = (cell * 0.54f).coerceIn(12.dp, 26.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            days.forEach { day ->
                val chosen = day.date == selectedDate
                val amount = day.amount(activeType)
                val barColor by animateColorAsState(
                    if (!hasSelection || chosen) tint else tint.copy(alpha = 0.34f),
                    tween(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut),
                    label = "day-bar-color",
                )
                val trackColor by animateColorAsState(
                    if (chosen) tint.copy(alpha = 0.16f) else p.fill,
                    tween(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut),
                    label = "day-track-color",
                )
                val interaction = remember(day.date) { MutableInteractionSource() }
                val semanticAmount = day.amount(type)
                Column(
                    Modifier.width(cell)
                        .abPressFeedback(interaction, pressedScale = 0.95f, pressedAlpha = 0.78f)
                        .clip(RoundedCornerShape(AbRadius.chip))
                        .clickable(interactionSource = interaction, indication = null) { onSelect(day.date) }
                        .semantics(mergeDescendants = true) {
                            selected = chosen
                            contentDescription = "${day.date.monthValue}月${day.date.dayOfMonth}日，${statisticsShortTypeLabel(type)} ${formatAmount(semanticAmount)}，${day.count(type)} 笔"
                        }
                        .padding(vertical = AbSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
                ) {
                    Canvas(Modifier.width(barWidth).height(124.dp)) {
                        val radius = CornerRadius(size.width / 2f)
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = radius,
                        )
                        if (amount > 0L && effectiveProgress > 0.005f) {
                            val rawHeight = size.height * (amount.toDouble() / max).toFloat() * effectiveProgress
                            val minCap = min(size.width * 0.72f, size.height) * effectiveProgress
                            val height = rawHeight.coerceIn(minCap, size.height)
                            val fillRadius = CornerRadius(min(size.width, height) / 2f)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(0f, size.height - height),
                                size = Size(size.width, height),
                                cornerRadius = fillRadius,
                            )
                        }
                    }
                    Text(
                        "${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = (if (chosen) AbType.caption1.copy(fontWeight = FontWeight.SemiBold) else AbType.caption1).tabular(),
                        color = if (chosen) p.label else p.secondaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * 月视图微环日历（Apple 健身月历风）：每格以进度微环表达当天金额占比，
 * 去掉大面积实底方块压迫感；空白天仅保留淡轨，点选当天高亮圆底。
 */
@Composable
internal fun StatisticsMonthHeatmap(
    days: List<StatisticsDay>,
    type: String,
    selectedDate: LocalDate?,
    progress: Float,
    onSelect: (LocalDate) -> Unit,
) {
    val p = LocalAbPalette.current
    if (days.isEmpty()) return
    val (activeType, typeProgress) = rememberInterruptibleTypeProgress(type)
    val effectiveProgress = progress * typeProgress
    val tint = amountTint(p, activeType)
    val peak = days.maxOfOrNull { it.amount(activeType) } ?: 0L
    val leading = days.first().date.dayOfWeek.value - 1
    val slots: List<LocalDate?> = buildList {
        repeat(leading) { add(null) }
        days.forEach { add(it.date) }
        while (size % 7 != 0) add(null)
    }
    val onChosen = Color(ColorContrast.contrastingForeground(tint.toArgb()))
    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(weekday, style = AbType.caption2, color = p.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val hasSelection = selectedDate != null && days.any { it.date == selectedDate }
        slots.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val day = days.first { it.date == date }
                        val amount = day.amount(activeType)
                        val ratio = if (amount > 0L && peak > 0L) (amount.toDouble() / peak).toFloat().coerceIn(0f, 1f) else 0f
                        val chosen = date == selectedDate
                        val dimFactor = if (hasSelection && !chosen) 0.40f else 1f
                        val label = if (date.dayOfMonth == 1 && days.first().date.month != date.month) "${date.monthValue}/1" else "${date.dayOfMonth}"
                        val dayInteraction = remember(date) { MutableInteractionSource() }
                        val semanticAmount = day.amount(type)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .abPressFeedback(dayInteraction, pressedScale = 0.92f, pressedAlpha = 0.72f)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable(interactionSource = dayInteraction, indication = null) { onSelect(date) }
                                .semantics(mergeDescendants = true) {
                                    selected = chosen
                                    contentDescription = "${date.monthValue}月${date.dayOfMonth}日，${statisticsShortTypeLabel(type)} ${formatAmount(semanticAmount)}，${day.count(type)} 笔"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.fillMaxSize(0.84f)) {
                                val strokePx = 2.6.dp.toPx()
                                val inset = strokePx / 2f
                                val ringSize = Size(size.width - strokePx, size.height - strokePx)
                                val origin = Offset(inset, inset)
                                if (chosen) {
                                    drawCircle(color = tint, radius = size.minDimension / 2f)
                                    drawArc(
                                        color = onChosen.copy(alpha = 0.28f),
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = origin,
                                        size = ringSize,
                                        style = Stroke(strokePx),
                                    )
                                } else {
                                    drawArc(
                                        color = p.fill.copy(alpha = p.fill.alpha * dimFactor),
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = origin,
                                        size = ringSize,
                                        style = Stroke(strokePx),
                                    )
                                    if (amount > 0L && effectiveProgress > 0.005f) {
                                        val sweep = (ratio * 340f).coerceAtLeast(24f) * effectiveProgress
                                        drawArc(
                                            color = tint.copy(alpha = (0.36f + ratio * 0.64f) * dimFactor),
                                            startAngle = -90f,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            topLeft = origin,
                                            size = ringSize,
                                            style = Stroke(strokePx, cap = StrokeCap.Round),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = label,
                                style = (if (label.length > 2) AbType.caption2.copy(fontSize = AbType.caption2.fontSize * 0.88f) else AbType.caption2)
                                    .let { if (chosen || amount > 0L) it.copy(fontWeight = FontWeight.SemiBold) else it }
                                    .tabular(),
                                color = when {
                                    chosen -> onChosen
                                    hasSelection -> p.secondaryLabel
                                    amount > 0L -> p.label
                                    else -> p.secondaryLabel
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class MonthSlotTarget(
    val day: StatisticsDay,
    val centerX: Float,
    val centerY: Float,
    val label: String,
)

private fun morphLerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * 周 ↔ 月过渡共享几何变形视图（0f = 周 7 根柱状图，1f = 月进度微环日历）。
 *
 * 落在目标月中的周日期按星期列一对一收缩/展开为对应微环；跨月未落入目标月的周日期平滑缩淡消失，
 * 其余月日历格子随高度展开柔和浮现。
 */
@Composable
internal fun StatisticsWeekMonthMorphChart(
    weekDays: List<StatisticsDay>,
    monthDays: List<StatisticsDay>,
    type: String,
    morphT: Float,
) {
    if (weekDays.isEmpty() || monthDays.isEmpty()) return
    val p = LocalAbPalette.current
    val tint = amountTint(p, type)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val t = morphT.coerceIn(0f, 1f)
    val weekMax = remember(weekDays, type) { weekDays.maxOfOrNull { it.amount(type) }?.coerceAtLeast(1L) ?: 1L }
    val monthPeak = remember(monthDays, type) { monthDays.maxOfOrNull { it.amount(type) }?.coerceAtLeast(1L) ?: 1L }
    val weekDateSet = remember(weekDays) { weekDays.mapTo(HashSet(weekDays.size)) { it.date } }
    val leading = remember(monthDays) { monthDays.first().date.dayOfWeek.value - 1 }
    val numMonthRows = remember(monthDays, leading) { ((leading + monthDays.size + 6) / 7).coerceAtLeast(1) }
    val weekLabelStyle = remember { AbType.caption1.tabular() }
    val headerStyle = remember { AbType.caption2 }
    val dayRingBaseStyle = remember { AbType.caption2.tabular() }
    val dayRingSmallStyle = remember { AbType.caption2.copy(fontSize = AbType.caption2.fontSize * 0.88f).tabular() }
    val weekLabelHeightPx = remember(textMeasurer, weekLabelStyle) {
        textMeasurer.measure(AnnotatedString("8/28"), style = weekLabelStyle, softWrap = false).size.height.toFloat()
    }
    val headerHeightPx = remember(textMeasurer, headerStyle) {
        textMeasurer.measure(AnnotatedString("一"), style = headerStyle, softWrap = false).size.height.toFloat()
    }
    val weekdayLabels = remember { listOf("一", "二", "三", "四", "五", "六", "日") }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { AbSpacing.xs.toPx() }
        val cellWidth = (maxWidth - AbSpacing.xs * 6) / 7f
        val cellWidthPx = ((maxWidthPx - gapPx * 6f) / 7f).coerceAtLeast(1f)
        val barWidthPx = with(density) { (cellWidth * 0.54f).coerceIn(12.dp, 26.dp).toPx() }
        val barTrackHeightPx = with(density) { 124.dp.toPx() }
        val topPadPx = with(density) { AbSpacing.xs.toPx() }
        val strokePx = with(density) { 2.6.dp.toPx() }
        val ringDiameterPx = cellWidthPx * 0.84f
        val weekTrackCenterY = topPadPx + barTrackHeightPx / 2f
        val weekLabelCenterY = topPadPx + barTrackHeightPx + gapPx + weekLabelHeightPx / 2f
        val weekTotalHeightPx = topPadPx + barTrackHeightPx + gapPx + weekLabelHeightPx + topPadPx
        val monthTotalHeightPx = headerHeightPx + gapPx + numMonthRows * cellWidthPx + (numMonthRows - 1).coerceAtLeast(0) * gapPx
        val animatedHeight = with(density) { morphLerp(weekTotalHeightPx, monthTotalHeightPx, t).toDp() }

        val firstMonth = monthDays.first().date.month
        val monthSlotByDate = remember(monthDays, maxWidthPx, headerHeightPx) {
            buildMap {
                monthDays.forEachIndexed { index, day ->
                    val slot = leading + index
                    val col = slot % 7
                    val row = slot / 7
                    val cx = col * (cellWidthPx + gapPx) + cellWidthPx / 2f
                    val cy = headerHeightPx + gapPx + row * (cellWidthPx + gapPx) + cellWidthPx / 2f
                    val lbl = if (day.date.dayOfMonth == 1 && day.date.month != firstMonth) {
                        "${day.date.monthValue}/1"
                    } else {
                        "${day.date.dayOfMonth}"
                    }
                    put(day.date, MonthSlotTarget(day, cx, cy, lbl))
                }
            }
        }

        Canvas(Modifier.fillMaxWidth().height(animatedHeight)) {
            val headerAlpha = ((t - 0.12f) / 0.65f).coerceIn(0f, 1f)
            if (headerAlpha > 0.01f) {
                val headerY = (1f - headerAlpha) * with(density) { (-6).dp.toPx() }
                weekdayLabels.forEachIndexed { col, weekday ->
                    val cx = col * (cellWidthPx + gapPx) + cellWidthPx / 2f
                    val layout = textMeasurer.measure(
                        AnnotatedString(weekday),
                        style = headerStyle.copy(color = p.secondaryLabel.copy(alpha = p.secondaryLabel.alpha * headerAlpha)),
                        softWrap = false,
                    )
                    drawText(
                        layout,
                        topLeft = Offset(cx - layout.size.width / 2f, headerY + (headerHeightPx - layout.size.height) / 2f),
                    )
                }
            }

            val monthReveal = ((t - 0.16f) / 0.84f).coerceIn(0f, 1f)
            if (monthReveal > 0.01f) {
                val cellScale = morphLerp(0.76f, 1f, monthReveal)
                monthDays.forEach { mDay ->
                    if (mDay.date !in weekDateSet) {
                        val target = monthSlotByDate[mDay.date] ?: return@forEach
                        val amount = mDay.amount(type)
                        val ratio = if (amount > 0L && monthPeak > 0L) (amount.toDouble() / monthPeak).toFloat().coerceIn(0f, 1f) else 0f
                        val r = (ringDiameterPx * cellScale) / 2f
                        val inset = strokePx / 2f
                        val arcSize = Size((r * 2f - strokePx).coerceAtLeast(1f), (r * 2f - strokePx).coerceAtLeast(1f))
                        val arcTopLeft = Offset(target.centerX - r + inset, target.centerY - r + inset)
                        drawArc(
                            color = p.fill.copy(alpha = p.fill.alpha * monthReveal),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(strokePx),
                        )
                        if (amount > 0L) {
                            val sweep = (ratio * 340f).coerceAtLeast(24f) * monthReveal
                            drawArc(
                                color = tint.copy(alpha = (0.36f + ratio * 0.64f) * monthReveal),
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(strokePx, cap = StrokeCap.Round),
                            )
                        }
                        val style = (if (target.label.length > 2) dayRingSmallStyle else dayRingBaseStyle).let {
                            if (amount > 0L) it.copy(fontWeight = FontWeight.SemiBold) else it
                        }
                        val labelColor = (if (amount > 0L) p.label else p.secondaryLabel).let {
                            it.copy(alpha = it.alpha * monthReveal)
                        }
                        val layout = textMeasurer.measure(AnnotatedString(target.label), style = style.copy(color = labelColor), softWrap = false)
                        drawText(layout, topLeft = Offset(target.centerX - layout.size.width / 2f, target.centerY - layout.size.height / 2f))
                    }
                }
            }

            weekDays.forEach { wDay ->
                val weekCol = (wDay.date.dayOfWeek.value - 1).coerceIn(0, 6)
                val weekCenterX = weekCol * (cellWidthPx + gapPx) + cellWidthPx / 2f
                val target = monthSlotByDate[wDay.date]
                val wAmount = wDay.amount(type)

                if (target == null) {
                    val fadeOut = (1f - t * 2.1f).coerceIn(0f, 1f)
                    if (fadeOut > 0.01f) {
                        val scale = morphLerp(0.55f, 1f, fadeOut)
                        val bw = barWidthPx * scale
                        val bh = barTrackHeightPx * scale
                        val topLeft = Offset(weekCenterX - bw / 2f, weekTrackCenterY - bh / 2f)
                        val r = CornerRadius(bw / 2f)
                        drawRoundRect(
                            color = p.fill.copy(alpha = p.fill.alpha * fadeOut),
                            topLeft = topLeft,
                            size = Size(bw, bh),
                            cornerRadius = r,
                        )
                        if (wAmount > 0L) {
                            val rawH = bh * (wAmount.toDouble() / weekMax).toFloat()
                            val minCap = min(bw * 0.72f, bh)
                            val activeH = rawH.coerceIn(minCap, bh)
                            drawRoundRect(
                                color = tint.copy(alpha = fadeOut),
                                topLeft = Offset(topLeft.x, topLeft.y + bh - activeH),
                                size = Size(bw, activeH),
                                cornerRadius = r,
                            )
                        }
                        val lbl = "${wDay.date.monthValue}/${wDay.date.dayOfMonth}"
                        val layout = textMeasurer.measure(
                            AnnotatedString(lbl),
                            style = weekLabelStyle.copy(color = p.secondaryLabel.copy(alpha = p.secondaryLabel.alpha * fadeOut)),
                            softWrap = false,
                        )
                        drawText(layout, topLeft = Offset(weekCenterX - layout.size.width / 2f, weekLabelCenterY - layout.size.height / 2f))
                    }
                } else {
                    val cx = morphLerp(weekCenterX, target.centerX, t)
                    val cy = morphLerp(weekTrackCenterY, target.centerY, t)
                    val boxW = morphLerp(barWidthPx, ringDiameterPx, t)
                    val boxH = morphLerp(barTrackHeightPx, ringDiameterPx, t)
                    val cornerR = min(boxW, boxH) / 2f
                    val topLeft = Offset(cx - boxW / 2f, cy - boxH / 2f)

                    val pillTrackAlpha = (1f - t * 1.45f).coerceIn(0f, 1f)
                    if (pillTrackAlpha > 0.01f) {
                        drawRoundRect(
                            color = p.fill.copy(alpha = p.fill.alpha * pillTrackAlpha),
                            topLeft = topLeft,
                            size = Size(boxW, boxH),
                            cornerRadius = CornerRadius(cornerR),
                        )
                    }
                    val ringTrackAlpha = ((t - 0.12f) / 0.65f).coerceIn(0f, 1f)
                    if (ringTrackAlpha > 0.01f) {
                        val inset = strokePx / 2f
                        drawRoundRect(
                            color = p.fill.copy(alpha = p.fill.alpha * ringTrackAlpha),
                            topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
                            size = Size((boxW - strokePx).coerceAtLeast(1f), (boxH - strokePx).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius((cornerR - inset).coerceAtLeast(1f)),
                            style = Stroke(strokePx),
                        )
                    }

                    val barFillAlpha = (1f - t * 1.65f).coerceIn(0f, 1f)
                    if (wAmount > 0L && barFillAlpha > 0.01f) {
                        val rawH = barTrackHeightPx * (wAmount.toDouble() / weekMax).toFloat()
                        val minCap = min(barWidthPx * 0.72f, barTrackHeightPx)
                        val hWeek = rawH.coerceIn(minCap, barTrackHeightPx)
                        val curBarH = min(boxH, hWeek * morphLerp(1f, ringDiameterPx / barTrackHeightPx, t)).coerceAtLeast(min(boxW, boxH) * 0.68f)
                        drawRoundRect(
                            color = tint.copy(alpha = barFillAlpha),
                            topLeft = Offset(topLeft.x, topLeft.y + boxH - curBarH),
                            size = Size(boxW, curBarH),
                            cornerRadius = CornerRadius(min(boxW, curBarH) / 2f),
                        )
                    }

                    val mAmount = target.day.amount(type)
                    val arcReveal = ((t - 0.16f) / 0.84f).coerceIn(0f, 1f)
                    if (mAmount > 0L && arcReveal > 0.01f) {
                        val mRatio = if (monthPeak > 0L) (mAmount.toDouble() / monthPeak).toFloat().coerceIn(0f, 1f) else 0f
                        val targetSweep = (mRatio * 340f).coerceAtLeast(24f) * arcReveal
                        val inset = strokePx / 2f
                        drawArc(
                            color = tint.copy(alpha = (0.36f + mRatio * 0.64f) * arcReveal),
                            startAngle = -90f,
                            sweepAngle = targetSweep,
                            useCenter = false,
                            topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
                            size = Size((boxW - strokePx).coerceAtLeast(1f), (boxH - strokePx).coerceAtLeast(1f)),
                            style = Stroke(strokePx, cap = StrokeCap.Round),
                        )
                    }

                    val weekLblAlpha = (1f - t * 2.4f).coerceIn(0f, 1f)
                    if (weekLblAlpha > 0.01f) {
                        val lbl = "${wDay.date.monthValue}/${wDay.date.dayOfMonth}"
                        val layout = textMeasurer.measure(
                            AnnotatedString(lbl),
                            style = weekLabelStyle.copy(color = p.secondaryLabel.copy(alpha = p.secondaryLabel.alpha * weekLblAlpha)),
                            softWrap = false,
                        )
                        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, weekLabelCenterY - layout.size.height / 2f))
                    }

                    val ringLblAlpha = ((t - 0.42f) / 0.58f).coerceIn(0f, 1f)
                    if (ringLblAlpha > 0.01f) {
                        val style = (if (target.label.length > 2) dayRingSmallStyle else dayRingBaseStyle).let {
                            if (mAmount > 0L) it.copy(fontWeight = FontWeight.SemiBold) else it
                        }
                        val labelColor = (if (mAmount > 0L) p.label else p.secondaryLabel).let {
                            it.copy(alpha = it.alpha * ringLblAlpha)
                        }
                        val layout = textMeasurer.measure(AnnotatedString(target.label), style = style.copy(color = labelColor), softWrap = false)
                        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
                    }
                }
            }
        }
    }
}

/** 0 = 无记录；1–4 按当日金额占峰值比例分档，避免小金额被大额压成全白。 */
internal fun heatLevel(amount: Long, peak: Long): Int {
    if (amount <= 0L || peak <= 0L) return 0
    return kotlin.math.ceil(4.0 * amount / peak).toInt().coerceIn(1, 4)
}

internal enum class RingTransitionMode { NONE, MORPH, RESWEEP }

internal data class RingArcSlice(
    val name: String,
    val startDeg: Float,
    val sweepDeg: Float,
    val color: Color,
)

internal fun buildRingArcs(groups: List<StatisticsGroup>): List<RingArcSlice> {
    var start = -90f
    return groups.mapIndexed { index, group ->
        val sweep = (group.ratio * 360.0).toFloat().coerceAtLeast(0f)
        RingArcSlice(
            name = group.name,
            startDeg = start,
            sweepDeg = sweep,
            color = swatchFor(group.name, index),
        ).also { start += sweep }
    }
}

/**
 * 构成占比圆环切换策略：
 * - 周 ↔ 月（period 变化）且非空、共享占比 >= 30% 时走同名扇区挤压变形（MORPH）；
 * - 稀疏交集（< 30%）、空态或切换 分类/账户、支出/收入/退款 时走顺时针重扫（RESWEEP）。
 */
internal fun selectRingTransitionMode(
    previousGroups: List<StatisticsGroup>,
    nextGroups: List<StatisticsGroup>,
    previousPeriod: StatisticsPeriod?,
    nextPeriod: StatisticsPeriod?,
    previousType: String,
    nextType: String,
): RingTransitionMode {
    if (previousGroups.isEmpty() || nextGroups.isEmpty()) return RingTransitionMode.RESWEEP
    if (previousPeriod == null || nextPeriod == null || previousPeriod == nextPeriod || previousType != nextType) {
        return RingTransitionMode.RESWEEP
    }
    val prevNames = previousGroups.mapTo(HashSet(previousGroups.size)) { it.name }
    val nextNames = nextGroups.mapTo(HashSet(nextGroups.size)) { it.name }
    val sharedNextRatio = nextGroups.filter { it.name in prevNames }.sumOf { it.ratio }
    val sharedPrevRatio = previousGroups.filter { it.name in nextNames }.sumOf { it.ratio }
    val overlap = minOf(sharedNextRatio, sharedPrevRatio)
    return if (overlap >= 0.30) RingTransitionMode.MORPH else RingTransitionMode.RESWEEP
}

private data class RingSnapshot(
    val groups: List<StatisticsGroup>,
    val arcs: List<RingArcSlice>,
    val totalCents: Long,
    val period: StatisticsPeriod?,
    val type: String,
)

private fun computeVisualRingArcs(
    prevArcs: List<RingArcSlice>,
    targetArcs: List<RingArcSlice>,
    mode: RingTransitionMode,
    t: Float,
): List<RingArcSlice> = when (mode) {
    RingTransitionMode.NONE -> targetArcs
    RingTransitionMode.MORPH -> buildList {
        val prevByName = prevArcs.associateBy { it.name }
        val targetByName = targetArcs.associateBy { it.name }
        prevArcs.forEach { prev ->
            if (prev.name !in targetByName) {
                val collapse = (1f - t * 1.35f).coerceIn(0f, 1f)
                if (collapse > 0.01f) {
                    add(
                        RingArcSlice(
                            name = prev.name,
                            startDeg = prev.startDeg + prev.sweepDeg * (1f - collapse) * 0.5f,
                            sweepDeg = prev.sweepDeg * collapse,
                            color = prev.color.copy(alpha = prev.color.alpha * collapse),
                        ),
                    )
                }
            }
        }
        targetArcs.forEach { target ->
            val prev = prevByName[target.name]
            if (prev != null) {
                add(
                    RingArcSlice(
                        name = target.name,
                        startDeg = morphLerp(prev.startDeg, target.startDeg, t),
                        sweepDeg = morphLerp(prev.sweepDeg, target.sweepDeg, t),
                        color = lerp(prev.color, target.color, t),
                    ),
                )
            } else {
                val expand = ((t - 0.14f) / 0.86f).coerceIn(0f, 1f)
                val alpha = ((t - 0.10f) / 0.55f).coerceIn(0f, 1f)
                if (expand > 0.01f && alpha > 0.01f) {
                    add(
                        RingArcSlice(
                            name = target.name,
                            startDeg = target.startDeg,
                            sweepDeg = target.sweepDeg * expand,
                            color = target.color.copy(alpha = target.color.alpha * alpha),
                        ),
                    )
                }
            }
        }
    }
    RingTransitionMode.RESWEEP -> buildList {
        if (t < 0.24f) {
            val fadeOut = (1f - t / 0.24f).coerceIn(0f, 1f)
            prevArcs.forEach { prev ->
                add(prev.copy(color = prev.color.copy(alpha = prev.color.alpha * fadeOut)))
            }
        }
        val sweepT = ((t - 0.08f) / 0.92f).coerceIn(0f, 1f)
        if (sweepT > 0.005f) {
            val maxAngle = -90f + 360f * sweepT
            targetArcs.forEach { target ->
                val clippedEnd = minOf(target.startDeg + target.sweepDeg, maxAngle)
                val sweep = clippedEnd - target.startDeg
                if (sweep > 0.1f) {
                    add(target.copy(sweepDeg = sweep))
                }
            }
        }
    }
}

@Composable
internal fun StatisticsRing(
    groups: List<StatisticsGroup>,
    type: String,
    selectedName: String?,
    progress: Float,
    period: StatisticsPeriod? = null,
    onSelect: (String) -> Unit,
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val total = remember(groups) { groups.sumOf { it.cents } }
    val targetArcs = remember(groups) { buildRingArcs(groups) }
    var settledSnapshot by remember { mutableStateOf<RingSnapshot?>(null) }
    var animFromSnapshot by remember { mutableStateOf<RingSnapshot?>(null) }
    var animMode by remember { mutableStateOf(RingTransitionMode.NONE) }
    var animGeneration by remember { mutableIntStateOf(0) }
    var animStartedGen by remember { mutableIntStateOf(0) }
    val animT = remember { Animatable(1f) }

    val currentSnapshot = remember(groups, targetArcs, total, period, type) {
        RingSnapshot(groups, targetArcs, total, period, type)
    }

    // 同步检测数据变更：保证首帧立即进入 t = 0 的起始态，彻底消除 1 帧闪烁并支持中途无缝打断重接。
    val prevSettled = settledSnapshot
    if (prevSettled == null) {
        settledSnapshot = currentSnapshot
    } else if (prevSettled.groups != groups || prevSettled.type != type || prevSettled.period != period) {
        if (reduceMotion) {
            settledSnapshot = currentSnapshot
            animFromSnapshot = null
            animMode = RingTransitionMode.NONE
            animStartedGen = animGeneration
        } else {
            val prevFrom = animFromSnapshot
            val curT = if (animStartedGen == animGeneration) animT.value.coerceIn(0f, 1f) else 0f
            val visualFrom = if (prevFrom != null && animMode != RingTransitionMode.NONE && curT < 0.998f) {
                val midArcs = computeVisualRingArcs(prevFrom.arcs, prevSettled.arcs, animMode, curT)
                val midTotal = (prevFrom.totalCents + ((prevSettled.totalCents - prevFrom.totalCents) * curT).roundToLong()).coerceAtLeast(0L)
                RingSnapshot(prevSettled.groups, midArcs, midTotal, prevSettled.period, prevSettled.type)
            } else {
                prevSettled
            }
            animMode = selectRingTransitionMode(
                previousGroups = prevSettled.groups,
                nextGroups = groups,
                previousPeriod = prevSettled.period,
                nextPeriod = period,
                previousType = prevSettled.type,
                nextType = type,
            )
            animFromSnapshot = visualFrom
            settledSnapshot = currentSnapshot
            animGeneration++
        }
    }

    LaunchedEffect(animGeneration, reduceMotion) {
        if (animGeneration == 0 || animFromSnapshot == null || reduceMotion) {
            animT.snapTo(1f)
            animStartedGen = animGeneration
            animFromSnapshot = null
            animMode = RingTransitionMode.NONE
            return@LaunchedEffect
        }
        val mode = animMode
        animT.snapTo(0f)
        animStartedGen = animGeneration
        val duration = if (mode == RingTransitionMode.MORPH) 380 else 300
        val easing = if (mode == RingTransitionMode.MORPH) AbEasing.EaseInOut else AbEasing.EaseOut
        animT.animateTo(1f, tween(duration, easing = easing))
        if (animStartedGen == animGeneration) {
            animFromSnapshot = null
            animMode = RingTransitionMode.NONE
        }
    }

    val strokeWidth = 24.dp
    val edgeInset = 3.dp
    val selectedExtraWidth = 4.dp
    val fromSnap = animFromSnapshot
    val t = if (fromSnap == null) 1f else if (animStartedGen == animGeneration) animT.value.coerceIn(0f, 1f) else 0f
    val activeMode = if (fromSnap != null && t < 0.999f) animMode else RingTransitionMode.NONE

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(184.dp)
                .semantics { contentDescription = "${statisticsShortTypeLabel(type)}占比环图，合计 ${formatAmount(total)}，${groups.size} 项；各项金额和百分比见下方可点击列表" }
                .pointerInput(groups, selectedName) {
                    detectTapGestures { position ->
                        val dx = position.x - size.width / 2f
                        val dy = position.y - size.height / 2f
                        val distance = sqrt(dx * dx + dy * dy)
                        if (total > 0L) {
                            val fraction = ((atan2(dy, dx).toDouble() * 180 / Math.PI + 450) % 360) / 360
                            var edge = 0.0
                            val found = groups.firstOrNull { edge += it.ratio; fraction < edge }
                                ?: groups.lastOrNull()
                            found?.let { group ->
                                // Same centerline radius and stroke widths used by drawArc below.
                                val width = strokeWidth.toPx()
                                val radius = min(size.width, size.height) / 2f - width / 2f - edgeInset.toPx()
                                val halfStroke = (width + if (group.name == selectedName) selectedExtraWidth.toPx() else 0f) / 2f
                                if (distance >= radius - halfStroke && distance <= radius + halfStroke) {
                                    onSelect(group.name)
                                }
                            }
                        }
                    }
                },
        ) {
            val width = strokeWidth.toPx()
            val inset = width / 2f + edgeInset.toPx()
            val diameter = size.minDimension - 2 * inset
            val origin = Offset(inset, inset)
            val bounds = Size(diameter, diameter)
            drawArc(p.fill, 0f, 360f, false, origin, bounds, style = Stroke(width))

            val drawnArcs = if (activeMode == RingTransitionMode.NONE) targetArcs
            else computeVisualRingArcs(fromSnap!!.arcs, targetArcs, activeMode, t)
            val showGap = maxOf(fromSnap?.arcs?.size ?: 0, targetArcs.size) > 1
            drawnArcs.forEach { slice ->
                val selectedWidth = if (activeMode == RingTransitionMode.NONE && slice.name == selectedName) {
                    width + selectedExtraWidth.toPx()
                } else width
                val gap = if (showGap) minOf(1.5f, slice.sweepDeg * .1f) else 0f
                val sweep = (slice.sweepDeg - gap) * progress
                if (sweep > 0.1f) {
                    drawArc(slice.color, slice.startDeg + gap / 2f, sweep, false, origin, bounds, style = Stroke(selectedWidth))
                }
            }
        }
        // 中心默认显示当前总额与项数；切换期/类型时平滑滚变中心金额；点选某扇区时直接展示该项名称、金额与占比。
        val selectedGroup = groups.firstOrNull { it.name == selectedName }
        val fromTotal = fromSnap?.totalCents ?: total
        val rolledTotal = if (activeMode != RingTransitionMode.NONE) {
            (fromTotal + ((total - fromTotal) * t).roundToLong()).coerceAtLeast(0L)
        } else total
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = selectedGroup?.name ?: statisticsShortTypeLabel(type),
                style = AbType.caption1,
                color = p.secondaryLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            AmountText(
                value = when {
                    selectedGroup != null -> formatAmount(selectedGroup.cents)
                    total <= 0L && (activeMode == RingTransitionMode.NONE || t >= 0.85f || fromTotal <= 0L) -> "—"
                    else -> formatAmount(rolledTotal)
                },
                style = AbType.subhead.copy(fontWeight = FontWeight.SemiBold).tabular(),
                color = p.label,
            )
            Text(
                text = when {
                    selectedGroup != null -> "${statisticsPercent(selectedGroup.ratio)} · ${selectedGroup.count} 笔"
                    groups.isEmpty() -> "暂无记录"
                    else -> "${groups.size} 项"
                },
                style = AbType.caption2.tabular(),
                color = p.secondaryLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun StatisticsGroupRow(index: Int, group: StatisticsGroup, isSelected: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember(group.name) { MutableInteractionSource() }
    val borderColor by animateColorAsState(
        if (isSelected) p.accent else p.cardBg,
        tween(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut),
        label = "stats-group-border",
    )
    InsetGroup {
        Column(
            Modifier.fillMaxWidth()
                .abPressFeedback(interaction, pressedScale = 0.985f, tintOverlay = true)
                .border(if (isSelected) 2.dp else 0.dp, borderColor, RoundedCornerShape(AbRadius.group))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics(mergeDescendants = true) { selected = isSelected }
                .padding(AbSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).background(AbAccentSwatches[(index - 1) % AbAccentSwatches.size], RoundedCornerShape(3.dp)))
                Text("$index. ${group.name}${if (isSelected) " · 已选" else ""}", style = AbType.subhead, color = p.label,
                    modifier = Modifier.weight(1f))
            }
            Text("${formatAmount(group.cents)} · ${group.count} 笔 · ${statisticsPercent(group.ratio)}",
                style = AbType.footnote.tabular(), color = p.secondaryLabel)
        }
    }
}
