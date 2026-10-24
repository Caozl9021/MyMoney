package com.example.autobook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.capture.ConfirmIntents
import com.example.autobook.data.*
import com.example.autobook.ui.design.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent history is separate from the ledger; recorded entries never offer another insert. */
@Composable
fun RecognitionHistoryScreen(onBack: () -> Unit, initialFilter: String = "ALL") {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val scope = rememberCoroutineScope()
    val records by RecognitionStore.records.collectAsStateWithLifecycle()
    val ready by RecognitionStore.ready.collectAsStateWithLifecycle()
    val storeError by RecognitionStore.error.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(true) }
    val statuses = listOf("ALL", RecognitionStatus.PENDING, RecognitionStatus.RECORDED, RecognitionStatus.IGNORED)
    val labels = listOf("全部", "待处理", "已记账", "已忽略")
    // Keep an independent scroll position for every filter, including after confirmation returns.
    val positions = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val listState = positions[statuses.indexOf(filter).coerceAtLeast(0)]
    val visible = remember(records, filter) { records.filter { filter == "ALL" || it.status == filter } }
    val db = remember(context) { AppDb.get(context) }
    val linkedIds by remember(db) {
        db.bills().observeIds().map<List<Long>, Set<Long>?> { it.toHashSet() }
            .catch { messageIsError = true; message = "读取关联账单失败：${it.message}"; emit(null) }
    }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(Unit) { RecognitionStore.init(context) }
    BackHandler(enabled = !confirmClear) { onBack() }
    SubPageScaffold(title = "识别历史", onBack = onBack) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    statuses.forEachIndexed { index, status ->
                        CapsuleChip(text = labels[index], selected = filter == status,
                            onClick = { filter = status })
                    }
                }
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
            if (!ready && storeError == null) item(key = "loading") {
                GroupFooter("正在读取本地识别历史…")
            }
            if (ready && visible.isEmpty()) item(key = "empty") {
                InsetGroup { EmptyState(icon = AbIcons.Clock, title = if (filter == "ALL") "暂无识别历史" else "暂无${labels[statuses.indexOf(filter)]}记录",
                    message = "成功识别的账单会保存在这里；手动记账不产生识别历史。") }
            }
            items(visible, key = { it.id }) { row ->
                InsetGroup {
                    Column(Modifier.fillMaxWidth().padding(AbSpacing.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.merchant ?: "识别到一笔账单", style = AbType.headline, color = p.label)
                        Text("${historyType(row.type)} ${formatAmount(row.priceCents)}", style = AbType.headline, color = p.label)
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
                        if (row.status == RecognitionStatus.RECORDED) {
                            InlineMessage(when {
                                linkedIds == null -> "正在核对关联账单…"
                                row.billId != null && row.billId in linkedIds.orEmpty() -> "已保存到本地账本（账单 #${row.billId}）"
                                else -> "关联账单已删除或不可用"
                            })
                        }
                        if (row.shareState != RecognitionShareState.NOT_REQUESTED) InlineMessage(
                            if (row.shareState == RecognitionShareState.REQUESTED) "分享已发起，外部导入结果需在目标应用确认" else "分享失败，本地记账状态以本页为准",
                            isError = row.shareState != RecognitionShareState.REQUESTED)
                        if (row.note != null) Text("备注：${row.note}", style = AbType.footnote, color = p.secondaryLabel)
                    }
                    if (row.status != RecognitionStatus.RECORDED) ListRow(
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
            if (ready) item(key = "retention") {
                GroupFooter("记录仅存本机，默认保留。关闭确认卡会保持待处理；已记账记录只读。")
                if (records.any { it.status == RecognitionStatus.IGNORED }) DestructiveButton(
                    text = "清理已忽略记录", enabled = !busy,
                    onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth().padding(top = AbSpacing.s))
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { if (!busy) confirmClear = false },
        title = { Text("清理已忽略记录？") },
        text = { Text("将永久删除 ${records.count { it.status == RecognitionStatus.IGNORED }} 条已忽略的识别记录，之后无法重新确认。不会删除账本、待处理或已记账的历史记录。建议先导出 JSON 备份。") },
        confirmButton = { DestructiveButton(text = "确认清理", busy = busy, busyLabel = "正在清理…", onClick = {
            busy = true
            scope.launch {
                runCatching { RecognitionStore.clearIgnored() }
                    .onSuccess { messageIsError = false; message = "已清理 $it 条已忽略记录，账本未删除" }
                    .onFailure { messageIsError = true; message = "清理失败：${it.message}" }
                busy = false
                confirmClear = false
            }
        }) },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmClear = false }) { Text("取消") } })
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
