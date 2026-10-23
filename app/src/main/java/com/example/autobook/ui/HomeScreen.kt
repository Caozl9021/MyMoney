package com.example.autobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.zIndex
import com.example.autobook.capture.AccessibilityGuardian
import com.example.autobook.root.RootWatcher
import com.example.billkit.DebugBus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.data.CaptureMode
import com.example.autobook.capture.ConfirmIntents
import com.example.autobook.data.BillEntity
import com.example.autobook.data.PendingStore
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
import com.example.autobook.ui.design.IconBadge
import com.example.autobook.ui.design.formatAmount
import com.example.autobook.ui.design.swatchFor

/** 首页：本月概览 + 最近几笔 + 去明细。 */
@Composable
fun HomeScreen(
    bills: List<BillEntity>,
    onSeeAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
) {
    val p = LocalAbPalette.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val captureState by CaptureModeManager.state.collectAsStateWithLifecycle()
    val mode = captureState.mode
    val rootStatus by RootWatcher.status.collectAsStateWithLifecycle()
    val recognition by DebugBus.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    var permissionRevision by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val prefs = remember { context.getSharedPreferences("first_run_guide", android.content.Context.MODE_PRIVATE) }
    var guideComplete by remember { mutableStateOf(prefs.getBoolean("complete", false)) }
    var recognized by remember { mutableStateOf(prefs.getBoolean("recognized", false)) }
    // Binding can finish after ON_RESUME; only poll while the unfinished guide is visible.
    LaunchedEffect(lifecycle, guideComplete) {
        if (!guideComplete) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(1000); permissionRevision++ }
        }
    }
    LaunchedEffect(recognition, pendingRecognition(bills)) {
        if (recognition?.outcome?.startsWith("draft:") == true || pendingRecognition(bills)) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    val overlayAllowed = remember(permissionRevision) { android.provider.Settings.canDrawOverlays(context) }
    val accessibilityBound = remember(permissionRevision, captureState) { AccessibilityGuardian.isAccessibilityEnabled(context) }
    val steps = FirstRunGuide.steps(mode, captureState.enabled, rootStatus.running,
        accessibilityBound, overlayAllowed, recognized)
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
    LaunchedEffect(newSource != null) { onEditorVisibilityChanged(newSource != null) }
    DisposableEffect(Unit) { onDispose { onEditorVisibilityChanged(false) } }
    val recent = remember(bills) { bills.take(8) }
    // 后台被系统拦下、只落进队列的草稿 —— 必须在这里露出来，
    // 否则用户永远不知道"其实已经识别到了一笔"。
    val pending by remember { com.example.autobook.data.PendingStore.items }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Box(Modifier.fillMaxSize()) {
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            recognized = true
            prefs.edit().putBoolean("recognized", true).apply()
        }
    }
    PageScaffold(
        title = "记账",
        listState = listState,
        subtitle = "自动识别 · 本地账本",
    ) { padding ->
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
            if (pending.isNotEmpty()) {
                item(key = "pending") {
                    PendingSection(
                        drafts = pending,
                        onOpen = { draft ->
                            context.startActivity(ConfirmIntents.intent(context, draft))
                        },
                    )
                }
            }

            item(key = "summary") { MonthlySummaryCard(bills) }
            if (!guideComplete) item(key = "guide") {
                Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    GroupHeader("开始自动记账")
                    InsetGroup {
                        steps.forEachIndexed { index, step ->
                            if (index > 0) InsetDivider()
                            ListRow(title = "${if (step.complete) "✓" else "${index + 1}."} ${step.label}",
                                onClick = onOpenSettings)
                        }
                    }
                    GroupFooter("完成一次真实识别后，引导会自动收起。也可以随时手动记账。")
                }
            }
            if (bills.isEmpty()) {
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
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }

                item(key = "recent-list") {
                    InsetGroup {
                        recent.forEachIndexed { index, bill ->
                            if (index > 0) InsetDivider(inset = 62.dp)
                            BillListRow(bill)
                        }
                    }
                }

                item(key = "footer") {
                    Text(
                        text = "共 ${bills.size} 条记录 · 数据仅存本机",
                        style = AbType.caption1,
                        color = p.tertiaryLabel,
                        modifier = Modifier.padding(start = AbSpacing.xs),
                    )
                }
            }

        }
    }
    if (newSource != null) key(newSource) {
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            BillEditorDialog(null, newSource.orEmpty(), newTime, onClose = { newSource = null })
        }
    }
    }
}

/**
 * 待确认区：识别成功但确认卡没能弹出来（被系统 BAL 拦下、或通知权限没给）的草稿。
 * 没有这一块，用户会觉得"识别到了却什么都没发生"。
 */
@Composable
private fun PendingSection(
    drafts: List<com.example.billkit.BillDraft>,
    onOpen: (com.example.billkit.BillDraft) -> Unit,
) {
    val p = LocalAbPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
        GroupHeader("待确认 ${drafts.size} 笔")
        InsetGroup {
            drafts.takeLast(3).reversed().forEachIndexed { index, draft ->
                if (index > 0) InsetDivider(inset = 62.dp)
                ListRow(
                    title = draft.merchant ?: "识别到一笔账单",
                    subtitle = buildString {
                        append(formatAmount(draft.priceCents))
                        draft.method?.let { append(" · ").append(it) }
                    },
                    leading = {
                        IconBadge(
                            icon = AbIcons.Clock,
                            tint = swatchFor(draft.category ?: draft.sourcePkg),
                        )
                    },
                    trailing = {
                        Text(
                            "去确认",
                            style = AbType.footnote,
                            color = p.accent,
                        )
                    },
                    onClick = { onOpen(draft) },
                )
            }
        }
        GroupFooter("确认卡被系统拦下时会先存到这里，点一行即可补记。")
    }
}

private fun pendingRecognition(bills: List<BillEntity>): Boolean =
    bills.any { FirstRunGuide.hasRecognizedSource(it.sourcePkg) }
