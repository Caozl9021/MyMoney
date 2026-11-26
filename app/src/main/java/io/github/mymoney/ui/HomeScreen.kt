package io.github.mymoney.ui

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.zIndex
import com.example.billkit.DebugBus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import io.github.mymoney.data.CaptureMode
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.RecognitionStore
import io.github.mymoney.data.RecognitionStatus
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbSize
import io.github.mymoney.ui.design.AbSpring
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AppDialog
import io.github.mymoney.ui.design.AbRadius
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.abPressFeedback
import io.github.mymoney.ui.design.sectionHeader
import io.github.mymoney.ui.design.tabular
import io.github.mymoney.ui.design.EmptyState
import io.github.mymoney.ui.design.GlassContextMenuHost
import io.github.mymoney.ui.design.GroupFooter
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InsetDivider
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalLiquidGlassEnabled
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.LocalReduceTransparency
import io.github.mymoney.ui.design.PageScaffold
import io.github.mymoney.ui.design.formatAmount
import io.github.mymoney.ui.design.SecondaryButton

/** 首页：紧凑月概览、待处理与按完整自然日分组的账单卡片。 */
@Composable
fun HomeScreen(
    bills: List<BillEntity>,
    onOpenAssets: () -> Unit = {},
    onOpenSettings: (CaptureSettingsDestination) -> Unit,
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
    onOpenPending: () -> Unit = {},
) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val clock = rememberLedgerClock()
    val monthStartDay = rememberMonthStartDay()
    var periodBack by androidx.compose.runtime.saveable.rememberSaveable(monthStartDay) { mutableIntStateOf(0) }
    var reimburseTarget by remember { mutableStateOf<Pair<BillEntity, Rect?>?>(null) }
    val pendingReimburse by remember(context) {
        io.github.mymoney.data.AppDb.get(context.applicationContext).bills().observePendingReimbursement()
    }.collectAsStateWithLifecycle(io.github.mymoney.data.PendingReimbursement(0, 0))
    val currentHomePeriod = remember(clock, monthStartDay) {
        io.github.mymoney.data.LedgerMonthPeriod.containing(clock.date, monthStartDay, clock.zone)
    }
    val earliestBillTime by remember(context) {
        io.github.mymoney.data.AppDb.get(context.applicationContext).bills().observeEarliestTimeMillis()
            .map { arrayOf(it) }
    }.collectAsStateWithLifecycle(null)
    val maxPeriodBack = remember(earliestBillTime, currentHomePeriod) {
        earliestBillTime?.let { currentHomePeriod.periodsBackTo(it[0]) }
    }
    LaunchedEffect(maxPeriodBack) {
        val limit = maxPeriodBack
        if (limit != null && periodBack > limit) periodBack = limit
    }
    val homePeriod = remember(currentHomePeriod, periodBack) {
        currentHomePeriod.shift(-periodBack.toLong())
    }
    val homePeriodKey = "${homePeriod.month}:${homePeriod.day}:${homePeriod.zone.id}"
    var visiblePeriodKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(homePeriodKey) }
    LaunchedEffect(homePeriodKey) {
        if (visiblePeriodKey != homePeriodKey) { listState.scrollToItem(0); visiblePeriodKey = homePeriodKey }
    }
    var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var historyOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var historyFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("ALL") }
    val healthInput = rememberCaptureHealthInput()
    val health = captureHealth(healthInput)
    val mode = healthInput.mode
    val recognition by DebugBus.state.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("first_run_guide", android.content.Context.MODE_PRIVATE) }
    var recognized by remember { mutableStateOf(prefs.getBoolean("recognized", false)) }
    LaunchedEffect(recognition, pendingRecognition(bills)) {
        if (recognition?.outcome?.startsWith("draft:") == true || pendingRecognition(bills)) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    var healthDetails by remember { mutableStateOf(false) }
    var newSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var newTime by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0L) }
    fun addBill() {
        newTime = System.currentTimeMillis()
        newSource = "io.github.mymoney.manual.${java.util.UUID.randomUUID()}"
    }
    val currentVisibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(newSource, historyOpen, selectedId) { currentVisibilityChanged(newSource != null || historyOpen || selectedId != null) }
    DisposableEffect(Unit) { onDispose { currentVisibilityChanged(false) } }
    var recentRetry by remember { mutableIntStateOf(0) }
    var anchorLimit by androidx.compose.runtime.saveable.rememberSaveable(homePeriodKey) { mutableIntStateOf(HOME_DAILY_PAGE_SIZE) }
    val dailyState = rememberHomeDailyState(clock, homePeriod, anchorLimit, recentRetry)
    val dailySnapshot = dailyState.snapshot
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val dailySlideX = remember { Animatable(0f) }
    val dailyAlpha = remember { Animatable(1f) }
    var shownDailyPeriod by remember { mutableStateOf<io.github.mymoney.data.LedgerMonthPeriod?>(null) }
    var pendingDailyDir by remember { mutableIntStateOf(0) }
    LaunchedEffect(dailyState.snapshot, dailyState.period) {
        val loadedPeriod = dailyState.period ?: return@LaunchedEffect
        if (dailyState.snapshot == null) return@LaunchedEffect
        if (shownDailyPeriod == null) {
            shownDailyPeriod = loadedPeriod
            return@LaunchedEffect
        }
        if (shownDailyPeriod != loadedPeriod) {
            shownDailyPeriod = loadedPeriod
            val dir = pendingDailyDir
            pendingDailyDir = 0
            if (dir != 0) {
                if (reduceMotion) {
                    dailySlideX.snapTo(0f)
                    dailyAlpha.snapTo(0.35f)
                    dailyAlpha.animateTo(1f, tween(110, easing = AbEasing.EaseOut))
                } else {
                    dailySlideX.snapTo(dir * with(density) { 88.dp.toPx() })
                    dailyAlpha.snapTo(0.1f)
                    coroutineScope {
                        launch { dailySlideX.animateTo(0f, AbSpring.navigationFloat(false)) }
                        launch { dailyAlpha.animateTo(1f, tween(170, easing = AbEasing.EaseOut)) }
                    }
                }
            }
        }
    }
    val history by RecognitionStore.records.collectAsStateWithLifecycle()
    val historyReady by RecognitionStore.ready.collectAsStateWithLifecycle()
    val historyError by RecognitionStore.error.collectAsStateWithLifecycle()
    val pending = remember(history) { history.filter { it.status == RecognitionStatus.PENDING } }

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
    val editorBackdrop = rememberGraphicsLayer()
    var editorHostOrigin by remember { mutableStateOf(Offset.Zero) }
    val sampleEditorBackdrop = android.os.Build.VERSION.SDK_INT >= 31 &&
        LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current && newSource != null
    BackSurface(enabled = false, onBack = {}, pixels = pagePixels) {
    GlassContextMenuHost(backEnabled = LocalBackActive.current) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { editorHostOrigin = it.boundsInRoot().topLeft }) {
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    Box(
        Modifier.fillMaxSize().then(
            if (sampleEditorBackdrop) Modifier.drawWithContent {
                editorBackdrop.record { this@drawWithContent.drawContent() }
                drawLayer(editorBackdrop)
            } else Modifier,
        ),
    ) {
    PageScaffold(
        title = "账本",
        compact = true,
        floatingHeader = true,
        onTitleClick = { onOpenSettings(CaptureSettingsDestination.AUTOMATION) },
        actions = {
            io.github.mymoney.ui.design.FloatingHeaderAction(AbIcons.Plus, "记一笔") { addBill() }
        },
        listState = listState,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        val dailyModifier = Modifier.graphicsLayer {
            if (!reduceMotion) translationX = dailySlideX.value
            alpha = dailyAlpha.value
        }
        LazyColumn(
            state = listState,
            // 底部让位必须走 contentPadding —— 不能用 Modifier.padding(padding)：
            // 那会把 LazyColumn 的视口从底部削掉一截，视口之下露出整片 pageBg，
            // 悬浮胶囊正好压在这片纯色区里，看起来就是「胶囊下面一条实色横带」。
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter).clipToBounds(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            homeCardItem(key = "summary") {
                MonthlySummaryCard(clock, homePeriod, periodBack, maxBack = maxPeriodBack) {
                    val limit = maxPeriodBack
                    val next = it.coerceAtLeast(0).let { v -> if (limit != null) minOf(v, limit) else v }
                    if (next != periodBack) {
                        pendingDailyDir = if (next > periodBack) -1 else 1
                        periodBack = next
                    }
                }
            }
            if (pendingReimburse.count > 0) item(key = "pending-reimburse") {
                val pendingInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = AbSpacing.page)
                        .heightIn(min = AbSize.minTouch)
                        .clip(RoundedCornerShape(AbRadius.row))
                        .abPressFeedback(pendingInteraction, pressedScale = 0.985f, pressedAlpha = 0.72f)
                        .clickable(interactionSource = pendingInteraction, indication = null, onClick = onOpenPending)
                        .padding(horizontal = AbSpacing.m),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "待报销 ${formatAmount(pendingReimburse.cents)} · ${pendingReimburse.count} 笔",
                        style = AbType.footnote.tabular(),
                        color = p.secondaryLabel,
                    )
                }
            }
            if (health.attention) homeCardItem(key = "runtime-attention") {
                val primaryAction = health.actions.firstOrNull()
                val attentionInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .abPressFeedback(attentionInteraction, pressedScale = 0.988f, tintOverlay = true)
                        .clip(RoundedCornerShape(AbRadius.row))
                        .background(p.fill)
                        .clickable(interactionSource = attentionInteraction, indication = null) { healthDetails = true }
                        .padding(start = AbSpacing.m, end = if (primaryAction == null) AbSpacing.m else AbSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                ) {
                    Text(
                        text = if (health.title.startsWith("已开启")) "自动记账需处理" else health.title,
                        style = AbType.footnote,
                        color = p.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    primaryAction?.let { action ->
                        AbTextButton(
                            text = "处理",
                            onClick = {
                                performCaptureAction(context, action, onOpenSettings) {
                                    historyFilter = RecognitionStatus.PENDING; historyOpen = true
                                }
                            },
                        )
                    }
                }
            }
            if (!historyReady || historyError != null || pending.isNotEmpty()) homeCardItem(key = "pending") {
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    InsetGroup {
                        ListRow(
                            title = if (historyError != null) "识别记录读取失败 · 重试" else if (!historyReady) "正在读取待处理账单…" else "待处理 ${pending.size} 笔",
                            subtitle = historyError ?: pending.firstOrNull()?.let { "${it.merchant ?: "识别到一笔账单"} · ${formatAmount(it.priceCents)}" },
                            chevron = historyReady && historyError == null,
                            onClick = {
                                if (historyError != null) RecognitionStore.init(context)
                                else { historyFilter = RecognitionStatus.PENDING; historyOpen = true }
                            },
                        )
                    }
                }
            }
            if (dailySnapshot == null && dailyState.loading) {
                homeCardItem(key = "daily-loading") {
                    InsetGroup { ListRow(title = "正在读取账单…") }
                }
            } else if (dailySnapshot == null && dailyState.failed) {
                homeCardItem(key = "daily-error") {
                    InsetGroup { ListRow(title = "账单读取失败", subtitle = "点此重新加载", onClick = { recentRetry++ }) }
                }
            } else if (dailySnapshot != null && dailySnapshot.days.isEmpty()) {
                homeCardItem(key = "empty", modifier = dailyModifier) {
                    InsetGroup {
                        EmptyState(icon = AbIcons.Wallet,
                            title = "这个账期还没有账单",
                            message = "可切换账期查看其他记录。",
                            actionLabel = "记一笔", onAction = { addBill() })
                    }
                }
            } else if (dailySnapshot != null) {
                homeDailyCards(dailySnapshot, clock, onBillClick = { openBill(it) }, onBillLongPress = { bill, bounds ->
                    reimburseTarget = bill to bounds
                }, itemModifier = dailyModifier)
            }
            val samePeriodMore = dailyState.period == homePeriod
            if (dailySnapshot != null && (dailyState.failed || (dailyState.loading && samePeriodMore) || dailySnapshot.hasMore)) {
                homeCardItem(key = "daily-more", modifier = dailyModifier) {
                    InsetGroup {
                        ListRow(
                            title = when {
                                dailyState.failed -> "加载失败 · 重试"
                                dailyState.loading && samePeriodMore -> "正在加载…"
                                else -> "加载更早记录"
                            },
                            subtitle = "已显示 ${dailySnapshot.loadedCount} 笔",
                            onClick = if (dailyState.loading) null else ({
                                if (dailyState.failed) recentRetry++
                                else anchorLimit = nextHomeDailyAnchorLimit(anchorLimit, dailySnapshot.loadedCount)
                            }),
                        )
                    }
                }
            }
            homeCardItem(key = "history") {
                InsetGroup {
                    ListRow(title = "记录历史",
                        subtitle = when {
                            historyError != null -> "读取失败 · 可重试"
                            !historyReady -> "正在读取记录…"
                            else -> "查看识别记录与删除记录"
                        },
                        chevron = true, onClick = { historyFilter = "ALL"; historyOpen = true })
                }
            }
            if (!recognized && FirstRunGuide.configurationComplete(healthInput)) homeCardItem(key = "guide") {
                InsetGroup {
                    ListRow(title = "识别第一笔账单",
                        subtitle = "打开微信或支付宝交易详情页后自动识别。")
                }
            }

        }
        }
    }
    }
    if (healthDetails) AppDialog(title = "识别状态", onDismiss = { healthDetails = false }) {
        CaptureHealthCard(health) { action ->
            healthDetails = false
            performCaptureAction(context, action, onOpenSettings) {
                historyFilter = RecognitionStatus.PENDING; historyOpen = true
            }
        }
        SecondaryButton("关闭", onClick = { healthDetails = false }, modifier = Modifier.fillMaxWidth())
    }
    reimburseTarget?.let { (bill, bounds) ->
        ReimburseChoiceDialog(bill, anchor = bounds) { reimburseTarget = null }
    }
    io.github.mymoney.ui.design.SlideSubPageValue(value = selectedId, modifier = Modifier.fillMaxSize().zIndex(10f)) { id ->
        BillActionsHost(billId = id, onClose = { selectedId = null; sourcePreview = null }, sourcePreview = sourcePreview)
    }
    io.github.mymoney.ui.design.SlideSubPage(visible = historyOpen, modifier = Modifier.fillMaxSize().zIndex(9f)) {
        RecognitionHistoryScreen(onBack = { historyOpen = false }, initialFilter = historyFilter, reserveDock = false)
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

private fun pendingRecognition(bills: List<BillEntity>): Boolean =
    bills.any { FirstRunGuide.hasRecognizedSource(it.sourcePkg) }
