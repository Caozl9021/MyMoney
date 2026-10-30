package com.example.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mymoney.data.*
import com.example.mymoney.ui.design.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AccountBalanceCard(account: AssetAccount, balance: Long, hidden: Boolean,
    onMore: () -> Unit, onEditBalance: () -> Unit) {
    val p = LocalAbPalette.current
    InsetGroup {
        Column(Modifier.fillMaxWidth().padding(AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                AccountAvatar(account.name, size = 40.dp)
                Text(account.name, style = AbType.title2, color = p.label, modifier = Modifier.weight(1f))
                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 60.dp).clickable(onClick = onMore)
                    .semantics { contentDescription = "更多账户操作" }, contentAlignment = Alignment.Center) {
                    Text("更多", style = AbType.footnote, color = p.secondaryLabel,
                        modifier = Modifier.clip(RoundedCornerShape(AbRadius.capsule)).background(p.fill)
                            .padding(horizontal = AbSpacing.m, vertical = AbSpacing.xs))
                }
            }
            Text("账户余额（CNY）", style = AbType.footnote, color = p.secondaryLabel)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AmountText(if (!account.initialized) "未设置" else if (hidden) "••••" else assetMoney(balance),
                    AbType.amountHero, p.label, Modifier.weight(1f))
                Box(Modifier.size(48.dp).clickable(onClick = onEditBalance)
                    .semantics { contentDescription = if (account.initialized) "调整余额" else "设置当前余额" },
                    contentAlignment = Alignment.Center) { Icon(AbIcons.Pencil, null, Modifier.size(20.dp), tint = p.secondaryLabel) }
            }
        }
    }
}

/** Header and every row are separate lazy items, painted as a single continuous period card. */
internal fun LazyListScope.accountPeriodCards(periods: List<AccountHistoryPeriod>, expanded: List<String>,
    hidden: Boolean, zone: ZoneId, currentMonth: YearMonth, onToggle: (String) -> Unit,
    reversedIds: Set<String>, onBill: (Long) -> Unit, onReverse: (AssetEntry) -> Unit) {
    periods.forEach { group ->
        val period = group.period
        val key = period.month.toString()
        val open = key in expanded
        item("account-period-$key") {
            val p = LocalAbPalette.current
            val endMonth = if (period.day == 1) period.month else YearMonth.from(period.endDate)
            val title = "${endMonth.year}年${endMonth.monthValue}月"
            Column(Modifier.fillMaxWidth().padding(top = AbCardDensity.gap)
                .clip(RoundedCornerShape(topStart = AbRadius.group, topEnd = AbRadius.group,
                    bottomStart = if (open) 0.dp else AbRadius.group, bottomEnd = if (open) 0.dp else AbRadius.group))
                .background(p.cardBg)) {
                Row(Modifier.fillMaxWidth().clickable { onToggle(key) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${if (period.month == currentMonth) "本期" else "历史账期"} $title ${period.label()}"
                        stateDescription = if (open) "已展开" else "已收起"
                    }.heightIn(min = 64.dp).padding(AbCardDensity.content), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                        Text(if (period.month == currentMonth) "本期 · $title" else title, style = AbType.headline, color = p.label)
                        Text(period.shortLabel(), style = AbType.caption1, color = p.secondaryLabel)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("流出 ${if (hidden) "••••" else assetMoney(group.outflow)}", style = AbType.footnote, color = p.expense)
                        Text("流入 ${if (hidden) "••••" else assetMoney(group.inflow)}", style = AbType.footnote, color = p.income)
                    }
                    Icon(if (open) AbIcons.ChevronDown else AbIcons.ChevronRight, null, Modifier.size(18.dp), tint = p.secondaryLabel)
                }
                if (open) InsetDivider(inset = AbCardDensity.content)
            }
        }
        if (open && group.rows.isEmpty()) item("account-empty-$key") {
            Text("暂无账单", style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = AbRadius.group, bottomEnd = AbRadius.group))
                    .background(LocalAbPalette.current.cardBg).padding(AbCardDensity.content))
        }
        if (open) itemsIndexed(group.rows, key = { _, row -> "account-history-${row.key}" }) { index, row ->
            val p = LocalAbPalette.current
            val date = Instant.ofEpochMilli(row.at).atZone(zone).toLocalDate()
            val previousDate = group.rows.getOrNull(index - 1)?.let { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            val last = index == group.rows.lastIndex
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = if (last) AbRadius.group else 0.dp,
                bottomEnd = if (last) AbRadius.group else 0.dp)).background(p.cardBg)) {
                if (previousDate != date) {
                    Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)),
                        style = AbType.caption1, color = p.secondaryLabel,
                        modifier = Modifier.padding(horizontal = AbCardDensity.content, vertical = AbSpacing.s))
                    InsetDivider(inset = AbCardDensity.content)
                }
                val bill = row.bill
                val entry = row.entry
                val manual = bill == null
                val title = bill?.let { it.subCategory ?: it.category ?: "未分类" }
                    ?: when (entry?.kind) { "OPENING" -> "余额起点"; "REVERSAL" -> "调整撤销"; else -> "余额调整" }
                val tint = when (bill?.type) { "EXPENSE" -> p.expense; "INCOME", "REFUND" -> p.income; else -> p.accent }
                ListRow(title = title, subtitle = buildString {
                    append(Instant.ofEpochMilli(row.at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
                    val note = bill?.note?.takeIf { it.isNotBlank() } ?: bill?.merchant ?: entry?.note
                    if (!note.isNullOrBlank()) append(" · ").append(note)
                    if (bill != null && !bill.assetLinked) append(" · 未计入余额")
                }, leading = { CategoryBadge(if (manual) null else bill?.category, tint, size = 34.dp) }, trailing = {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 150.dp)) {
                        val delta = bill?.let { AssetMath.delta(it.type, it.priceCents) } ?: entry!!.deltaCents
                        Text(if (hidden) "••••" else (if (delta > 0) "+" else "") + assetMoney(delta), style = AbType.amountRow, color = tint)
                        row.balanceAfter?.takeIf { manual }?.let { Text("余额 ${if (hidden) "••••" else assetMoney(it)}", style = AbType.caption1, color = p.secondaryLabel) }
                    }
                }, onClick = when {
                    bill != null -> ({ onBill(bill.id) })
                    entry?.kind == "ADJUSTMENT" && entry.id !in reversedIds -> ({ onReverse(entry) })
                    else -> null
                })
                if (!last) InsetDivider(inset = AbCardDensity.content)
            }
        }
    }
}
