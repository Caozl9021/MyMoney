package com.example.autobook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.zIndex
import com.example.billkit.DebugBus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import com.example.autobook.data.AppDb
import androidx.compose.ui.unit.dp
import com.example.autobook.data.CaptureMode
import com.example.autobook.data.BillEntity
import com.example.autobook.data.RecognitionStore
import com.example.autobook.data.RecognitionStatus
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.BillListRow
import com.example.autobook.ui.design.EmptyState
import com.example.autobook.ui.design.GroupFooter
import com.example.autobook.ui.design.GroupHeader
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.ListRow
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PageScaffold
import com.example.autobook.ui.design.formatAmount

/** 首页：完整今日摘要、待处理与紧凑月概览、最近几笔。 */
@Composable
fun HomeScreen(
    bills: List<BillEntity>,
    onSeeAll: () -> Unit,
    onOpenSettings: (CaptureSettingsDestination) -> Unit,
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val clock = rememberLedgerClock()
    var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var historyOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var historyFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("ALL") }
    val healthInput = rememberCaptureHealthInput()
    val health = captureHealth(healthInput)
    val mode = healthInput.mode
    val recognition by DebugBus.state.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("first_run_guide", android.content.Context.MODE_PRIVATE) }
    var guideComplete by remember { mutableStateOf(prefs.getBoolean("complete", false)) }
    var recognized by remember { mutableStateOf(prefs.getBoolean("recognized", false)) }
    LaunchedEffect(recognition, pendingRecognition(bills)) {
        if (recognition?.outcome?.startsWith("draft:") == true || pendingRecognition(bills)) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    val steps = FirstRunGuide.steps(mode, healthInput.enabled, healthInput.rootRunning,
        healthInput.accessibilityAuthorized && healthInput.accessibilityBound, healthInput.overlayAllowed, recognized)
    LaunchedEffect(steps) {
        if (steps.all { it.complete }) {
            guideComplete = true
            prefs.edit().putBoolean("complete", true).apply()
        }
    }
    var newSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var newTime by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0L) }
    fun addBill() {
        newTime = System.currentTimeMillis()
        newSource = "com.example.autobook.manual.${java.util.UUID.randomUUID()}"
    }
    val currentVisibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(newSource, historyOpen, selectedId) { currentVisibilityChanged(newSource != null || historyOpen || selectedId != null) }
    DisposableEffect(Unit) { onDispose { currentVisibilityChanged(false) } }
    var recentRetry by remember { mutableIntStateOf(0) }
    val recentState = rememberHomeRecentState(recentRetry)
    val recent = (recentState as? HomeRecentState.Ready)?.bills.orEmpty()
    val history by RecognitionStore.records.collectAsStateWithLifecycle()
    val historyReady by RecognitionStore.ready.collectAsStateWithLifecycle()
    val historyError by RecognitionStore.error.collectAsStateWithLifecycle()
    val pending = remember(history) { history.filter { it.status == RecognitionStatus.PENDING } }

    Box(Modifier.fillMaxSize()) {
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    PageScaffold(
        title = "${clock.date.year}年${clock.date.monthValue}月",
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
                top = AbSpacing.xs,
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.l),
        ) {
            // 本月支出是首页的主视觉，放在第一屏最上面；今日摘要降为它下面的一行辅助信息。
            item(key = "summary") { MonthlySummaryCard(clock) }
            if (health.attention) item(key = "runtime-attention") {
                InsetGroup {
                    CaptureHealthCard(health) { action ->
                        performCaptureAction(context, action, onOpenSettings) {
                            historyFilter = RecognitionStatus.PENDING; historyOpen = true
                        }
                    }
                }
            }
            if (!historyReady || historyError != null || pending.isNotEmpty()) item(key = "pending") {
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    GroupHeader(if (historyError != null) "识别记录读取失败" else if (!historyReady) "正在读取识别记录" else "待处理 ${pending.size} 笔")
                    InsetGroup {
                        ListRow(
                            title = if (historyError != null) "重新读取" else if (!historyReady) "正在读取待处理账单…" else "继续处理",
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
            item(key = "today") { TodaySummaryCard(clock) }
            if (recentState == HomeRecentState.Loading) {
                item(key = "recent-loading") {
                    InsetGroup { ListRow(title = "正在读取最近记录…") }
                }
            } else if (recentState == HomeRecentState.Failed) {
                item(key = "recent-error") {
                    InsetGroup {
                        ListRow(title = "最近记录读取失败", subtitle = "点此重新加载",
                            onClick = { recentRetry++ })
                    }
                }
            } else if (recent.isEmpty()) {
                item(key = "empty") {
                    InsetGroup {
                        EmptyState(
                            icon = AbIcons.Wallet,
                            title = "还没有记录",
                            message = "先记下一笔，也可以按上方引导开启自动识别。",
                            actionLabel = "记一笔",
                            onAction = { addBill() },
                        )
                    }
                }
            } else {
                item(key = "recent-header") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = AbSpacing.xs, end = AbSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        GroupHeader("最近记录")
                        Text(
                            "全部",
                            style = AbType.footnote,
                            color = p.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AbRadius.capsule))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onSeeAll,
                                )
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 6.dp, vertical = 14.dp),
                        )
                    }
                }

                item(key = "recent-list") {
                    InsetGroup {
                        recent.forEachIndexed { index, bill ->
                            if (index > 0) InsetDivider(inset = 62.dp)
                            BillListRow(bill, modifier = Modifier.clickable { selectedId = bill.id })
                        }
                    }
                }

            }
            item(key = "history") {
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
            if (!health.attention) item(key = "runtime") {
                InsetGroup {
                    CaptureHealthCard(health) { action ->
                        performCaptureAction(context, action, onOpenSettings) {
                            historyFilter = RecognitionStatus.PENDING; historyOpen = true
                        }
                    }
                }
            }
            if (!guideComplete) item(key = "guide") {
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    GroupHeader("开始自动记账")
                    InsetGroup {
                        steps.forEachIndexed { index, step ->
                            if (index > 0) InsetDivider()
                            ListRow(title = "${if (step.complete) "✓" else "${index + 1}."} ${step.label}",
                                onClick = {
                                    val action = when (index) {
                                        0 -> when {
                                            mode == null -> CaptureAction.SELECT_MODE
                                            !healthInput.enabled -> CaptureAction.RESUME
                                            mode == CaptureMode.ROOT -> CaptureAction.ROOT_STATUS
                                            else -> CaptureAction.ACCESSIBILITY
                                        }
                                        1 -> CaptureAction.OVERLAY
                                        else -> CaptureAction.SUPPORTED_APPS
                                    }
                                    performCaptureAction(context, action, onOpenSettings) {
                                        historyFilter = RecognitionStatus.PENDING; historyOpen = true
                                    }
                                })
                        }
                    }
                    GroupFooter("完成一次真实识别后，引导会自动收起。也可以随时手动记账。")
                }
            }

        }
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = AbSpacing.page)
            .padding(bottom = padding.calculateBottomPadding())) { BillUndoStack() }
        }
    }
    selectedId?.let { id ->
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            BillActionsHost(billId = id, onClose = { selectedId = null })
        }
    }
    if (historyOpen) Box(Modifier.fillMaxSize().zIndex(9f)) {
        RecognitionHistoryScreen(onBack = { historyOpen = false }, initialFilter = historyFilter)
    }
    if (newSource != null) key(newSource) {
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            BillEditorDialog(null, newSource.orEmpty(), newTime, onClose = { newSource = null })
        }
    }
    }
}

private fun pendingRecognition(bills: List<BillEntity>): Boolean =
    bills.any { FirstRunGuide.hasRecognizedSource(it.sourcePkg) }


private sealed interface HomeRecentState {
    data object Loading : HomeRecentState
    data object Failed : HomeRecentState
    data class Ready(val bills: List<BillEntity>) : HomeRecentState
}

/** Empty is a successful database result, never the shell's initial empty placeholder. */
@Composable
private fun rememberHomeRecentState(retry: Int): HomeRecentState {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { AppDb.get(context).bills() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember(dao, retry) { mutableStateOf<HomeRecentState>(HomeRecentState.Loading) }
    LaunchedEffect(dao, lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dao.observeWindow(8)
                .map<List<BillEntity>, HomeRecentState> { HomeRecentState.Ready(it) }
                .catch { emit(HomeRecentState.Failed) }
                .collect { state = it }
        }
    }
    return state
}
