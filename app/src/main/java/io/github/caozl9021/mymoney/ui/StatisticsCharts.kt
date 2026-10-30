package io.github.caozl9021.mymoney.ui

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
import io.github.caozl9021.mymoney.data.StatisticsDay
import io.github.caozl9021.mymoney.data.StatisticsGroup
import io.github.caozl9021.mymoney.ui.design.*
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
    // 「最高 …」由调用方放在标题同一行，这里不再重复一行文字。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AbSpacing.xs
        val cell = (maxWidth - gap * (days.size - 1).coerceAtLeast(0)) / days.size.coerceAtLeast(1)
        val barWidth = (cell * 0.52f).coerceIn(10.dp, 28.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            days.forEach { day ->
                val chosen = day.date == selectedDate
                val amount = day.amount(type)
                Column(
                    Modifier.width(cell)
                        .background(if (chosen) p.fill else Color.Transparent, RoundedCornerShape(AbRadius.chip))
                        .clickable { onSelect(day.date) }
                        .semantics(mergeDescendants = true) {
                            selected = chosen
                            contentDescription = "${day.date.monthValue}月${day.date.dayOfMonth}日，${statisticsShortTypeLabel(type)} ${formatAmount(amount)}，${day.count(type)} 笔"
                        }
                        .padding(vertical = AbSpacing.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
                ) {
                    Canvas(Modifier.width(barWidth).height(136.dp)) {
                        // 基线比数据柱弱：细线 + 半透明分隔色，不与柱子争视觉。
                        drawLine(p.separator.copy(alpha = 0.55f), Offset(0f, size.height), Offset(size.width, size.height), 0.75.dp.toPx())
                        if (amount > 0L) {
                            val height = (size.height * (amount.toDouble() / max).toFloat() * progress).coerceAtLeast(0f)
                            drawRoundRect(tint, topLeft = Offset(0f, size.height - height),
                                size = Size(size.width, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                        }
                    }
                    Text("${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = AbType.caption1, color = if (chosen) p.label else p.secondaryLabel)
                    // 选中标记占一行固定高度，切换日期时图表不会上下跳。
                    Text(if (chosen) "已选" else " ", style = AbType.caption2,
                        color = if (chosen) p.accent else Color.Transparent)
                }
            }
        }
    }
}

/**
 * 月视图热力图：日历网格，每格颜色深浅表示当天金额。
 *
 * 配色跟随当前类型（支出/收入/退款）的语义色，与本页柱图、环图同源；
 * 空白天与隔月占位用卡片填充色，保证网格形状稳定。
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
    val levelAlpha = listOf(0f, 0.22f, 0.44f, 0.68f, 0.94f)
    // 日期数字的前景色按**混色后**的格子底色算对比度：alpha 叠在卡片底上，
    // 直接拿语义色原色算会低估亮度、把中档格子错判成可以放白字。
    val onStrong = Color(ColorContrast.contrastingForeground(lerp(p.cardBg, tint, 0.94f).toArgb()))
    val onMedium = Color(ColorContrast.contrastingForeground(lerp(p.cardBg, tint, 0.68f).toArgb()))
    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(weekday, style = AbType.caption2, color = p.tertiaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        slots.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val day = days.first { it.date == date }
                        val amount = day.amount(type)
                        val level = heatLevel(amount, peak)
                        val chosen = date == selectedDate
                        val shape = RoundedCornerShape(AbRadius.chip)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(shape)
                                .background(if (level == 0) p.fill else tint.copy(alpha = levelAlpha[level] * progress))
                                .then(if (chosen) Modifier.border(2.dp, p.label, shape) else Modifier)
                                .clickable { onSelect(date) }
                                .semantics(mergeDescendants = true) {
                                    selected = chosen
                                    contentDescription = "${date.monthValue}月${date.dayOfMonth}日，${statisticsShortTypeLabel(type)} ${formatAmount(amount)}，${day.count(type)} 笔"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (date.dayOfMonth == 1 && days.first().date.month != date.month) "${date.monthValue}/1" else "${date.dayOfMonth}",
                                style = AbType.caption2,
                                color = when {
                                    level >= 4 -> onStrong
                                    level == 3 -> onMedium
                                    else -> p.label
                                },
                            )
                        }
                    }
                }
            }
        }
        // 图例：浅→深，说明颜色只表示金额大小，具体数字在点选后的文字里。
        Row(Modifier.fillMaxWidth().padding(top = AbSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
            Text("少", style = AbType.caption2, color = p.tertiaryLabel)
            (0..4).forEach { level ->
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (level == 0) p.fill else tint.copy(alpha = levelAlpha[level])))
            }
            Text("多", style = AbType.caption2, color = p.tertiaryLabel)
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
    val colors = groups.indices.map { AbAccentSwatches[it % AbAccentSwatches.size] }
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
        // 中心给「当前类型 + 当前完整总额」，类别数量降为弱副信息；
        // 108dp 宽度小于环内径，不会遮住可点击的环带。
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(108.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(statisticsShortTypeLabel(type), style = AbType.caption1, color = p.secondaryLabel)
            Text(
                if (total <= 0L) "—" else formatAmount(total),
                style = AbType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = p.label,
                maxLines = 1,
            )
            Text(if (groups.isEmpty()) "暂无记录" else "${groups.size} 项", style = AbType.caption2, color = p.tertiaryLabel)
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
