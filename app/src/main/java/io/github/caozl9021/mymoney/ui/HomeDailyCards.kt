package io.github.caozl9021.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.caozl9021.mymoney.data.AppDb
import io.github.caozl9021.mymoney.ui.design.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal data class HomeDailyState(
    val snapshot: HomeDailySnapshot? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Keep successfully loaded cards on screen during pagination and retry. A timezone change starts fresh. */
@Composable
internal fun rememberHomeDailyState(clock: LedgerClock, period: io.github.caozl9021.mymoney.data.LedgerMonthPeriod, anchorLimit: Int, retry: Int): HomeDailyState {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { AppDb.get(context).bills() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val boundary = remember(dao, period) { HomeDailyBoundary() }
    var state by remember(dao, period) { mutableStateOf(HomeDailyState()) }
    LaunchedEffect(dao, lifecycle, period, anchorLimit, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state = state.copy(loading = true, failed = false)
            try {
                homeDailySnapshots(
                    dao.observeHomeWindow(period.fromMillis, period.toMillis, anchorLimit),
                    dao.observeHomeCount(period.fromMillis, period.toMillis), clock.zone, boundary,
                    observeFrom = { from -> dao.observeRange(from, period.toMillis) },
                    fromInclusive = period.fromMillis, toExclusive = period.toMillis,
                )
                    .collect { state = HomeDailyState(snapshot = it, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                state = state.copy(loading = false, failed = true)
            }
        }
    }
    return state
}

/** Non-daily home sections keep a uniform card gap while daily segments have no seams. */
internal fun LazyListScope.homeCardItem(key: Any, content: @Composable () -> Unit) {
    item(key = key) {
        Column(Modifier.fillMaxWidth().padding(bottom = AbCardDensity.gap)) { content() }
    }
}

/** One logical daily card, composed as lazy header/rows so a thousand-row day stays lazy. */
internal fun LazyListScope.homeDailyCards(
    snapshot: HomeDailySnapshot,
    clock: LedgerClock,
    onBillClick: (Long) -> Unit,
) {
    snapshot.days.forEach { day ->
        item(key = "home-day-${day.date}", contentType = "home-day-header") {
            val p = LocalAbPalette.current
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = AbRadius.group, topEnd = AbRadius.group))
                .background(p.cardBg)) {
                HomeDailyHeader(day, clock)
                InsetDivider(inset = AbCardDensity.content)
            }
        }
        itemsIndexed(day.bills, key = { _, bill -> "home-bill-${bill.id}" }, contentType = { _, _ -> "home-bill" }) { index, bill ->
            val last = index == day.bills.lastIndex
            val shape = RoundedCornerShape(bottomStart = if (last) AbRadius.group else 0.dp,
                bottomEnd = if (last) AbRadius.group else 0.dp)
            Column(Modifier.fillMaxWidth().padding(bottom = if (last) AbCardDensity.gap else 0.dp)
                .clip(shape).background(LocalAbPalette.current.cardBg)) {
                BillListRow(bill, modifier = Modifier.clickable { onBillClick(bill.id) })
                if (!last) InsetDivider(inset = AbSpacing.l + AbCardDensity.icon + AbSpacing.m)
            }
        }
    }
}

@Composable
private fun HomeDailyHeader(day: HomeDailyDay, clock: LedgerClock) {
    val p = LocalAbPalette.current
    val date = day.date.format(DateTimeFormatter.ofPattern("MM/dd")) +
        if (day.date.year != clock.date.year) " · ${day.date.year}" else ""
    val amount = "支出 ${formatAmount(day.expenseCents)}"
    val style = AbType.subhead.copy(fontWeight = FontWeight.SemiBold)
    val measure = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = AbCardDensity.content, vertical = AbCardDensity.headerVertical)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val needed = measure.measure(AnnotatedString(date), style = style, softWrap = false).size.width +
            measure.measure(AnnotatedString(amount), style = style, softWrap = false).size.width +
            with(density) { AbSpacing.l.roundToPx() }
        if (needed <= constraints.maxWidth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = style, color = p.label, modifier = Modifier.semantics { contentDescription = day.date.toString() })
                Text(amount, style = style, color = p.expense, textAlign = TextAlign.End)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                Text(date, style = style, color = p.label, modifier = Modifier.semantics { contentDescription = day.date.toString() })
                Text(amount, style = style, color = p.expense, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
