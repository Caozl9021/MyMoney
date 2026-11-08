package io.github.mymoney.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.capture.ConfirmIntents
import io.github.mymoney.data.*
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent history is separate from the ledger; recorded entries never offer another insert. */
@Composable
fun RecognitionHistoryScreen(onBack: () -> Unit, initialFilter: String = "ALL", reserveDock: Boolean = true, onDetailVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val scope = rememberCoroutineScope()
    val records by RecognitionStore.records.collectAsStateWithLifecycle()
    val ready by RecognitionStore.ready.collectAsStateWithLifecycle()
    val storeError by RecognitionStore.error.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var timeline by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(true) }
    val statuses = listOf("ALL", RecognitionStatus.PENDING, RecognitionStatus.RECORDED, RecognitionStatus.IGNORED, "DELETED")
    val labels = listOf("全部", "待处理", "已记账", "已忽略", "已删除")
    // Keep an independent scroll position for every filter, including after confirmation returns.
    val positions = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val listState = positions[statuses.indexOf(filter).coerceAtLeast(0)]
    val db = remember(context) { AppDb.get(context) }
    val deletions by remember(db) {
        db.deletions().observeAll().map<List<BillDeletionLog>, List<BillDeletionLog>?> { it }
            .catch { messageIsError = true; message = "读取删除记录失败：${it.message}"; emit(null) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val visible = remember(records, deletions, filter) { recordHistoryEvents(records, deletions.orEmpty(), filter) }
    var selectedBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailVisibilityChanged by rememberUpdatedState(onDetailVisibilityChanged)
    LaunchedEffect(selectedBillId, selectedDeletionId) { detailVisibilityChanged(selectedBillId != null || selectedDeletionId != null) }
    DisposableEffect(Unit) { onDispose { detailVisibilityChanged(false) } }
    LaunchedEffect(deletions, selectedDeletionId) {
        if (deletions != null && selectedDeletionId != null && deletions.orEmpty().none { it.eventId == selectedDeletionId }) {
            selectedDeletionId = null
            messageIsError = true
            message = "该删除记录已不可用，请重新选择"
        }
    }
    val linkedIds by remember(db) {
        db.bills().observeIds().map<List<Long>, Set<Long>?> { it.toHashSet() }
            .catch { messageIsError = true; message = "读取关联账单失败：${it.message}"; emit(null) }
    }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(Unit) { RecognitionStore.init(context) }
    ActiveBackHandler(enabled = !confirmClear && selectedBillId == null && selectedDeletionId == null && !LocalPageBackManaged.current) { onBack() }
    SubPageScaffold(title = "记录历史", onBack = onBack, reserveDock = reserveDock) { padding ->
        io.github.mymoney.ui.design.SectionLazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            item(key = "filters") {
                RecordHistorySegments(labels, statuses.indexOf(filter).coerceAtLeast(0)) { filter = statuses[it] }
            }
            if (storeError != null || message != null) item(key = "error") {
                InsetGroup {
                    if (storeError != null) InlineMessage(storeError.orEmpty(), isError = true, announce = true,
                        modifier = Modifier.padding(AbSpacing.m))
                    if (message != null) InlineMessage(message.orEmpty(), isError = messageIsError, announce = true,
                        modifier = Modifier.padding(AbSpacing.m))
                    if (storeError != null) ListRow(title = "重试读取", onClick = { RecognitionStore.init(context) })
                }
            }
            if (ready && deletions != null && visible.isEmpty()) item(key = "empty") {
                InsetGroup { EmptyState(icon = AbIcons.Clock, title = if (filter == "ALL") "暂无记录历史" else "暂无${labels[statuses.indexOf(filter)]}记录",
                    message = "这里保留识别记录与删除记录。手动记账删除后也会留下记录。") }
            }
            items(visible, key = { it.key }) { event ->
                val deletion = event.deletion
                if (deletion != null) {
                    DeletedHistoryCard(deletion) { selectedDeletionId = deletion.eventId }
                } else {
                    val row = requireNotNull(event.recognition)
                    val removed = deletions.orEmpty().firstOrNull {
                        it.recognitionId == row.id || (it.recognitionId == null && row.billId != null && it.principalId == row.billId)
                    }
                    InsetGroup {
                        Column(Modifier.fillMaxWidth().padding(AbSpacing.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.merchant ?: "识别到一笔账单", style = AbType.headline, color = p.label)
                            Text("${historyType(row.type)} ${formatAmount(row.priceCents)}", style = AbType.headline, color = amountTint(p, row.type))
                            StatusLabel(historyStatus(row.status), emphasized = row.status == RecognitionStatus.PENDING,
                                icon = when (row.status) {
                                    RecognitionStatus.PENDING -> AbIcons.Clock
                                    RecognitionStatus.RECORDED -> AbIcons.Check
                                    RecognitionStatus.IGNORED -> AbIcons.Close
                                    else -> null
                                })
                            Text("${row.category ?: "未分类"}${row.subCategory?.let { " / $it" }.orEmpty()}",
                                style = AbType.body, color = p.secondaryLabel)
                            val legacy = row.identityKey.startsWith("legacy:")
                            Text("${if (legacy) "迁移时间" else "识别时间"}：${historyTime(row.recognizedAt)}\n账单时间：${historyTime(row.timeMillis)}${when {
                                legacy -> "（旧草稿时间，来源未记录）"
                                row.hasExplicitTime -> ""
                                else -> "（页面未提供，使用识别时间）"
                            }}\n来源：${row.sourcePkg}",
                                style = AbType.caption1, color = p.secondaryLabel)
                            if (row.reviewOf != null && row.status == RecognitionStatus.PENDING) {
                                InlineMessage("待核对：另一来源可能是同一笔交易。未自动合并，也未再次弹框；请比较金额和交易信息。")
                            }
                            if (row.status == RecognitionStatus.RECORDED) {
                                InlineMessage(when {
                                    linkedIds == null -> "正在核对关联账单…"
                                    row.billId != null && row.billId in linkedIds.orEmpty() -> "已保存到本地账本（账单 #${row.billId}）"
                                    removed != null -> "关联账单已删除，可查看删除时的记录"
                                    else -> "关联账单已删除或不可用"
                                })
                            }
                            if (row.shareState != RecognitionShareState.NOT_REQUESTED) InlineMessage(
                                if (row.shareState == RecognitionShareState.REQUESTED) "分享已发起，外部导入结果需在目标应用确认" else "分享失败，本地记账状态以本页为准",
                                isError = row.shareState != RecognitionShareState.REQUESTED)
                            if (row.note != null) Text("备注：${row.note}", style = AbType.footnote, color = p.secondaryLabel)
                        }
                        if (row.status == RecognitionStatus.RECORDED) {
                            if (row.billId != null && row.billId in linkedIds.orEmpty()) ListRow(
                                title = "查看账单", chevron = true, onClick = { selectedBillId = row.billId })
                            else if (removed != null) ListRow(title = "查看删除记录", chevron = true,
                                onClick = { selectedDeletionId = removed.eventId })
                        }
                        ListRow(title = "来源与投递记录", onClick = {
                            scope.launch {
                                runCatching {
                                    val events = db.capture().forRecord(row.id)
                                    events.joinToString("\n\n") {
                                        val merged = if ("merge=${CaptureShortWindowPolicy.RULE}" in it.detail) {
                                            val interval = Regex("(?:^|;)intervalMs=(\\d+)").find(it.detail)?.groupValues?.get(1)?.toLongOrNull()
                                            "\n已自动合并：跨通道同额，间隔 ${interval?.let { ms -> String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0) } ?: "未知"} 秒"
                                        } else ""
                                        "${historyTime(it.receivedAt)} · ${it.source}\n${it.outcome}$merged\n${it.detail}"
                                    }
                                        .ifBlank { "旧记录没有来源时间线；不会补造通知证据。" }
                                }.onSuccess { timeline = it }.onFailure { messageIsError = true; message = it.message }
                            }
                        })
                        if (row.reviewOf != null && row.status == RecognitionStatus.PENDING) {
                            ListRow(title = "作为独立账单确认", chevron = true, onClick = {
                                if (!busy) { busy = true; scope.launch {
                                    runCatching {
                                        val draft = RecognitionStore.resolveSeparate(row.id) ?: error("该记录已处理")
                                        context.startActivity(ConfirmIntents.intent(context, draft))
                                    }.onFailure { messageIsError = true; message = it.message }
                                    busy = false
                                } }
                            })
                            ListRow(title = "与原记录合并来源", onClick = {
                                if (!busy) { busy = true; scope.launch {
                                    runCatching { RecognitionStore.mergeReview(row.id) }
                                        .onFailure { messageIsError = true; message = it.message }
                                    busy = false
                                } }
                            })
                        }
                        if (row.status != RecognitionStatus.RECORDED && (row.reviewOf == null || row.status == RecognitionStatus.IGNORED)) ListRow(
                            title = if (busy) "处理中…" else if (row.status == RecognitionStatus.IGNORED) "重新确认" else "继续确认",
                            chevron = true, onClick = {
                                if (!busy) {
                                    busy = true
                                    scope.launch {
                                        runCatching {
                                            val draft = RecognitionStore.reopen(row.id) ?: error("这条记录已记账或不可用，请查看最新状态")
                                            context.startActivity(ConfirmIntents.intent(context, draft))
                                        }.onFailure { messageIsError = true; message = "无法打开确认卡：${it.message}" }
                                        busy = false
                                    }
                                }
                            })
                    }
                }
            }
            if (ready && filter in listOf("ALL", RecognitionStatus.IGNORED)) item(key = "retention") {
                if (records.any { it.status == RecognitionStatus.IGNORED }) DestructiveButton(
                    text = "清理已忽略记录", enabled = !busy,
                    onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth().padding(top = AbSpacing.s))
            }
        }
    }
    selectedBillId?.let { id -> BillActionsHost(billId = id, onClose = { selectedBillId = null }) }
    selectedDeletionId?.let { eventId ->
        deletions.orEmpty().firstOrNull { it.eventId == eventId }?.let { removed ->
            BillActionsHost(billId = removed.principalId, onClose = { selectedDeletionId = null }, deletedSnapshot = removed)
        }
    }
    timeline?.let { text -> AppAlertDialog(
        title = "来源与投递记录",
        message = text,
        onDismiss = { timeline = null },
        confirmLabel = "关闭",
        dismissLabel = null,
        onConfirm = { timeline = null },
    ) }
    if (confirmClear) AppAlertDialog(
        title = "清理已忽略记录？",
        message = "将永久删除 ${records.count { it.status == RecognitionStatus.IGNORED }} 条已忽略的识别记录，之后无法重新确认。不会删除账本、待处理、已记账或删除历史记录。建议先导出 JSON 备份。",
        onDismiss = { if (!busy) confirmClear = false },
        confirmLabel = "确认清理",
        onConfirm = {
            busy = true
            scope.launch {
                runCatching { RecognitionStore.clearIgnored() }
                    .onSuccess { messageIsError = false; message = "已清理 $it 条已忽略记录，账本未删除" }
                    .onFailure { messageIsError = true; message = "清理失败：${it.message}" }
                busy = false
                confirmClear = false
            }
        },
        destructive = true,
        busy = busy,
        busyLabel = "正在清理…",
    )
}

