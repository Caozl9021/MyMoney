package io.github.mymoney.ui

import kotlinx.coroutines.launch

import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mymoney.data.*
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class BillWindowState(
    val query: LedgerQuery? = null,
    val bills: List<BillEntity> = emptyList(),
    val summary: BillQuerySummary? = null,
    val totalCount: Int? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Complete ledger, grown in windows while every bill remains an independent lazy item. */
@Composable
fun BillsScreen(requestAdd: Int = 0, onEditorVisibilityChanged: (Boolean) -> Unit = {}, onBack: (() -> Unit)? = null) {
    val app = LocalContext.current.applicationContext
    val dao = remember(app) { AppDb.get(app).bills() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = rememberLedgerClock()
    val monthStartDay = rememberMonthStartDay()
    val listState = rememberLazyListState()
    var keyword by rememberSaveable { mutableStateOf("") }
    var dateMode by rememberSaveable { mutableStateOf("ALL") }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by rememberSaveable { mutableStateOf(100) }
    var resetPosition by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val dateWindow = remember(dateMode, start, end, clock, monthStartDay) { ledgerFilterWindow(dateMode, start, end, clock.date, clock.zone, monthStartDay) }
    val query = remember(keyword, dateWindow, category, type) {
        LedgerQuery(keyword, dateWindow.fromMillis, dateWindow.toMillis, category, type)
    }
    val active = keyword.isNotBlank() || dateMode != "ALL" || category != null || type != null
    fun changed() { limit = 100; resetPosition = true }
    fun clear() { keyword = ""; dateMode = "ALL"; category = null; type = null; changed() }
    val rawWindow by produceState(BillWindowState(), dao, lifecycle, query, limit, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = if (value.query == query) value.copy(loading = true, error = null) else BillWindowState(query = query)
            combine(
                dao.observeFilteredWindow(query.likePattern, query.fromMillis, query.toMillis, query.category, query.type, limit),
                dao.observeFilteredSummary(query.likePattern, query.fromMillis, query.toMillis, query.category, query.type),
                dao.observeCount(),
            ) { bills, summary, total -> BillWindowState(query, bills, summary, total, loading = false) }
                .catch { value = BillWindowState(query = query, loading = false, error = it.message ?: "读取账单失败") }
                .collect { value = it }
        }
    }
    // produceState keeps its previous value until its coroutine starts; never label that value as a new query.
    val window = if (rawWindow.query == query) rawWindow else BillWindowState(query = query)
    val bills = window.bills
    LaunchedEffect(window.summary, resetPosition) {
        if (resetPosition && window.summary != null) { listState.scrollToItem(0); resetPosition = false }
    }
    LaunchedEffect(listState, query, bills.size, window.summary, window.loading, window.error) {
        if (!window.loading && window.error == null && bills.size >= limit && bills.size < (window.summary?.count ?: 0)) {
            snapshotFlow {
                val layout = listState.layoutInfo
                layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 8
            }.first { it }
            limit = (limit.toLong() + 100L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    val categories by produceState(emptyList<String>(), dao, lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dao.observeCategories().catch { /* Query errors are shown by the main ledger query. */ }.collect { value = it }
        }
    }
    // Include configured names and historical names even when configuration was subsequently removed.
    val categoryOptions = (LedgerConfig.expenseGroups.map { it.name } + LedgerConfig.incomeGroups.map { it.name } + categories + listOfNotNull(category)).distinct()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var newSource by rememberSaveable { mutableStateOf<String?>(null) }
    var newTime by rememberSaveable { mutableStateOf(0L) }
    val reduceMotion = LocalReduceMotion.current
    fun addBill() {
        selectedId = null
        newTime = System.currentTimeMillis()
        newSource = "io.github.mymoney.manual.${java.util.UUID.randomUUID()}"
    }
    val currentVisibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(newSource, selectedId, historyOpen) {
        currentVisibilityChanged(newSource != null || selectedId != null || historyOpen)
    }
    DisposableEffect(Unit) { onDispose { currentVisibilityChanged(false) } }
    var consumedRequest by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(requestAdd) {
        if (requestAdd != 0 && requestAdd != consumedRequest) { consumedRequest = requestAdd; addBill() }
    }
    val days = remember(bills, clock.zone) {
        bills.groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(clock.zone).toLocalDate() }
    }
    // 明细是压在 Tab 之上的整页，不是 Tab 之一：必须自己铺不透明底，
    // 否则下面的统计/首页会从行缝里透出来（曾经的「串页」）。
    val pagePixels = RememberBackPixels()
    val navigationScope = androidx.compose.runtime.rememberCoroutineScope()
    var sourcePreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var openingBill by remember { mutableStateOf(false) }
    fun openBill(id: Long) {
        if (openingBill || selectedId != null) return
        openingBill = true
        navigationScope.launch {
            try { sourcePreview = pagePixels.snapshot(); selectedId = id } finally { openingBill = false }
        }
    }
    BackSurface(enabled = false, onBack = {}, pixels = pagePixels) {
    Box(Modifier.fillMaxSize().background(LocalAbPalette.current.pageBg)) {
        PageScaffold(title = "明细", listState = listState, onBack = onBack,
            actions = {
                IconButton(onClick = { historyOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(AbIcons.Clock, contentDescription = "识别历史", tint = LocalAbPalette.current.label)
                }
                TextButton(onClick = { addBill() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("记一笔") }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().imePadding()) {
                LedgerFilters(keyword, { keyword = it; changed() }, dateMode, start, end, clock.date,
                    { mode, from, to -> dateMode = mode; start = from; end = to; changed() },
                    category, categoryOptions, { category = it; changed() }, type, { type = it; changed() }, active, { clear() },
                    monthStartDay = monthStartDay, summaryLabel = window.summary?.let { "${if (active) "匹配" else "共"} ${it.count} 笔" }
                        ?: if (window.error != null) "读取失败" else "正在读取账单…")
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Keep a restored deep position untouched until its saved window is available.
                    if (window.summary == null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.page)) {
                            InsetGroup { ListRow(title = if (window.error == null) "正在读取账单…" else "读取失败，点此重试",
                                subtitle = window.error, onClick = { if (window.error != null) retry++ }) }
                        }
                    } else LazyColumn(state = listState,
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter),
                        contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                            top = AbSpacing.xs, bottom = padding.calculateBottomPadding() + 64.dp),
                    ) {
                        item(key = "summary") { GroupFooter(ledgerSummaryLabel(window.summary!!, active)) }
                        if (!window.loading && window.error == null && bills.isEmpty()) item(key = "empty") {
                            InsetGroup {
                                val emptyLedger = window.totalCount == 0
                                EmptyState(icon = AbIcons.Note, title = if (emptyLedger) "还没有账单" else "没有匹配的账单",
                                    message = if (emptyLedger) "点右上角「记一笔」，或开启识别后确认保存。" else "试试其他关键词，或清除筛选查看全部账单。")
                                if (active) TextButton(onClick = { clear() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清除筛选") }
                            }
                        }
                        days.forEach { (day, dayBills) ->
                            item(key = "h-$day", contentType = "date") {
                                LedgerDayHeader(day, clock, query, active, retry)
                            }
                            dayBills.forEachIndexed { index, bill ->
                                item(key = bill.id, contentType = "bill") {
                                    val shape = RoundedCornerShape(
                                        topStart = if (index == 0) AbRadius.group else 0.dp,
                                        topEnd = if (index == 0) AbRadius.group else 0.dp,
                                        bottomStart = if (index == dayBills.lastIndex) AbRadius.group else 0.dp,
                                        bottomEnd = if (index == dayBills.lastIndex) AbRadius.group else 0.dp)
                                    Column((if (reduceMotion) Modifier else Modifier.animateItem(
                                        fadeInSpec = tween(AbMotion.fast), placementSpec = tween(AbMotion.fast), fadeOutSpec = tween(AbMotion.fast)))
                                        .fillMaxWidth().clip(shape).background(LocalAbPalette.current.cardBg)) {
                                        if (index > 0) InsetDivider()
                                        SwipeableBillRow(bill, onAmountBounds = {}, hideAmount = false,
                                            onEdit = { openBill(bill.id); newSource = null },
                                            onDelete = { BillWriter.deleteUndoably(app, bill.id) { Toast.makeText(app, it, Toast.LENGTH_LONG).show() } })
                                    }
                                }
                            }
                        }
                        if (window.loading) item(key = "loading") { GroupFooter("正在加载更多…") }
                        if (window.error != null) item(key = "error") { InsetGroup { ListRow(title = "读取失败，点此重试", subtitle = window.error, onClick = { retry++ }) } }
                        item(key = "history") {
                            Spacer(Modifier.height(AbSpacing.l))
                            InsetGroup { ListRow(title = "识别历史", subtitle = "查看待处理及全部识别记录", chevron = true, onClick = { historyOpen = true }) }
                        }
                        item(key = "footer") {
                            GroupFooter(if (reduceMotion) "点账单查看操作 · 删除后5秒内可撤销" else "点账单查看操作 · 左滑删除后5秒内可撤销")
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = AbSpacing.page)
                        .padding(bottom = padding.calculateBottomPadding())) { BillUndoStack() }
                }
            }
        }
        if (historyOpen) Box(Modifier.fillMaxSize().zIndex(9f)) {
            RecognitionHistoryScreen(onBack = { historyOpen = false }, reserveDock = false)
        }
        selectedId?.let { id ->
            Box(Modifier.fillMaxSize().zIndex(10f)) { BillActionsHost(billId = id, onClose = { selectedId = null; sourcePreview = null }, sourcePreview = sourcePreview) }
        }
        if (newSource != null) key(newSource) {
            Box(Modifier.fillMaxSize().zIndex(10f)) { BillEditorDialog(null, newSource.orEmpty(), newTime, onClose = { newSource = null }) }
        }
    }
    }
}

private fun ledgerSummaryLabel(summary: BillQuerySummary, filtered: Boolean): String = buildList {
    add("${if (filtered) "匹配" else "共"}${summary.count}笔")
    add("支出${formatAmount(summary.expense)}")
    if (summary.income != 0L) add("收入${formatAmount(summary.income)}")
    if (summary.refund != 0L) add("退款${formatAmount(summary.refund)}")
}.joinToString(" · ")

/**
 * 日期组头：左边日期/星期，右边该日支出。
 *
 * 右列是**支出**，不是净额。收入/退款/转账非零时在下面单独补一行，
 * 让「支出 28.37 + 收入 5.00」不会被读成「今天只花了 23.37」。
 * 金额不加 `maxLines`/省略号：宽度不够时让左列日期换行，不截断关键数字。
 */
@Composable
private fun LedgerDayHeader(day: LocalDate, clock: LedgerClock, query: LedgerQuery, filtered: Boolean, retry: Int) {
    val app = LocalContext.current.applicationContext
    val dao = remember(app) { AppDb.get(app).bills() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dayWindow = remember(day, clock.zone) { ledgerDateWindow(day, day, clock.zone) }
    val dailyQuery = remember(query, dayWindow) { query.copy(fromMillis = maxOf(query.fromMillis, dayWindow.fromMillis), toMillis = minOf(query.toMillis, dayWindow.toMillis)) }
    var localRetry by remember { mutableStateOf(0) }
    val state by key(dailyQuery) { produceState<Pair<BillQuerySummary?, Boolean>>(null to false, dao, lifecycle, dailyQuery, retry, localRetry) {
        value = null to false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dao.observeFilteredSummary(dailyQuery.likePattern, dailyQuery.fromMillis, dailyQuery.toMillis, dailyQuery.category, dailyQuery.type)
                .catch { value = null to true }.collect { value = it to false }
        }
    } }
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxWidth().padding(start = AbSpacing.xs, end = AbSpacing.xs, top = AbSpacing.m, bottom = AbSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val pattern = if (day.year == clock.date.year) "M月d日 EEEE" else "yyyy年M月d日 EEEE"
        val daySummary = state.first
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                Text(
                    DateTimeFormatter.ofPattern(pattern, Locale.CHINA).format(day),
                    style = AbType.sectionHeader,
                    color = p.secondaryLabel,
                )
                // 笔数跟在日期后面而不是独占一行：无收入/退款时整组只占一行，
                // 日期组头省下的一行高度直接还给账单行。
                if (daySummary != null) Text(
                    "${if (filtered) "匹配" else "共"} ${daySummary.count} 笔",
                    style = AbType.caption2, color = p.tertiaryLabel,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
            if (daySummary != null) Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
            ) {
                Text("支出", style = AbType.caption1, color = p.tertiaryLabel,
                    modifier = Modifier.padding(bottom = 2.dp))
                Text(
                    formatAmount(daySummary.expense),
                    style = AbType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = if (daySummary.expense == 0L) p.secondaryLabel else p.expense,
                )
            }
        }
        when {
            daySummary != null -> {
                val extras = buildList {
                    if (daySummary.income != 0L) add("收入 ${formatAmount(daySummary.income)}")
                    if (daySummary.refund != 0L) add("退款 ${formatAmount(daySummary.refund)}")
                }
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = AbType.caption1,
                    color = p.secondaryLabel, modifier = Modifier.fillMaxWidth())
            }
            state.second -> TextButton(onClick = { localRetry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("日汇总读取失败，重试") }
            else -> Text("正在读取日汇总…", style = AbType.caption1, color = p.secondaryLabel)
        }
    }
}

