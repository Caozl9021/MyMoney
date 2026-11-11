package io.github.mymoney.ui

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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.data.BillDeletionLog
import io.github.mymoney.data.BillDeletionCodec
import kotlinx.coroutines.flow.flowOf
import io.github.mymoney.ui.design.*
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
    deletedSnapshot: BillDeletionLog? = null,
) {
    val app = LocalContext.current.applicationContext
    val dao = remember(app) { AppDb.get(app).bills() }
    var retry by remember(billId) { mutableStateOf(0) }
    val read by remember(dao, billId, retry, deletedSnapshot) {
        if (deletedSnapshot != null) flowOf(runCatching {
            BillRead(loaded = true, bill = BillDeletionCodec.bills(deletedSnapshot).firstOrNull())
        }.getOrElse { BillRead(error = it.message ?: "删除快照读取失败") })
        else dao.observeById(billId).map { BillRead(loaded = true, bill = it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(BillRead(error = error.message ?: "读取账单失败，请重试"))
            }
    }.collectAsStateWithLifecycle(initialValue = BillRead())
    var detail by rememberSaveable(billId) { mutableStateOf(deletedSnapshot != null) }
    var recognitionExpanded by rememberSaveable(billId) { mutableStateOf(false) }
    var editing by rememberSaveable(billId) { mutableStateOf(false) }
    var editorWasTransfer by rememberSaveable(billId) { mutableStateOf(false) }
    var deleting by remember(billId) { mutableStateOf(false) }
    var deleteError by remember(billId) { mutableStateOf<String?>(null) }
    var linkConfirm by remember(billId) { mutableStateOf(false) }
    var returnPicker by remember(billId) { mutableStateOf(false) }
    var linking by remember(billId) { mutableStateOf(false) }
    var parentId by remember(billId) { mutableStateOf<Long?>(null) }
    val assetAccounts by io.github.mymoney.data.AssetStore.accounts.collectAsStateWithLifecycle()
    val allBills by remember(dao) { dao.observeRange(Long.MIN_VALUE, Long.MAX_VALUE) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var amountBounds by remember(billId) { mutableStateOf<Rect?>(null) }
    var amountFont by remember(billId) { mutableStateOf<androidx.compose.ui.unit.TextUnit?>(null) }
    val summaryScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    val visibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    val close by rememberUpdatedState(onClose)
    val p = LocalAbPalette.current

    DisposableEffect(billId) {
        visibilityChanged(true)
        onDispose { visibilityChanged(false) }
    }
    fun back() {
        if (detail && deletedSnapshot == null) detail = false else close()
    }
    val bill = read.bill
    fun beginEdit() { editorWasTransfer = bill?.type == "TRANSFER"; editing = true }
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
        Box(Modifier.fillMaxSize().then(if (editing) Modifier.clearAndSetSemantics {} else Modifier)) {
            SubPageScaffold(title = if (detail) "账单详情" else "账单操作", onBack = { back() }, reserveDock = false) { padding ->
                io.github.mymoney.ui.design.SectionLazyColumn(
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
                                    Column(Modifier.padding(AbSpacing.l)) {
                                        BillAmountDisplay(bill.priceCents, bill.type, hidden = editing,
                                            onBounds = { amountBounds = it }, onFontSize = { amountFont = it })
                                    }
                                    if (deletedSnapshot != null) {
                                        BillField("状态", "已删除 · ${billTime(deletedSnapshot.deletedAt)}")
                                        GroupFooter("删除当时的账单快照，仅供查看；不代表当前账户余额。")
                                        val fees = BillDeletionCodec.bills(deletedSnapshot).drop(1)
                                        if (fees.isNotEmpty()) BillField("一同删除的手续费", fees.joinToString { formatAmount(it.priceCents) })
                                    }
                                    BillField("类型", billTypeLabel(bill.type))
                                    BillField("账单时间", billTime(bill.timeMillis))
                                    bill.note?.takeIf { it.isNotBlank() }?.let { BillField("备注", it) }
                                    BillField("分类", bill.category)
                                    BillField("二级分类", bill.subCategory)
                                    BillField("账户", if (deletedSnapshot != null) bill.account else assetAccounts.firstOrNull { it.id == bill.accountId }?.name ?: bill.account)
                                    if (bill.type == "TRANSFER") BillField("转入账户", if (deletedSnapshot != null) bill.transferAccount ?: "未补全" else assetAccounts.firstOrNull { it.id == bill.transferAccountId }?.name ?: bill.transferAccount ?: "未补全")
                                    BillField(if (deletedSnapshot != null) "删除前余额联动" else "余额联动", if (bill.assetLinked) "已计入账户余额" else "未计入余额；历史账单不会自动重复扣减")
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
                                        BillAmountDisplay(bill.priceCents, bill.type, hidden = editing, onBounds = { amountBounds = it }, onFontSize = { amountFont = it })
                                        Text(billTypeLabel(bill.type), style = AbType.headline, color = p.secondaryLabel)
                                    }
                                    bill.note?.takeIf { it.isNotBlank() }?.let { BillField("备注", it) }
                                    BillField("账单时间", billTime(bill.timeMillis))
                                    if (io.github.mymoney.data.Reimburse.eligible(bill) || bill.reimburseStatus != io.github.mymoney.data.Reimburse.NONE) {
                                        BillField("报销", when (bill.reimburseStatus) {
                                            io.github.mymoney.data.Reimburse.PENDING -> "待报销"
                                            io.github.mymoney.data.Reimburse.REIMBURSED -> "已报销"
                                            else -> "未标记"
                                        })
                                        bill.reimbursedAt?.takeIf { bill.reimburseStatus == io.github.mymoney.data.Reimburse.REIMBURSED }?.let { at ->
                                            val accountName = assetAccounts.firstOrNull { account -> account.id == bill.reimburseAccountId }?.name
                                                ?: if (bill.reimburseAccountId == null) assetAccounts.firstOrNull { account -> account.id == bill.accountId }?.name ?: bill.account else null
                                            BillField("退回", "${formatAmount(bill.priceCents)} 已于 ${billTime(at)} 退回 ${accountName ?: "退回账户"}")
                                        }
                                    }
                                    BillField("分类", bill.category)
                                    BillField("账户", if (deletedSnapshot != null) bill.account else assetAccounts.firstOrNull { it.id == bill.accountId }?.name ?: bill.account)
                                    if (bill.type == "TRANSFER") BillField("转入账户", if (deletedSnapshot != null) bill.transferAccount ?: "未补全" else assetAccounts.firstOrNull { it.id == bill.transferAccountId }?.name ?: bill.transferAccount ?: "未补全")
                                    BillField(if (deletedSnapshot != null) "删除前余额联动" else "余额联动", if (bill.assetLinked) "已计入账户余额" else "未计入余额；历史账单不会自动重复扣减")
                                }
                            }
                            item("actions") {
                                InsetGroup {
                                    if (io.github.mymoney.data.Reimburse.eligible(bill)) {
                                        when (bill.reimburseStatus) {
                                            io.github.mymoney.data.Reimburse.PENDING -> {
                                                ListRow(title = "已报销", onClick = { markReimbursed(app, bill, assetAccounts) { returnPicker = true } })
                                                ListRow(title = "取消可报销", dividerAbove = true, onClick = { requestReimbursement(app, bill, io.github.mymoney.data.Reimburse.NONE) })
                                            }
                                            io.github.mymoney.data.Reimburse.REIMBURSED -> {
                                                if (paymentReady(bill, assetAccounts)) ListRow(title = "更改退回账户", onClick = { returnPicker = true })
                                                ListRow(title = "撤销报销", dividerAbove = paymentReady(bill, assetAccounts), onClick = { requestReimbursement(app, bill, io.github.mymoney.data.Reimburse.PENDING) })
                                            }
                                            else -> ListRow(title = "标为可报销", onClick = { requestReimbursement(app, bill, io.github.mymoney.data.Reimburse.PENDING) })
                                        }
                                    }
                                    if (bill.feeParentKey != null) {
                                        val parent = allBills.firstOrNull { it.assetKey == bill.feeParentKey }
                                        ListRow(title = "手续费随原转账一同修改 / 删除", subtitle = "保持本金、手续费与双账户余额一致",
                                            chevron = parent != null, onClick = parent?.let { { parentId = it.id } })
                                    } else ListRow(title = "修改", chevron = true,
                                        onClick = if (deleting) null else { { beginEdit() } })
                                    if (!bill.assetLinked && bill.feeParentKey == null) ListRow(
                                        title = if (bill.type == "TRANSFER") "补全转账账户并计入余额" else "计入账户余额",
                                        subtitle = "仅在当前余额尚未包含这笔账单时使用", chevron = true,
                                        onClick = if (deleting || linking) null else { { if (bill.type == "TRANSFER") beginEdit() else linkConfirm = true } })
                                    ListRow(title = "详情", chevron = true, dividerAbove = true,
                                        onClick = if (deleting) null else { { openDetail() } })
                                    InsetDivider()
                                    Column(Modifier.fillMaxWidth().padding(AbSpacing.l),
                                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        if (bill.feeParentKey == null) DestructiveButton(text = "删除账单", enabled = !deleting,
                                            modifier = Modifier.fillMaxWidth(), onClick = {
                                                deleting = true
                                            })
                                    }
                                }
                            }
                        }
                    }
                    if (detail && bill != null && deletedSnapshot == null && bill.feeParentKey == null) item("detail-actions") {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                            InsetGroup { ListRow(title = "修改", chevron = true, onClick = { beginEdit() }) }
                            DestructiveButton("删除账单", onClick = { deleting = true }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (deleteError != null) item("delete-error") {
                        InlineMessage(deleteError.orEmpty(), isError = true, announce = true)
                    }
                }
            }
        }
        if (returnPicker && bill != null) ReturnAccountDialog(bill, assetAccounts) { returnPicker = false }
        if (deleting && deletedSnapshot == null) DeleteBillDialog(billId, origin = "bill_detail", onDismiss = { deleting = false }, onDeleted = { deleting = false; close() })
        if (linkConfirm && bill != null && deletedSnapshot == null) {
            val linkedAccount = assetAccounts.firstOrNull { it.id == bill.accountId }
                ?: assetAccounts.firstOrNull { it.name == bill.account }
            AppAlertDialog(
                title = "将这笔账单计入余额？",
                message = "${linkedAccount?.name ?: bill.account ?: "未指定账户"}\n本次余额变动 ${assetMoney(io.github.mymoney.data.AssetMath.delta(bill.type, bill.priceCents))}\n若当前余额已包含这笔账单，请取消，以免重复扣算。",
                onDismiss = { if (!linking) linkConfirm = false },
                confirmLabel = "确认计入",
                onConfirm = {
                    linking = true
                    BillWriter.linkAsset(app, bill.id) { result ->
                        linking = false
                        result.onSuccess { linkConfirm = false }.onFailure { deleteError = it.message ?: "计入余额失败"; linkConfirm = false }
                    }
                },
                busy = linking,
                busyLabel = "处理中…",
            )
        }
        parentId?.let { id -> BillActionsHost(id, onClose = { parentId = null }) }
        if (editing && bill != null && deletedSnapshot == null) {
            if (editorWasTransfer) AssetTransferScreen(original = bill, onBack = { editing = false })
            else BillEditorDialog(original = bill, newSource = bill.sourcePkg, newTime = bill.timeMillis,
                sourceAmountBounds = amountBounds?.takeIf { it.top >= 0 && it.height > 0 }, sourceAmountFontSize = amountFont, onClose = { editing = false })
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
