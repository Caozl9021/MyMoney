package com.example.autobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.autobook.data.BillEntity
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PageScaffold
import com.example.autobook.ui.design.Segmented
import com.example.autobook.ui.design.formatAmount
import com.example.autobook.ui.design.rememberAbHaptic
import com.example.autobook.ui.design.swatchFor
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 统计：可回看最近 12 个月，分类/账户两组占比 + 结余。 */
@Composable
fun StatsScreen(bills: List<BillEntity>) {
    val p = LocalAbPalette.current
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember(context) { com.example.autobook.data.AppDb.get(context) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(db, owner) {
        val observer = object : androidx.room.InvalidationTracker.Observer("bills") {
            override fun onInvalidated(tables: Set<String>) { scope.launch { revision++ } }
        }
        val resume = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++
        }
        db.invalidationTracker.addObserver(observer)
        owner.lifecycle.addObserver(resume)
        onDispose { db.invalidationTracker.removeObserver(observer); owner.lifecycle.removeObserver(resume) }
    }
    val thisMonth = YearMonth.now()

    // 0 = 本月，1 = 上月 …… 11
    var back by rememberSaveable { mutableIntStateOf(0) }
    val target = thisMonth.minusMonths(back.toLong())

    val zone = ZoneId.systemDefault()
    val from = remember(target, zone) { target.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val to = remember(target, zone) {
        target.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    var month by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<BillEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(bills, from, to, revision) {
        month = emptyList()
        month = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.autobook.data.AppDb.get(context).bills().range(from, to) }
    }

    val expense = month.filter { it.type == "EXPENSE" }
    // 转账既不是支出也不是收入（转给「储蓄卡」这种账户互转，计入任一边都会重复）。
    // 之前这里只 filter 两种，转账在统计页彻底消失；现在单列一块金额 + 笔数。
    val transfers = month.filter { it.type == "TRANSFER" }
    val totals = ledgerTotals(month)
    val expenseTotal = totals.expense
    val incomeTotal = totals.income
    val transferTotal = transfers.sumOf { it.priceCents }

    var mode by rememberSaveable { mutableIntStateOf(0) }
    val byCategory = remember(expense) {
        expense.groupBy { it.category ?: "未分类" }
            .mapValues { e -> e.value.sumOf { it.priceCents } }
            .toList().sortedByDescending { it.second }
    }
    val byAccount = remember(expense) {
        expense.groupBy { it.account ?: "未知账户" }
            .mapValues { e -> e.value.sumOf { it.priceCents } }
            .toList().sortedByDescending { it.second }
    }
    val rows = if (mode == 0) byCategory else byAccount

    PageScaffold(
        title = "统计",
        listState = listState,
        subtitle = "可回看最近 12 个月",
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = AbSpacing.xs,
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.l),
        ) {
            item(key = "month") {
                MonthSwitcher(
                    label = if (back == 0) "${target.year}年${target.monthValue}月 · 本月"
                    else "${target.year}年${target.monthValue}月",
                    canGoBack = back < 11,
                    canGoForward = back > 0,
                    onPrev = { back = (back + 1).coerceAtMost(11) },
                    onNext = { back = (back - 1).coerceAtLeast(0) },
                    onToday = { back = 0 },
                )
            }

            item(key = "totals") {
                InsetGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(AbSpacing.xl),
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.l),
                    ) {
                        TotalCell("支出", formatAmount(expenseTotal), p.label, Modifier.weight(1f))
                        TotalCell("收入", formatAmount(incomeTotal), p.green, Modifier.weight(1f))
                        val net = totals.balance
                        TotalCell(
                            "结余",
                            if (net < 0) "−" + formatAmount(-net) else formatAmount(net),
                            if (net < 0) p.red else p.label,
                            Modifier.weight(1f),
                        )
                    }
                }
            }

            item(key = "refunds") {
                InsetGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl, vertical = AbSpacing.m),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("退款（抵减支出，不计收入）", style = AbType.caption1, color = p.secondaryLabel)
                        Text(formatAmount(totals.refund), style = AbType.amountRow, color = p.label)
                    }
                }
            }

            if (transfers.isNotEmpty()) {
                // 转账单独一行：不进支出也不进收入，但必须让用户看见它没被丢掉。
                item(key = "transfers") {
                    InsetGroup {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl, vertical = AbSpacing.m),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("转账", style = AbType.body, color = p.label)
                                Text(
                                    "不计入收支",
                                    style = AbType.caption1,
                                    color = p.tertiaryLabel,
                                )
                            }
                            Text(
                                "${transfers.size} 笔 · ${formatAmount(transferTotal)}",
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                            )
                        }
                    }
                }
            }

            item(key = "mode") {
                Segmented(
                    options = listOf("按分类", "按账户"),
                    selected = mode,
                    onSelect = { mode = it },
                )
            }

            item(key = "bars") {
                InsetGroup {
                    Column(
                        Modifier.fillMaxWidth().padding(AbSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(AbSpacing.l),
                    ) {
                        if (rows.isEmpty() || expenseTotal <= 0) {
                            Text(
                                "这个月没有支出记录。",
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                            )
                        } else {
                            rows.forEach { (name, cents) ->
                                BarRow(name, cents, expenseTotal, swatchFor(name))
                            }
                        }
                    }
                }
            }

            item(key = "hint") {
                Text(
                    "分类/账户取自本机清单；名字要与 iCost 里的一致，导出后才能正确归类。",
                    style = AbType.caption1,
                    color = p.tertiaryLabel,
                    modifier = Modifier.padding(start = AbSpacing.xs),
                )
            }
        }
    }
}

