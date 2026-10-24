package com.example.autobook.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.data.AppDb
import com.example.autobook.data.BillEntity
import com.example.autobook.data.BillWriter
import com.example.autobook.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Both ledger entry points use the same ID-backed, same-window operation layer. */
@Composable
fun BillActionsHost(
    billId: Long,
    onClose: () -> Unit,
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext
    val dao = remember(app) { AppDb.get(app).bills() }
    var retry by remember(billId) { mutableStateOf(0) }
    val read by remember(dao, billId, retry) {
        dao.observeById(billId).map { BillRead(loaded = true, bill = it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(BillRead(error = error.message ?: "读取账单失败，请重试"))
            }
    }.collectAsStateWithLifecycle(initialValue = BillRead())
    val undoItems by BillWriter.pendingUndo.collectAsStateWithLifecycle()
    var detail by rememberSaveable(billId) { mutableStateOf(false) }
    var recognitionExpanded by rememberSaveable(billId) { mutableStateOf(false) }
    var editing by rememberSaveable(billId) { mutableStateOf(false) }
    var deleting by remember(billId) { mutableStateOf(false) }
    var deleteError by remember(billId) { mutableStateOf<String?>(null) }
    var amountBounds by remember(billId) { mutableStateOf<Rect?>(null) }
    val summaryScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    val visibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    val close by rememberUpdatedState(onClose)
    val p = LocalAbPalette.current

    DisposableEffect(billId) {
        visibilityChanged(true)
        onDispose { visibilityChanged(false) }
    }
    // deleteUndoably has no completion callback. Only database absence or its undo entry
    // is positive evidence: a tap alone must never dismiss a failed deletion.
    LaunchedEffect(deleting, read, undoItems) {
        if (deleting && ((read.loaded && read.bill == null) || undoItems.any { it.bill.id == billId })) {
            close()
        }
    }
    fun back() {
        if (detail) detail = false else close()
    }
    val bill = read.bill
    BackHandler(enabled = !editing || bill == null) { back() }
    Box(Modifier.fillMaxSize().pointerInput(billId) { detectTapGestures { } }) {
        if (!editing || bill == null) {
            SubPageScaffold(title = if (detail) "账单详情" else "账单操作", onBack = { back() }) { padding ->
                LazyColumn(
                    state = if (detail) detailScroll else summaryScroll,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                        bottom = padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
                ) {
                    when {
                        read.error != null -> item("read-error") {
                            InsetGroup {
                                InlineMessage(read.error.orEmpty(), isError = true, announce = true,
                                    modifier = Modifier.padding(AbSpacing.l))
                                ListRow(title = "重试读取", onClick = { retry++ })
                            }
                        }
                        !read.loaded -> item("loading") { GroupFooter("正在读取账单…") }
                        bill == null -> item("missing") {
                            InsetGroup {
                                BillField("账单已不存在", "这笔账单可能已被删除或在恢复备份时被移除。")
                                ListRow(title = "返回列表", onClick = { close() })
                            }
                        }
                        detail -> {
                            item("record") {
                                InsetGroup {
                                    BillField("金额", formatAmount(bill.priceCents))
                                    BillField("类型", billTypeLabel(bill.type))
                                    BillField("账单时间", billTime(bill.timeMillis))
                                    BillField("商户", bill.merchant)
                                    BillField("分类", bill.category)
                                    BillField("二级分类", bill.subCategory)
                                    BillField("账户", bill.account)
                                    BillField("备注", bill.note)
                                }
                            }
                            item("source") {
                                InsetGroup {
                                    BillField("支付方式", bill.method)
                                    BillField("订单尾号", bill.orderTail)
                                }
                            }
                            item("recognition") {
                                InsetGroup {
                                    ListRow(title = "识别信息",
                                        subtitle = if (recognitionExpanded) "收起来源与本地记录信息" else "查看来源、记账方式与记录编号",
                                        onClick = { recognitionExpanded = !recognitionExpanded },
                                        trailing = { Text(if (recognitionExpanded) "收起" else "展开",
                                            style = AbType.footnote, color = p.accent) })
                                    if (recognitionExpanded) {
                                        InsetDivider()
                                        BillField("来源包名", bill.sourcePkg)
                                        BillField("识别策略 / 记账方式", bill.strategy)
                                        BillField("本地创建时间", billTime(bill.createdAt))
                                        BillField("账单编号", bill.id.toString())
                                        BillField("识别记录编号", bill.recognitionId)
                                        GroupFooter("仅显示本地已保存字段；本地创建时间不代表实际支付或识别时间。")
                                    }
                                }
                            }
                        }
                        else -> {
                            item("summary") {
                                InsetGroup {
                                    Column(Modifier.fillMaxWidth().padding(AbSpacing.l),
                                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        Text(formatAmount(bill.priceCents), style = AbType.amountEntry, color = p.label,
                                            modifier = Modifier.onGloballyPositioned { amountBounds = it.boundsInRoot() })
                                        Text(billTypeLabel(bill.type), style = AbType.headline, color = p.secondaryLabel)
                                    }
                                    BillField("商户", bill.merchant)
                                    BillField("账单时间", billTime(bill.timeMillis))
                                    BillField("分类", bill.category)
                                    BillField("账户", bill.account)
                                    BillField("备注", bill.note)
                                }
                            }
                            item("actions") {
                                InsetGroup {
                                    ListRow(title = "修改", chevron = true,
                                        onClick = if (deleting) null else { { editing = true } })
                                    ListRow(title = "详情", chevron = true, dividerAbove = true,
                                        onClick = if (deleting) null else { { detail = true } })
                                    InsetDivider()
                                    Column(Modifier.fillMaxWidth().padding(AbSpacing.l),
                                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        InlineMessage("删除后5秒内可逐笔撤销")
                                        DestructiveButton(text = "删除账单", busy = deleting, busyLabel = "正在删除…",
                                            modifier = Modifier.fillMaxWidth(), onClick = {
                                                deleting = true
                                                deleteError = null
                                                BillWriter.deleteUndoably(app, billId) { message ->
                                                    deleting = false
                                                    deleteError = message
                                                }
                                            })
                                    }
                                }
                            }
                        }
                    }
                    if (deleteError != null) item("delete-error") {
                        InlineMessage(deleteError.orEmpty(), isError = true, announce = true)
                    }
                }
            }
        }
        if (editing && bill != null) {
            BillEditorDialog(original = bill, newSource = bill.sourcePkg, newTime = bill.timeMillis,
                sourceAmountBounds = amountBounds, onClose = { editing = false; detail = false })
        }
    }
}

/** Place this in the screen's bottom overlay, outside its LazyColumn. Uses Writer's deadlines. */
@Composable
fun BillUndoStack() {
    val app = LocalContext.current.applicationContext
    val items by BillWriter.pendingUndo.collectAsStateWithLifecycle()
    val inFlight = remember { mutableStateMapOf<Long, Boolean>() }
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            key(item.bill.id) {
                Snackbar(action = {
                    TextButton(enabled = inFlight[item.bill.id] != true, onClick = {
                        inFlight[item.bill.id] = true
                        BillWriter.undoDelete(app, item.bill.id) { result ->
                            inFlight.remove(item.bill.id)
                            Toast.makeText(app, result.fold({ "已撤销删除" }, { it.message ?: "撤销失败" }), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("撤销") }
                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text("已删除 ${item.bill.merchant?.takeIf { it.isNotBlank() } ?: formatAmount(item.bill.priceCents)}")
                }
            }
        }
    }
}

private data class BillRead(val loaded: Boolean = false, val bill: BillEntity? = null, val error: String? = null)

@Composable
private fun BillField(label: String, value: String?) {
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AbType.caption1, color = p.secondaryLabel)
        Text(value?.takeIf { it.isNotBlank() } ?: "未提供", style = AbType.body, color = p.label)
    }
}

private fun billTypeLabel(type: String): String = when (type) {
    "EXPENSE" -> "支出"
    "INCOME" -> "收入"
    "REFUND" -> "退款"
    "TRANSFER" -> "转账"
    else -> type.takeIf { it.isNotBlank() } ?: "未提供"
}

private fun billTime(time: Long): String = if (time <= 0) "未提供" else
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