private fun historyTime(value: Long): String = if (value <= 0L) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))
private fun historyStatus(value: String): String = when (value) {
    RecognitionStatus.PENDING -> "待处理"
    RecognitionStatus.RECORDED -> "已记账"
    RecognitionStatus.IGNORED -> "已忽略"
    else -> "未知状态"
}
private fun historyType(value: String): String = when (value) {
    "INCOME" -> "收入"
    "REFUND" -> "退款"
    "TRANSFER" -> "转账"
    else -> "支出"
}


/** Separate event identity prevents a recorded row and its later deletion from replacing each other. */
internal data class RecordHistoryEvent(val recognition: RecognitionEntity? = null, val deletion: BillDeletionLog? = null) {
    val key: String get() = recognition?.let { "recognition:${it.id}" } ?: "deletion:${requireNotNull(deletion).eventId}"
    val time: Long get() = recognition?.recognizedAt ?: requireNotNull(deletion).deletedAt
}

internal fun recordHistoryEvents(records: List<RecognitionEntity>, deletions: List<BillDeletionLog>, filter: String): List<RecordHistoryEvent> =
    buildList {
        if (filter != "DELETED") records.filter { filter == "ALL" || it.status == filter }
            .forEach { add(RecordHistoryEvent(recognition = it)) }
        if (filter == "ALL" || filter == "DELETED") deletions.forEach { add(RecordHistoryEvent(deletion = it)) }
    }.sortedWith(compareByDescending<RecordHistoryEvent> { it.time }.thenBy { it.key })

