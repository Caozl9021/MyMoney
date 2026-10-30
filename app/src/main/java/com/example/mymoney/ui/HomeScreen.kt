package com.example.mymoney.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.zIndex
import com.example.billkit.DebugBus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.example.mymoney.data.CaptureMode
import com.example.mymoney.data.BillEntity
import com.example.mymoney.data.RecognitionStore
import com.example.mymoney.data.RecognitionStatus
import com.example.mymoney.ui.design.AbIcons
import com.example.mymoney.ui.design.AbRadius
import com.example.mymoney.ui.design.AbSpacing
import com.example.mymoney.ui.design.AbType
import com.example.mymoney.ui.design.sectionHeader
import com.example.mymoney.ui.design.EmptyState
import com.example.mymoney.ui.design.GroupFooter
import com.example.mymoney.ui.design.GroupHeader
import com.example.mymoney.ui.design.InsetDivider
import com.example.mymoney.ui.design.InsetGroup
import com.example.mymoney.ui.design.ListRow
import com.example.mymoney.ui.design.LocalAbPalette
import com.example.mymoney.ui.design.PageScaffold
import com.example.mymoney.ui.design.formatAmount

/** 首页：紧凑月概览、待处理与按完整自然日分组的账单卡片。 */
@Composable
fun HomeScreen(
    bills: List<BillEntity>,
    onOpenAssets: () -> Unit = {},
    onOpenSettings: (CaptureSettingsDestination) -> Unit,
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val clock = rememberLedgerClock()
    val monthStartDay = rememberMonthStartDay()
    var periodBack by androidx.compose.runtime.saveable.rememberSaveable(monthStartDay) { mutableIntStateOf(0) }
    val homePeriod = remember(clock, monthStartDay, periodBack) {
        com.example.mymoney.data.LedgerMonthPeriod.containing(clock.date, monthStartDay, clock.zone)
            .shift(-periodBack.toLong())
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
        newSource = "com.example.mymoney.manual.${java.util.UUID.randomUUID()}"
    }
    val currentVisibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(newSource, historyOpen, selectedId) { currentVisibilityChanged(newSource != null || historyOpen || selectedId != null) }
    DisposableEffect(Unit) { onDispose { currentVisibilityChanged(false) } }
    var recentRetry by remember { mutableIntStateOf(0) }
    var anchorLimit by androidx.compose.runtime.saveable.rememberSaveable(homePeriodKey) { mutableIntStateOf(HOME_DAILY_PAGE_SIZE) }
    val dailyState = rememberHomeDailyState(clock, homePeriod, anchorLimit, recentRetry)
    val dailySnapshot = dailyState.snapshot
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
    BackSurface(enabled = false, onBack = {}, pixels = pagePixels) {
    Box(Modifier.fillMaxSize()) {
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    PageScaffold(
        title = "账本",
        compact = true,
        floatingHeader = true,
        titleActive = healthInput.recognitionReady,
        titleStateDescription = if (healthInput.recognitionReady) "自动记账运行中" else health.title,
        onTitleClick = { onOpenSettings(CaptureSettingsDestination.MODE) },
        actions = {
            com.example.mymoney.ui.design.FloatingHeaderAction(AbIcons.Plus, "记一笔") { addBill() }
        },
        listState = listState,
        subtitle = clock.date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // 底部让位必须走 contentPadding —— 不能用 Modifier.padding(padding)：
            // 那会把 LazyColumn 的视口从底部削掉一截，视口之下露出整片 pageBg，
            // 悬浮胶囊正好压在这片纯色区里，看起来就是「胶囊下面一条实色横带」。
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "today-date") {
                Text(clock.date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 · EEEE", java.util.Locale.CHINA)),
                    style = AbType.caption1, color = p.secondaryLabel,
                    modifier = Modifier.padding(start = AbSpacing.xs, bottom = AbSpacing.xs))
            }
            // 本期支出保持主视觉，日期移到正文，不撑高四页统一顶栏。
            homeCardItem(key = "summary") {
                MonthlySummaryCard(clock, homePeriod, periodBack) { periodBack = it.coerceAtLeast(0) }
            }
            if (health.attention) homeCardItem(key = "runtime-attention") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(AbRadius.row))
                        .clickable { healthDetails = true }.heightIn(min = 48.dp)
                        .padding(vertical = AbSpacing.xs), verticalArrangement = Arrangement.Center) {
                        Text(if (health.title.startsWith("已开启")) "自动记账需处理" else health.title, style = AbType.footnote, color = p.label)
                        Text("点此查看原因", style = AbType.caption1, color = p.secondaryLabel)
                    }
                    health.actions.firstOrNull()?.let { action ->
                        TextButton(onClick = {
                            performCaptureAction(context, action, onOpenSettings) {
                                historyFilter = RecognitionStatus.PENDING; historyOpen = true
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("处理", color = p.accent) }
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
                homeCardItem(key = "empty") {
                    InsetGroup {
                        EmptyState(icon = AbIcons.Wallet,
                            title = "这个账期还没有账单",
                            message = "可切换账期查看其他记录。",
                            actionLabel = "记一笔", onAction = { addBill() })
                    }
                }
            } else if (dailySnapshot != null) {
                homeDailyCards(dailySnapshot, clock) { openBill(it) }
            }
            if (dailySnapshot != null && (dailyState.failed || dailyState.loading || dailySnapshot.hasMore)) {
                homeCardItem(key = "daily-more") {
                    InsetGroup {
                        ListRow(
                            title = when {
                                dailyState.failed -> "加载失败 · 重试"
                                dailyState.loading -> "正在加载…"
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
                    ListRow(title = "识别历史",
                        subtitle = when {
                            historyError != null -> "读取失败 · 可重试"
                            !historyReady -> "正在读取记录…"
                            else -> "全部 ${history.size} 条 · 含已记账与已忽略"
                        },
                        chevron = true, onClick = { historyFilter = "ALL"; historyOpen = true })
                }
            }
            if (!recognized && FirstRunGuide.configurationComplete(healthInput)) homeCardItem(key = "guide") {
                InsetGroup {
                    ListRow(title = "识别第一笔账单",
                        subtitle = "在所选应用收到交易通知或打开支付结果后，识别结果会进入待确认。")
                }
            }

        }
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = AbSpacing.page)
            .padding(bottom = padding.calculateBottomPadding())) { BillUndoStack() }
        }
    }
    if (healthDetails) AlertDialog(
        onDismissRequest = { healthDetails = false },
        title = { Text("识别状态") },
        text = { CaptureHealthCard(health) { action ->
            healthDetails = false
            performCaptureAction(context, action, onOpenSettings) {
                historyFilter = RecognitionStatus.PENDING; historyOpen = true
            }
        } },
        confirmButton = { TextButton(onClick = { healthDetails = false }) { Text("关闭") } },
    )
    selectedId?.let { id ->
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            BillActionsHost(billId = id, onClose = { selectedId = null; sourcePreview = null }, sourcePreview = sourcePreview)
        }
    }
    if (historyOpen) Box(Modifier.fillMaxSize().zIndex(9f)) {
        RecognitionHistoryScreen(onBack = { historyOpen = false }, initialFilter = historyFilter, reserveDock = false)
    }
    if (newSource != null) key(newSource) {
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            BillEditorDialog(null, newSource.orEmpty(), newTime, onClose = { newSource = null })
        }
    }
    }
    }
}

private fun pendingRecognition(bills: List<BillEntity>): Boolean =
    bills.any { FirstRunGuide.hasRecognizedSource(it.sourcePkg) }