/** 月份切换器：左右箭头翻月，中间点一下回到本月。iOS 里就是这种轻量导航。 */
@Composable
private fun MonthSwitcher(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    fun tap(action: () -> Unit): () -> Unit = {
        haptic.tick()
        action()
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowCell(AbIcons.ChevronRight, mirrored = true, enabled = canGoBack, onClick = tap(onPrev))
        Text(
            label,
            style = AbType.headline,
            color = p.label,
            modifier = Modifier
                .clip(RoundedCornerShape(AbRadius.capsule))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = tap(onToday),
                )
                .padding(horizontal = AbSpacing.l, vertical = 6.dp),
        )
        ArrowCell(AbIcons.ChevronRight, mirrored = false, enabled = canGoForward, onClick = tap(onNext))
    }
}

@Composable
private fun ArrowCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    mirrored: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAbPalette.current
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(AbRadius.capsule))
            .background(if (enabled) p.fill else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = if (mirrored) "上个月" else "下个月",
            modifier = Modifier
                .size(18.dp)
                .then(
                    if (mirrored) Modifier.graphicsLayer(scaleX = -1f) else Modifier,
                ),
            tint = if (enabled) p.label else p.quaternaryLabel,
        )
    }
}

@Composable
private fun TotalCell(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val p = LocalAbPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = AbType.caption1, color = p.secondaryLabel)
        Text(value, style = AbType.amountTitle, color = tint, maxLines = 1)
    }
}

/** 条形占比：左名字、右金额+百分比、下面一条进度。 */
@Composable
private fun BarRow(name: String, cents: Long, total: Long, tint: Color) {
    val p = LocalAbPalette.current
    val ratio = if (total <= 0L) 0f else (cents.toFloat() / total).coerceIn(0f, 1f)
    val reduceMotion = com.example.autobook.ui.design.LocalReduceMotion.current
    val progress = remember(name) { androidx.compose.animation.core.Animatable(if (reduceMotion) ratio else 0f) }
    androidx.compose.runtime.LaunchedEffect(ratio, reduceMotion) {
        if (reduceMotion) progress.snapTo(ratio)
        else progress.animateTo(ratio, androidx.compose.animation.core.tween(300))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = AbType.subhead, color = p.label, maxLines = 1)
            Text(
                "${formatAmount(cents)}  ·  ${(ratio * 100).toInt()}%",
                style = AbType.caption1,
                color = p.secondaryLabel,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(AbRadius.capsule))
                .background(p.fill),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(AbRadius.capsule))
                    .background(tint),
            )
        }
    }
}
