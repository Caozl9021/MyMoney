package io.github.mymoney.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import io.github.mymoney.ui.design.*
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign

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
    val max = days.maxOfOrNull { it.amount(type) }?.coerceAtLeast(1L) ?: 1L
    // 语义色：支出珊瑚、收入压暗绿、退款青蓝；不再借用错误红/动态 accent。
    val tint = amountTint(p, type)
    val hasSelection = selectedDate != null
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AbSpacing.xs
        val cell = (maxWidth - gap * (days.size - 1).coerceAtLeast(0)) / days.size.coerceAtLeast(1)
        val barWidth = (cell * 0.54f).coerceIn(12.dp, 26.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            days.forEach { day ->
                val chosen = day.date == selectedDate
                val amount = day.amount(type)
                val barColor = if (!hasSelection || chosen) tint else tint.copy(alpha = 0.34f)
                val trackColor = if (chosen) tint.copy(alpha = 0.16f) else p.fill
                Column(
                    Modifier.width(cell)
                        .clip(RoundedCornerShape(AbRadius.chip))
                        .clickable { onSelect(day.date) }
                        .semantics(mergeDescendants = true) {
                            selected = chosen
                            contentDescription = "${day.date.monthValue}月${day.date.dayOfMonth}日，${statisticsShortTypeLabel(type)} ${formatAmount(amount)}，${day.count(type)} 笔"
                        }
                        .padding(vertical = AbSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
                ) {
                    Canvas(Modifier.width(barWidth).height(124.dp)) {
                        val radius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = radius,
                        )
                        if (amount > 0L && progress > 0f) {
                            val rawHeight = size.height * (amount.toDouble() / max).toFloat() * progress
                            val minCap = min(size.width * 0.72f, size.height) * progress
                            val height = rawHeight.coerceIn(minCap, size.height)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(0f, size.height - height),
                                size = Size(size.width, height),
                                cornerRadius = radius,
                            )
                        }
                    }
                    Text(
                        "${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = if (chosen) AbType.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AbType.caption1,
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
    val tint = amountTint(p, type)
    val peak = days.maxOfOrNull { it.amount(type) } ?: 0L
    if (days.isEmpty()) return
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
                        val amount = day.amount(type)
                        val ratio = if (amount > 0L && peak > 0L) (amount.toDouble() / peak).toFloat().coerceIn(0f, 1f) else 0f
                        val chosen = date == selectedDate
                        val dimFactor = if (hasSelection && !chosen) 0.40f else 1f
                        val label = if (date.dayOfMonth == 1 && days.first().date.month != date.month) "${date.monthValue}/1" else "${date.dayOfMonth}"
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable { onSelect(date) }
                                .semantics(mergeDescendants = true) {
                                    selected = chosen
                                    contentDescription = "${date.monthValue}月${date.dayOfMonth}日，${statisticsShortTypeLabel(type)} ${formatAmount(amount)}，${day.count(type)} 笔"
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
                                    if (amount > 0L && progress > 0f) {
                                        val sweep = (ratio * 340f).coerceAtLeast(24f) * progress
                                        drawArc(
                                            color = tint.copy(alpha = (0.36f + ratio * 0.64f) * dimFactor),
                                            startAngle = -90f,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            topLeft = origin,
                                            size = ringSize,
                                            style = Stroke(strokePx, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = label,
                                style = (if (label.length > 2) AbType.caption2.copy(fontSize = AbType.caption2.fontSize * 0.88f) else AbType.caption2)
                                    .let { if (chosen || amount > 0L) it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else it },
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

/** 0 = 无记录；1–4 按当日金额占峰值比例分档，避免小金额被大额压成全白。 */
internal fun heatLevel(amount: Long, peak: Long): Int {
    if (amount <= 0L || peak <= 0L) return 0
    return kotlin.math.ceil(4.0 * amount / peak).toInt().coerceIn(1, 4)
}

@Composable
internal fun StatisticsRing(
    groups: List<StatisticsGroup>,
    type: String,
    selectedName: String?,
    progress: Float,
    onSelect: (String) -> Unit,
) {
    val p = LocalAbPalette.current
    val colors = groups.mapIndexed { index, group -> swatchFor(group.name, index) }
    val total = groups.sumOf { it.cents }
    val strokeWidth = 24.dp
    val edgeInset = 3.dp
    val selectedExtraWidth = 4.dp
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
            var start = -90f
            groups.forEachIndexed { index, group ->
                val sweep = (group.ratio * 360).toFloat()
                val selectedWidth = if (group.name == selectedName) width + selectedExtraWidth.toPx() else width
                val gap = if (groups.size > 1) minOf(1.5f, sweep * .1f) else 0f
                drawArc(colors[index], start + gap / 2f, (sweep - gap) * progress, false,
                    origin, bounds, style = Stroke(selectedWidth))
                start += sweep
            }
        }
        // 中心默认显示当前总额与项数；点选某扇区时直接展示该项名称、金额与占比。
        val selectedGroup = groups.firstOrNull { it.name == selectedName }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = selectedGroup?.name ?: statisticsShortTypeLabel(type),
                style = AbType.caption1,
                color = p.secondaryLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    selectedGroup != null -> formatAmount(selectedGroup.cents)
                    total <= 0L -> "—"
                    else -> formatAmount(total)
                },
                style = AbType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = p.label,
                maxLines = 1,
            )
            Text(
                text = when {
                    selectedGroup != null -> "${statisticsPercent(selectedGroup.ratio)} · ${selectedGroup.count} 笔"
                    groups.isEmpty() -> "暂无记录"
                    else -> "${groups.size} 项"
                },
                style = AbType.caption2,
                color = p.secondaryLabel,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun StatisticsGroupRow(index: Int, group: StatisticsGroup, isSelected: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    InsetGroup {
        Column(
            Modifier.fillMaxWidth()
                .border(if (isSelected) 2.dp else 0.dp, if (isSelected) p.accent else p.cardBg, RoundedCornerShape(AbRadius.group))
                .clickable(onClick = onClick)
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
                style = AbType.footnote, color = p.secondaryLabel)
        }
    }
}