@Composable
private fun RecordHistorySegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val segmentWidth = maxOf(maxWidth, 64.dp * labels.size * density.fontScale.coerceAtLeast(1f))
        val viewport = maxWidth
        LaunchedEffect(selected, segmentWidth, viewport) {
            val target = with(density) { (segmentWidth / labels.size * (selected + 0.5f) - viewport / 2).toPx().toInt() }
                .coerceAtLeast(0)
            if (reduceMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Segmented(labels, selected, onSelect, modifier = Modifier.width(segmentWidth))
        }
    }
}

@Composable
private fun DeletedHistoryCard(log: BillDeletionLog, onOpen: () -> Unit) {
    val p = LocalAbPalette.current
    val snapshot = remember(log) { runCatching { BillDeletionCodec.bills(log) } }
    val rows = snapshot.getOrNull().orEmpty()
    val bill = rows.firstOrNull()
    InsetGroup {
        Column(Modifier.fillMaxWidth().padding(AbSpacing.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(bill?.merchant?.takeIf(String::isNotBlank) ?: "已删除账单", style = AbType.headline, color = p.label)
            bill?.let {
                Text("${historyType(it.type)} ${formatAmount(it.priceCents)}", style = AbType.headline, color = amountTint(p, it.type))
                Text("${it.category?.takeIf(String::isNotBlank) ?: "未分类"} · ${it.account?.takeIf(String::isNotBlank) ?: "未知账户"}",
                    style = AbType.body, color = p.secondaryLabel)
                Text("删除时间：${historyTime(log.deletedAt)}\n账单时间：${historyTime(it.timeMillis)}",
                    style = AbType.caption1, color = p.secondaryLabel)
            }
            StatusLabel("已删除", icon = AbIcons.Close)
            if (rows.size > 1) InlineMessage("包含随原账单一起删除的 ${rows.size - 1} 条关联手续费记录")
            if (snapshot.isFailure) InlineMessage("无法读取删除时的账单快照，请检查备份或重试。", isError = true)
        }
        ListRow(title = "查看删除记录", subtitle = "只读，不支持恢复账单", chevron = true, onClick = onOpen)
    }
}