/** 左滑删除：滑过阈值即删库，条目随列表刷新消失。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableBillRow(bill: BillEntity, onEdit: () -> Unit, onDelete: () -> Unit, onAmountBounds: (Rect) -> Unit, hideAmount: Boolean) {
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BillListRow(bill, modifier = Modifier.weight(1f).clickable(onClick = onEdit),
                onAmountBounds = onAmountBounds, hideAmount = hideAmount)
            IconButton(onClick = { haptic.warn(); onDelete() }) {
                Icon(AbIcons.Close, contentDescription = "删除账单，可在5秒内撤销", tint = LocalAbPalette.current.red)
            }
        }
        return
    }
    var rowWidth by remember { mutableStateOf(1f) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptic.warn()
                onDelete()
                // 等数据库流移除行；失败时原行仍可继续操作。
                false
            } else false
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = { SwipeDeleteBackground(progress = (kotlin.math.abs(runCatching { state.requireOffset() }.getOrDefault(0f)) / rowWidth).coerceIn(0f, 1f)) },
        modifier = Modifier.onSizeChanged { rowWidth = it.width.toFloat().coerceAtLeast(1f) },
    ) {
        // 行本身要盖住红色底衬，所以必须有实底
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .background(LocalAbPalette.current.cardBg),
        ) {
            BillListRow(bill, modifier = Modifier.clickable(onClick = onEdit), onAmountBounds = onAmountBounds, hideAmount = hideAmount)
        }
    }
}
