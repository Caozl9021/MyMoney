package io.github.mymoney.ui

import kotlinx.coroutines.launch

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
fun BillsScreen(requestAdd: Int = 0, applyPendingFilter: Boolean = false, onPendingFilterApplied: () -> Unit = {}, onEditorVisibilityChanged: (Boolean) -> Unit = {}, onBack: (() -> Unit)? = null, statisticsScope: StatisticsDrilldown? = null) {
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
    var reimburse by rememberSaveable { mutableStateOf<String?>(null) }
    var reimburseTarget by remember { mutableStateOf<Pair<BillEntity, Rect?>?>(null) }
    var limit by rememberSaveable { mutableStateOf(100) }
    var resetPosition by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val dateWindow = remember(dateMode, start, end, clock, monthStartDay) { ledgerFilterWindow(dateMode, start, end, clock.date, clock.zone, monthStartDay) }
    val query = remember(keyword, dateWindow, category, type, reimburse, statisticsScope) {
        statisticsScope?.query(keyword) ?: LedgerQuery(keyword, dateWindow.fromMillis, dateWindow.toMillis, category, type, reimburse = reimburse)
    }
    val active = keyword.isNotBlank() || dateMode != "ALL" || category != null || type != null || reimburse != null
    fun changed() { limit = 100; resetPosition = true }
    fun clear() { keyword = ""; dateMode = "ALL"; category = null; type = null; reimburse = null; changed() }
    LaunchedEffect(applyPendingFilter) {
        if (applyPendingFilter) {
            keyword = ""; dateMode = "ALL"; category = null; type = null; reimburse = Reimburse.PENDING; changed()
            onPendingFilterApplied()
        }
    }
    val rawWindow by produceState(BillWindowState(), dao, lifecycle, query, limit, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = if (value.query == query) value.copy(loading = true, error = null) else BillWindowState(query = query)
            combine(
                dao.observeFilteredWindow(query.likePattern, query.fromMillis, query.toMillis, query.category, query.type, limit, query.statisticsCategory, query.statisticsAccount, query.reimburse),
                dao.observeFilteredSummary(query.likePattern, query.fromMillis, query.toMillis, query.category, query.type, query.statisticsCategory, query.statisticsAccount, query.reimburse),
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
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
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
    val predictiveBack = LocalPredictiveBackEnabled.current
    val navigationScope = androidx.compose.runtime.rememberCoroutineScope()
    var sourcePreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var openingBill by remember { mutableStateOf(false) }
    fun openBill(id: Long) {
        if (openingBill || selectedId != null) return
        if (!predictiveBack) {
            selectedId = id
            return
        }
        openingBill = true
        navigationScope.launch {
            try { sourcePreview = pagePixels.snapshot(); selectedId = id } finally { openingBill = false }
        }
    }
    val p = LocalAbPalette.current
    val editorBackdrop = rememberGraphicsLayer()
    var editorHostOrigin by remember { mutableStateOf(Offset.Zero) }
    val sampleEditorBackdrop = android.os.Build.VERSION.SDK_INT >= 31 &&
        LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current && newSource != null
    BackSurface(enabled = false, onBack = {}, pixels = pagePixels) {
    GlassContextMenuHost(backEnabled = LocalBackActive.current) {
    Box(Modifier.fillMaxSize().background(p.pageBg).onGloballyPositioned { editorHostOrigin = it.boundsInRoot().topLeft }) {
        Box(
            Modifier.fillMaxSize().then(
                if (sampleEditorBackdrop) Modifier.drawWithContent {
                    editorBackdrop.record { this@drawWithContent.drawContent() }
                    drawLayer(editorBackdrop)
                } else Modifier,
            ),
        ) {
        PageScaffold(
            title = statisticsScope?.groupName ?: "明细",
            listState = listState,
            onBack = onBack,
            actions = {
                if (statisticsScope == null) {
                    GlassCircleIconButton(AbIcons.Clock, "记录历史", { historyOpen = true }, tint = p.label)
                    GlassCircleIconButton(AbIcons.Plus, "记一笔", { addBill() }, tint = p.label)
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(top = (padding.calculateTopPadding() - AbSpacing.s).coerceAtLeast(0.dp)),
            ) {
                if (statisticsScope != null) {
                    val scopeTypeLabel = when (statisticsScope.type) { "INCOME" -> "收入"; "REFUND" -> "退款"; else -> "支出" }
                    val totalCents = window.summary?.let {
                        when (statisticsScope.type) {
                            "INCOME" -> it.income
                            "REFUND" -> it.refund
                            else -> it.expense
                        }
                    } ?: bills.sumOf { it.priceCents }
                    val count = window.summary?.count ?: bills.size
                    Box(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.page).padding(bottom = AbSpacing.s)) {
                        InsetGroup {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AbCardDensity.content, vertical = AbCardDensity.rowVertical),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "${statisticsScope.periodLabel} · $scopeTypeLabel",
                                        style = AbType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                                        color = p.label,
                                    )
                                    Text(
                                        "共 $count 笔",
                                        style = AbType.caption1,
                                        color = p.secondaryLabel,
                                    )
                                }
                                AmountText(
                                    formatAmount(totalCents),
                                    style = AbType.title2,
                                    color = amountTint(p, statisticsScope.type),
                                )
                            }
                        }
                    }
                } else {
                    LedgerFilters(
                        keyword, { keyword = it; changed() },
                        dateMode, start, end, clock.date,
                        { mode, from, to -> dateMode = mode; start = from; end = to; changed() },
                        category, categoryOptions, { category = it; changed() },
                        type, { type = it; changed() },
                        reimburse, { reimburse = it; changed() },
                        active, { clear() },
                        monthStartDay = monthStartDay,
                        summaryLabel = window.summary?.let { ledgerSummaryLabel(it, active) }
                            ?: if (window.error != null) "读取失败" else "",
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (window.summary == null && window.error != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.page)) {
                            InsetGroup {
                                ListRow(
                                    title = "读取失败，点此重试",
                                    subtitle = window.error,
                                    onClick = { retry++ },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter),
                            contentPadding = PaddingValues(
                                start = AbSpacing.page,
                                end = AbSpacing.page,
                                top = AbSpacing.xs,
                                bottom = padding.calculateBottomPadding(),
                            ),
                        ) {
                            if (!window.loading && window.error == null && window.summary != null && bills.isEmpty()) {
                                item(key = "empty") {
                                    InsetGroup {
                                        val emptyLedger = window.totalCount == 0
                                        EmptyState(
                                            icon = AbIcons.Note,
                                            title = if (emptyLedger) "还没有账单" else "没有匹配的账单",
                                            message = if (emptyLedger) "点右上角「+」记一笔，或开启识别后确认保存。" else "该筛选范围内没有账单。",
                                        )
                                        if (active && statisticsScope == null) {
                                            AbTextButton(
                                                text = "清除筛选",
                                                onClick = { clear() },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                            days.forEach { (day, dayBills) ->
                                item(key = "h-$day", contentType = "date") {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(topStart = AbRadius.group, topEnd = AbRadius.group))
                                            .background(p.cardBg),
                                    ) {
                                        LedgerCardDayHeader(day, dayBills, clock)
                                        InsetDivider(inset = AbCardDensity.content)
                                    }
                                }
                                dayBills.forEachIndexed { index, bill ->
                                    item(key = bill.id, contentType = "bill") {
                                        val last = index == dayBills.lastIndex
                                        val shape = RoundedCornerShape(
                                            bottomStart = if (last) AbRadius.group else 0.dp,
                                            bottomEnd = if (last) AbRadius.group else 0.dp,
                                        )
                                        Column(
                                            (if (reduceMotion) Modifier else Modifier.animateItem(
                                                fadeInSpec = tween(AbMotion.fast, easing = AbEasing.EaseOut),
                                                placementSpec = AbSpring.morphOffset(false),
                                                fadeOutSpec = tween(100, easing = AbEasing.EaseOut),
                                            ))
                                                .fillMaxWidth()
                                                .padding(bottom = if (last) AbCardDensity.gap else 0.dp)
                                                .clip(shape)
                                                .background(p.cardBg),
                                        ) {
                                            val boundsHolder = remember { arrayOfNulls<Rect>(1) }
                                            SwipeableBillRow(
                                                bill,
                                                modifier = Modifier.onGloballyPositioned { boundsHolder[0] = it.boundsInRoot() },
                                                onAmountBounds = {},
                                                hideAmount = false,
                                                onEdit = { openBill(bill.id); newSource = null },
                                                onDelete = { deleteId = bill.id },
                                                onLongPress = { if (Reimburse.eligible(bill)) reimburseTarget = bill to boundsHolder[0] },
                                            )
                                        }
                                    }
                                }
                            }
                            if (window.loading && bills.isNotEmpty()) {
                                item(key = "loading") { GroupFooter("正在加载更多…") }
                            }
                            if (window.error != null) {
                                item(key = "error") {
                                    InsetGroup {
                                        ListRow(title = "读取失败，点此重试", subtitle = window.error, onClick = { retry++ })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        deleteId?.let { id -> DeleteBillDialog(id, origin = "ledger", onDismiss = { deleteId = null }, onDeleted = { deleteId = null }) }
        reimburseTarget?.let { (bill, bounds) -> ReimburseChoiceDialog(bill, anchor = bounds) { reimburseTarget = null } }
        SlideSubPage(visible = historyOpen, modifier = Modifier.fillMaxSize().zIndex(9f)) {
            RecognitionHistoryScreen(onBack = { historyOpen = false }, reserveDock = false)
        }
        SlideSubPageValue(value = selectedId, modifier = Modifier.fillMaxSize().zIndex(10f)) { id ->
            BillActionsHost(billId = id, onClose = { selectedId = null; sourcePreview = null }, sourcePreview = sourcePreview)
        }
        if (newSource != null) key(newSource) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                BillEditorDialog(
                    original = null,
                    newSource = newSource.orEmpty(),
                    newTime = newTime,
                    backdrop = if (sampleEditorBackdrop) editorBackdrop else null,
                    backdropOrigin = editorHostOrigin,
                    onClose = { newSource = null },
                )
            }
        }
    }
    }
    }
}

private fun ledgerSummaryLabel(summary: BillQuerySummary, filtered: Boolean): String = buildList {
    add("${if (filtered) "匹配 " else "共 "}${summary.count} 笔")
    if (summary.expense != 0L || (summary.income == 0L && summary.refund == 0L)) {
        add("支出 ${formatAmount(summary.expense)}")
    }
    if (summary.income != 0L) add("收入 ${formatAmount(summary.income)}")
    if (summary.refund != 0L) add("退款 ${formatAmount(summary.refund)}")
}.joinToString(" · ")

@Composable
private fun LedgerCardDayHeader(day: LocalDate, dayBills: List<BillEntity>, clock: LedgerClock) {
    val p = LocalAbPalette.current
    val date = day.format(DateTimeFormatter.ofPattern("MM/dd")) +
        if (day.year != clock.date.year) " · ${day.year}" else ""
    val expense = dayBills.filter { it.type == "EXPENSE" }.sumOf { it.priceCents }
    val income = dayBills.filter { it.type == "INCOME" }.sumOf { it.priceCents }
    val refund = dayBills.filter { it.type == "REFUND" }.sumOf { it.priceCents }
    val (amountText, amountColor) = when {
        expense > 0L -> "支出 ${formatAmount(expense)}" to p.expense
        income > 0L -> "收入 ${formatAmount(income)}" to p.income
        refund > 0L -> "退款 ${formatAmount(refund)}" to p.refund
        else -> "支出 ${formatAmount(0L)}" to p.secondaryLabel
    }
    val style = AbType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold).tabular()
    val measure = androidx.compose.ui.text.rememberTextMeasurer()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AbCardDensity.content, vertical = AbCardDensity.headerVertical),
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val needed = measure.measure(androidx.compose.ui.text.AnnotatedString(date), style = style, softWrap = false).size.width +
            measure.measure(androidx.compose.ui.text.AnnotatedString(amountText), style = style, softWrap = false).size.width +
            with(density) { AbSpacing.l.roundToPx() }
        if (needed <= constraints.maxWidth) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(date, style = style, color = p.label)
                Text(amountText, style = style, color = amountColor)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                Text(date, style = style, color = p.label)
                Text(amountText, style = style, color = amountColor, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 左滑请求删除确认；取消时保持原账单。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableBillRow(
    bill: BillEntity,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    onAmountBounds: (Rect) -> Unit,
    hideAmount: Boolean,
) {
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val rowInteraction = remember { MutableInteractionSource() }
            val deleteInteraction = remember { MutableInteractionSource() }
            BillListRow(
                bill,
                modifier = Modifier
                    .weight(1f)
                    .abPressFeedback(rowInteraction, pressedScale = 0.99f, tintOverlay = true)
                    .combinedClickable(
                        interactionSource = rowInteraction,
                        indication = null,
                        onClick = onEdit,
                        onLongClick = onLongPress,
                    ),
                onAmountBounds = onAmountBounds,
                hideAmount = hideAmount,
            )
            Box(
                Modifier
                    .size(48.dp)
                    .abPressFeedback(deleteInteraction, pressedScale = 0.92f, pressedAlpha = 0.65f)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = deleteInteraction,
                        indication = null,
                        role = Role.Button,
                    ) {
                        haptic.warn()
                        onDelete()
                    }
                    .semantics { contentDescription = "删除账单，需确认" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AbIcons.Close, contentDescription = null, tint = LocalAbPalette.current.red, modifier = Modifier.size(18.dp))
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

    val density = androidx.compose.ui.platform.LocalDensity.current
    val offsetPx = runCatching { kotlin.math.abs(state.requireOffset()) }.getOrDefault(0f)
    val progress = (offsetPx / rowWidth).coerceIn(0f, 1f)
    var crossedThreshold by remember { mutableStateOf(false) }
    LaunchedEffect(progress) {
        val nowCrossed = progress >= 0.38f
        if (nowCrossed && !crossedThreshold) haptic.tick()
        crossedThreshold = nowCrossed
    }
    val cornerThresholdPx = remember(density) { with(density) { 12.dp.toPx() } }
    val rowCorner = (((offsetPx / cornerThresholdPx).coerceIn(0f, 1f)) * 16f).dp
    val rowInteraction = remember { MutableInteractionSource() }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = { SwipeDeleteBackground(progress = progress) },
        modifier = modifier.onSizeChanged { rowWidth = it.width.toFloat().coerceAtLeast(1f) },
    ) {
        // 行本身要盖住删除底衬，所以必须有实底；左滑露出底衬时右侧自适应收出圆角弧度
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topEnd = rowCorner, bottomEnd = rowCorner))
                .background(LocalAbPalette.current.cardBg),
        ) {
            BillListRow(
                bill,
                modifier = Modifier
                    .abPressFeedback(rowInteraction, pressedScale = 0.99f, tintOverlay = true)
                    .combinedClickable(
                        interactionSource = rowInteraction,
                        indication = null,
                        onClick = onEdit,
                        onLongClick = onLongPress,
                    ),
                onAmountBounds = onAmountBounds,
                hideAmount = hideAmount,
            )
        }
    }
}
