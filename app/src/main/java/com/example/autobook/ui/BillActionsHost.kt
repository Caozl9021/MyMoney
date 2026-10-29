package com.example.autobook.ui

import kotlinx.coroutines.launch

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
    sourcePreview: androidx.compose.ui.graphics.ImageBitmap? = null,
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
    var sharing by remember(billId) { mutableStateOf(false) }
    var shareMessage by remember(billId) { mutableStateOf<String?>(null) }
    var deleteError by remember(billId) { mutableStateOf<String?>(null) }
    var linkConfirm by remember(billId) { mutableStateOf(false) }
    var linking by remember(billId) { mutableStateOf(false) }
    var parentId by remember(billId) { mutableStateOf<Long?>(null) }
    val assetAccounts by com.example.autobook.data.AssetStore.accounts.collectAsStateWithLifecycle()
    val allBills by remember(dao) { dao.observeRange(Long.MIN_VALUE, Long.MAX_VALUE) }.collectAsStateWithLifecycle(initialValue = emptyList())
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
    val backPixels = RememberBackPixels()
    val navigationScope = rememberCoroutineScope()
    var detailPreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var openingDetail by remember { mutableStateOf(false) }
    fun openDetail() {
        if (openingDetail || detail) return
        openingDetail = true
        navigationScope.launch {
            try { detailPreview = backPixels.snapshot(); detail = true } finally { openingDetail = false }
        }
    }
    BackSurface(enabled = !editing || bill == null, onBack = ::back, routeKey = listOf(billId, detail, editing), preview = if (detail) detailPreview else sourcePreview, pixels = backPixels) {
    Box(Modifier.fillMaxSize().pointerInput(billId) { detectTapGestures { } }) {
        if (!editing || bill == null) {
            SubPageScaffold(title = if (detail) "账单详情" else "账单操作", onBack = { back() }, reserveDock = false) { padding ->
                com.example.autobook.ui.design.SectionLazyColumn(
                    state = if (detail) detailScroll else summaryScroll,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                        top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
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
                                    bill.note?.takeIf { it.isNotBlank() }?.let { BillField("备注", it) }
                                    BillField("分类", bill.category)
                                    BillField("二级分类", bill.subCategory)
                                    BillField("账户", assetAccounts.firstOrNull { it.id == bill.accountId }?.name ?: bill.account)
                                    if (bill.type == "TRANSFER") BillField("转入账户", assetAccounts.firstOrNull { it.id == bill.transferAccountId }?.name ?: bill.transferAccount ?: "未补全")
                                    BillField("余额联动", if (bill.assetLinked) "已计入账户余额" else "未计入余额；历史账单不会自动重复扣减")
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
                                        BillField("商户", bill.merchant)
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
                                        Text(formatAmount(bill.priceCents), style = AbType.amountEntry, color = amountTint(p, bill.type),
                                            modifier = Modifier.onGloballyPositioned { amountBounds = it.boundsInRoot() })
                                        Text(billTypeLabel(bill.type), style = AbType.headline, color = p.secondaryLabel)
                                    }
                                    bill.note?.takeIf { it.isNotBlank() }?.let { BillField("备注", it) }
                                    BillField("账单时间", billTime(bill.timeMillis))
                                    BillField("分类", bill.category)
                                    BillField("账户", assetAccounts.firstOrNull { it.id == bill.accountId }?.name ?: bill.account)
                                    if (bill.type == "TRANSFER") BillField("转入账户", assetAccounts.firstOrNull { it.id == bill.transferAccountId }?.name ?: bill.transferAccount ?: "未补全")
                                    BillField("余额联动", if (bill.assetLinked) "已计入账户余额" else "未计入余额；历史账单不会自动重复扣减")
                                }
                            }
                            item("actions") {
                                InsetGroup {
                                    if (bill.feeParentKey != null) {
                                        val parent = allBills.firstOrNull { it.assetKey == bill.feeParentKey }
                                        ListRow(title = "手续费随原转账一同修改 / 删除", subtitle = "保持本金、手续费与双账户余额一致",
                                            chevron = parent != null, onClick = parent?.let { { parentId = it.id } })
                                    } else ListRow(title = "修改", chevron = true,
                                        onClick = if (deleting) null else { { editing = true } })
                                    if (!bill.assetLinked && bill.feeParentKey == null) ListRow(
                                        title = if (bill.type == "TRANSFER") "补全转账账户并计入余额" else "计入账户余额",
                                        subtitle = "仅在当前余额尚未包含这笔账单时使用", chevron = true,
                                        onClick = if (deleting || linking) null else { { if (bill.type == "TRANSFER") editing = true else linkConfirm = true } })
                                    ListRow(title = "详情", chevron = true, dividerAbove = true,
                                        onClick = if (deleting) null else { { openDetail() } })
                                    InsetDivider()
                                    Column(Modifier.fillMaxWidth().padding(AbSpacing.l),
                                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        InlineMessage("删除后5秒内可逐笔撤销")
                                        if (bill.feeParentKey == null) DestructiveButton(text = "删除账单", busy = deleting, busyLabel = "正在删除…",
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
                    if (bill != null && bill.type in listOf("EXPENSE", "INCOME")) item("share") {
                        InsetGroup {
                            ListRow(title = if (sharing) "正在启动分享…" else "分享至 iCost", subtitle = shareMessage,
                                onClick = if (sharing) null else { {
                                    sharing = true; shareMessage = null
                                    navigationScope.launch {
                                        try {
                                            val current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dao.byId(billId) }
                                                ?: error("账单已不存在，无法分享")
                                            val launched = com.example.autobook.export.CostShare.shareBills(app, listOf(BillWriter.toDraft(current))) != null
                                            shareMessage = if (launched) "已发起分享，请在目标 App 确认导入" else "分享未启动，可重试"
                                            current.recognitionId?.let { com.example.autobook.data.RecognitionStore.markShare(it, launched) }
                                        } catch (failure: Exception) {
                                            shareMessage = failure.message ?: "分享失败，可重试"
                                        } finally { sharing = false }
                                    }
                                } })
                        }
                    }
                    if (deleteError != null) item("delete-error") {
                        InlineMessage(deleteError.orEmpty(), isError = true, announce = true)
                    }
                }
            }
        }
        if (linkConfirm && bill != null) {
            val linkedAccount = assetAccounts.firstOrNull { it.id == bill.accountId }
                ?: assetAccounts.firstOrNull { it.name == bill.account }
            androidx.compose.material3.AlertDialog(onDismissRequest = { if (!linking) linkConfirm = false },
                title = { Text("将这笔账单计入余额？") },
                text = { Text("${linkedAccount?.name ?: bill.account ?: "未指定账户"}\n本次余额变动 ${assetMoney(com.example.autobook.data.AssetMath.delta(bill.type, bill.priceCents))}\n若当前余额已包含这笔账单，请取消，以免重复扣算。") },
                confirmButton = { TextButton(enabled = !linking, onClick = {
                    linking = true
                    BillWriter.linkAsset(app, bill.id) { result ->
                        linking = false
                        result.onSuccess { linkConfirm = false }.onFailure { deleteError = it.message ?: "计入余额失败"; linkConfirm = false }
                    }
                }) { Text(if (linking) "处理中…" else "确认计入") } },
                dismissButton = { TextButton(enabled = !linking, onClick = { linkConfirm = false }) { Text("取消") } })
        }
        parentId?.let { id -> BillActionsHost(id, onClose = { parentId = null }) }
        if (editing && bill != null) {
            if (bill.type == "TRANSFER") AssetTransferScreen(original = bill, onBack = { editing = false })
            else BillEditorDialog(original = bill, newSource = bill.sourcePkg, newTime = bill.timeMillis,
                sourceAmountBounds = amountBounds, onClose = { editing = false })
        }
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
                    Text("已删除 ${item.bill.note?.takeIf { it.isNotBlank() } ?: item.bill.merchant?.takeIf { it.isNotBlank() } ?: formatAmount(item.bill.priceCents)}")
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
