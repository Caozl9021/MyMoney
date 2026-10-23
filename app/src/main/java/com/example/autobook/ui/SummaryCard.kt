package com.example.autobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.InvalidationTracker
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.autobook.data.AppDb
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AmountText
import java.time.YearMonth
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.autobook.data.BillEntity
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.sectionHeader
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.formatAmount
import java.time.ZoneId

/**
 * 本月概览卡。实色卡面（不是玻璃）—— 玻璃只给导航层用，内容卡片必须能读。
 */
@Composable
fun MonthlySummaryCard(bills: List<BillEntity>) {
    val p = LocalAbPalette.current
    val context = LocalContext.current.applicationContext
    val db = remember(context) { AppDb.get(context) }
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(db, lifecycle) {
        val observer = object : InvalidationTracker.Observer("bills") {
            override fun onInvalidated(tables: Set<String>) { scope.launch { revision++ } }
        }
        val resume = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        db.invalidationTracker.addObserver(observer)
        lifecycle.lifecycle.addObserver(resume)
        onDispose {
            db.invalidationTracker.removeObserver(observer)
            lifecycle.lifecycle.removeObserver(resume)
        }
    }
    var back by rememberSaveable { mutableIntStateOf(0) }
    val target = YearMonth.now().minusMonths(back.toLong())
    val zone = ZoneId.systemDefault()
    val from = target.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = target.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    // Query the complete month: the shell's recent 500 rows cannot support historical totals.
    val month by produceState<List<BillEntity>?>(null, target, revision) {
        value = null
        value = db.bills().range(from, to)
    }
    val totals = ledgerTotals(month.orEmpty())
    val expense = totals.expense
    val income = totals.income
    val balance = totals.balance

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AbRadius.card))
            .background(p.cardBg)
            .padding(horizontal = AbSpacing.xl, vertical = AbSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AbIcons.ChevronLeft, contentDescription = "上个月", tint = p.accent,
                modifier = Modifier.size(48.dp).clickable { back++ }.padding(12.dp))
            Text("${target.year}年${target.monthValue}月支出", style = AbType.sectionHeader,
                color = p.secondaryLabel, modifier = Modifier.weight(1f)
                    .clickable { back = 0 }.semantics { contentDescription = "${target.year}年${target.monthValue}月支出，点击回到本月" })
            Icon(AbIcons.ChevronRight, contentDescription = "下个月", tint = if (back > 0) p.accent else p.tertiaryLabel,
                modifier = Modifier.size(48.dp).clickable(enabled = back > 0) { back-- }.padding(12.dp))
        }
        AmountText(if (month == null) "…" else formatAmount(expense), AbType.amountHero, p.label)

        Box(Modifier.fillMaxWidth().padding(vertical = AbSpacing.xs)) {
            HorizontalDivider(thickness = 0.5.dp, color = p.separator)
        }

        Text(if (month == null) "正在读取账单" else "${target.monthValue}月 ${month.orEmpty().size} 笔", style = AbType.caption1, color = p.secondaryLabel)

        // FlowRow measures intrinsic widths, which BoxWithConstraints-based amounts cannot supply.
        // Allocate explicit cells from the parent's measured width before composing amount content.
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = AbSpacing.l
            val minimumCell = 88.dp * fontScale.coerceAtLeast(1f)
            val columns = when {
                maxWidth >= minimumCell * 3 + gap * 2 -> 3
                maxWidth >= minimumCell * 2 + gap -> 2
                else -> 1
            }
            val cellWidth = (maxWidth - gap * (columns - 1)) / columns
            val stats = listOf(
                Triple("收入", formatAmount(income), p.green),
                Triple("结余", if (balance < 0) "−" + formatAmount(-balance) else formatAmount(balance),
                    if (balance < 0) p.red else p.label),
                Triple("退款", formatAmount(totals.refund), p.label),
            )
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                stats.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { (label, value, tint) ->
                            SummaryStat(label, value, tint, Modifier.width(cellWidth))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val p = LocalAbPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = AbType.caption1, color = p.secondaryLabel)
        AmountText(value, AbType.amountRow, tint)
    }
}
