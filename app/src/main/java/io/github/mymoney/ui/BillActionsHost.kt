package io.github.mymoney.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.BillDeletionCodec
import io.github.mymoney.data.BillDeletionLog
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.data.Reimburse
import io.github.mymoney.ui.design.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Both ledger entry points use the same ID-backed, same-window bill detail layer (Plan A). */
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
    val scrollState = rememberLazyListState()
    val visibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    val close by rememberUpdatedState(onClose)
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()

    DisposableEffect(billId) {
        visibilityChanged(true)
        onDispose { visibilityChanged(false) }
    }
    val bill = read.bill
    fun beginEdit() { editorWasTransfer = bill?.type == "TRANSFER"; editing = true }
    val backPixels = RememberBackPixels()
    val canEdit = deletedSnapshot == null && bill != null && bill.feeParentKey == null
    val showBottomActions = read.loaded && bill != null && deletedSnapshot == null && bill.feeParentKey == null
    val editorBackdrop = rememberGraphicsLayer()
    var editorHostOrigin by remember { mutableStateOf(Offset.Zero) }
    val sampleEditorBackdrop = android.os.Build.VERSION.SDK_INT >= 31 &&
        LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current &&
        editing && !editorWasTransfer && bill != null && deletedSnapshot == null

    BackSurface(enabled = !editing || bill == null, onBack = { close() }, routeKey = listOf(billId, editing), preview = sourcePreview, pixels = backPixels) {
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { editorHostOrigin = it.boundsInRoot().topLeft }
            .pointerInput(billId) { detectTapGestures { } },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (sampleEditorBackdrop) Modifier.drawWithContent {
                        editorBackdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(editorBackdrop)
                    } else Modifier,
                )
                .then(if (editing) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            SubPageScaffold(
                title = "账单详情",
                onBack = { close() },
                reserveDock = false,
                floatingBottomBar = {
                    val actionBill = if (showBottomActions) bill else null
                    if (actionBill != null) {
                        val eligible = Reimburse.eligible(actionBill)
                        val isReimbursable = eligible && actionBill.reimburseStatus != Reimburse.NONE
                        val isReimbursed = actionBill.reimburseStatus == Reimburse.REIMBURSED
                        val reimburseBlue = if (p.cardBg.luminance() > 0.5f) Color(0xFF007AFF) else Color(0xFF0A84FF)
                        val pendingYellow = if (p.cardBg.luminance() > 0.5f) Color(0xFFF5A623) else Color(0xFFFFD60A)
                        val reduceMotion = LocalReduceMotion.current

                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(start = AbSpacing.page, end = AbSpacing.page, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (eligible) {
                                HeaderGlassCapsule(
                                    Modifier
                                        .height(48.dp)
                                        .animateContentSize(AbSpring.morphSize(reduceMotion)),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val receiptBg by animateColorAsState(
                                            targetValue = if (isReimbursable) reimburseBlue else Color.Transparent,
                                            animationSpec = tween(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut),
                                            label = "receipt-bg",
                                        )
                                        val receiptFg by animateColorAsState(
                                            targetValue = if (isReimbursable) Color.White else p.label,
                                            animationSpec = tween(if (reduceMotion) 110 else AbMotion.selection, easing = AbEasing.EaseOut),
                                            label = "receipt-fg",
                                        )
                                        val receiptInteraction = remember { MutableInteractionSource() }
                                        Box(
                                            Modifier
                                                .size(width = 48.dp, height = 48.dp)
                                                .abPressFeedback(receiptInteraction, pressedScale = 0.92f, pressedAlpha = 0.72f)
                                                .clickable(
                                                    interactionSource = receiptInteraction,
                                                    indication = null,
                                                    role = Role.Button,
                                                ) {
                                                    haptic.select()
                                                    val target = if (isReimbursable) Reimburse.NONE else Reimburse.PENDING
                                                    requestReimbursement(app, actionBill, target)
                                                }
                                                .semantics {
                                                    contentDescription = if (isReimbursable) "取消报销" else "标为可报销"
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(receiptBg),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = AbIcons.Receipt,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(AbSize.iconMd),
                                                    tint = receiptFg,
                                                )
                                            }
                                        }
                                        AnimatedVisibility(
                                            visible = isReimbursable,
                                            enter = if (reduceMotion) fadeIn(tween(140, easing = AbEasing.EaseOut)) else expandHorizontally(AbSpring.morphSize(false)) + fadeIn(tween(150, easing = AbEasing.EaseOut)),
                                            exit = if (reduceMotion) fadeOut(tween(110, easing = AbEasing.EaseOut)) else shrinkHorizontally(AbSpring.morphSize(false)) + fadeOut(tween(110, easing = AbEasing.EaseOut)),
                                        ) {
                                            val statusInteraction = remember { MutableInteractionSource() }
                                            Row(
                                                Modifier
                                                    .height(48.dp)
                                                    .abPressFeedback(statusInteraction, pressedScale = 0.96f, pressedAlpha = 0.72f)
                                                    .clickable(
                                                        interactionSource = statusInteraction,
                                                        indication = null,
                                                        role = Role.Button,
                                                    ) {
                                                        haptic.select()
                                                        if (isReimbursed) {
                                                            requestReimbursement(app, actionBill, Reimburse.PENDING)
                                                        } else {
                                                            markReimbursed(app, actionBill, assetAccounts) { returnPicker = true }
                                                        }
                                                    }
                                                    .padding(start = 6.dp, end = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isReimbursed) p.income else pendingYellow),
                                                )
                                                Text(
                                                    text = if (isReimbursed) "已报销" else "待报销",
                                                    style = AbType.subhead.copy(fontWeight = FontWeight.Medium),
                                                    color = if (isReimbursed) p.income else p.label,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            HeaderGlassCapsule(Modifier.height(48.dp)) {
                                Row(
                                    Modifier.padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (canEdit) {
                                        val editInteraction = remember { MutableInteractionSource() }
                                        Box(
                                            Modifier
                                                .size(width = 48.dp, height = 48.dp)
                                                .abPressFeedback(editInteraction, enabled = !deleting, pressedScale = 0.92f, pressedAlpha = 0.65f)
                                                .clip(CircleShape)
                                                .clickable(
                                                    interactionSource = editInteraction,
                                                    indication = null,
                                                    enabled = !deleting,
                                                    role = Role.Button,
                                                ) {
                                                    haptic.select()
                                                    beginEdit()
                                                }
                                                .semantics { contentDescription = "修改" },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = AbIcons.Pencil,
                                                contentDescription = null,
                                                modifier = Modifier.size(AbSize.iconMd),
                                                tint = if (deleting) p.label.copy(alpha = 0.4f) else p.label,
                                            )
                                        }
                                        Box(
                                            Modifier
                                                .width(0.5.dp)
                                                .height(18.dp)
                                                .background(p.separator.copy(alpha = 0.45f)),
                                        )
                                    }
                                    val trashInteraction = remember { MutableInteractionSource() }
                                    Box(
                                        Modifier
                                            .size(width = 48.dp, height = 48.dp)
                                            .abPressFeedback(trashInteraction, enabled = !deleting, pressedScale = 0.92f, pressedAlpha = 0.65f)
                                            .clip(CircleShape)
                                            .clickable(
                                                interactionSource = trashInteraction,
                                                indication = null,
                                                enabled = !deleting,
                                                role = Role.Button,
                                            ) {
                                                haptic.warn()
                                                deleting = true
                                            }
                                            .semantics { contentDescription = "删除账单" },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = AbIcons.Trash,
                                            contentDescription = null,
                                            modifier = Modifier.size(AbSize.iconMd),
                                            tint = if (deleting) p.red.copy(alpha = 0.4f) else p.red,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                SectionLazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AbSpacing.page,
                        end = AbSpacing.page,
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + if (showBottomActions) 72.dp else 0.dp,
                    ),
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
                                BillDetailRow("账单已不存在", "可能已被删除或移除")
                                ListRow(title = "返回列表", onClick = { close() })
                            }
                        }
                        else -> {
                            val linkedAssetAccount = assetAccounts.firstOrNull { it.id == bill.accountId }
                                ?: bill.account?.let { name -> assetAccounts.firstOrNull { it.name == name } }
                            val accountName = if (deletedSnapshot != null) bill.account
                                else linkedAssetAccount?.name ?: bill.account
                            val canLinkUnlinkedBill = !bill.assetLinked && (bill.type == "TRANSFER" || linkedAssetAccount?.initialized == true)
                            val isPureManual = (bill.strategy == "手动记账" ||
                                (bill.sourcePkg.startsWith("io.github.mymoney.manual.") && !bill.sourcePkg.startsWith("io.github.mymoney.manual.ocr."))) &&
                                bill.merchant.isNullOrBlank() && bill.method.isNullOrBlank() &&
                                bill.orderTail.isNullOrBlank() && bill.recognitionId.isNullOrBlank()

                            item("summary") {
                                InsetGroup {
                                    val accent = billIconTint(p, bill.type, bill.category, bill.subCategory)
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = AbSpacing.l, vertical = AbSpacing.m),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                                    ) {
                                        CategoryBadge(
                                            category = bill.category,
                                            tint = accent,
                                            size = AbCardDensity.icon,
                                        )
                                        Column(
                                            Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(AbSpacing.xxs),
                                        ) {
                                            Text(
                                                text = billCategoryDisplay(bill.category, bill.subCategory, bill.type) ?: "未分类",
                                                style = AbType.body.copy(fontWeight = FontWeight.SemiBold),
                                                color = p.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = billTypeLabel(bill.type),
                                                style = AbType.caption1,
                                                color = p.secondaryLabel,
                                                maxLines = 1,
                                            )
                                        }
                                        Box(
                                            Modifier
                                                .widthIn(min = 84.dp, max = 188.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            AmountText(
                                                value = formatAmount(bill.priceCents, signed = true, type = bill.type),
                                                style = AbType.amountTitle.copy(textAlign = TextAlign.End),
                                                color = billRowAmountColor(p, bill.type, bill.reimburseStatus),
                                                minFontSize = 16f,
                                            )
                                        }
                                    }
                                    InsetDivider()
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = AbSpacing.xs),
                                    ) {
                                        if (deletedSnapshot != null) {
                                            BillDetailRow("状态", "已删除 · ${billTime(deletedSnapshot.deletedAt)}")
                                            val fees = BillDeletionCodec.bills(deletedSnapshot).drop(1)
                                            if (fees.isNotEmpty()) {
                                                BillDetailRow("一同删除的手续费", fees.joinToString { formatAmount(it.priceCents) })
                                            }
                                        }
                                        if (!accountName.isNullOrBlank()) {
                                            BillDetailRow("账户", accountName)
                                        }
                                        if (bill.type == "TRANSFER") {
                                            val transferTarget = if (deletedSnapshot != null) bill.transferAccount ?: "未补全"
                                            else assetAccounts.firstOrNull { it.id == bill.transferAccountId }?.name ?: bill.transferAccount ?: "未补全"
                                            BillDetailRow("转入账户", transferTarget)
                                        }
                                        BillDetailRow("时间", billTime(bill.timeMillis))
                                        bill.note?.takeIf { it.isNotBlank() }?.let { BillDetailRow("备注", it) }

                                        when (bill.reimburseStatus) {
                                            Reimburse.PENDING -> {
                                                BillDetailRow(
                                                    label = "报销状态",
                                                    value = "待报销",
                                                    valueColor = if (p.cardBg.luminance() > 0.5f) Color(0xFFF5A623) else Color(0xFFFFD60A),
                                                )
                                            }
                                            Reimburse.REIMBURSED -> {
                                                val returnAccountName = assetAccounts.firstOrNull { account -> account.id == bill.reimburseAccountId }?.name
                                                    ?: if (bill.reimburseAccountId == null) assetAccounts.firstOrNull { account -> account.id == bill.accountId }?.name ?: bill.account else null
                                                val canPickReturn = deletedSnapshot == null && paymentReady(bill, assetAccounts) && returnCandidates(assetAccounts).isNotEmpty()
                                                BillDetailRow(
                                                    label = "报销状态",
                                                    value = if (!returnAccountName.isNullOrBlank()) "已报销（退回$returnAccountName）" else "已报销",
                                                    valueColor = p.income,
                                                    onClick = if (canPickReturn) ({ returnPicker = true }) else null,
                                                )
                                                bill.reimbursedAt?.let { at ->
                                                    BillDetailRow("报销时间", billTime(at))
                                                }
                                            }
                                        }

                                        if (deletedSnapshot != null && bill.assetLinked) {
                                            BillDetailRow(
                                                label = "删除前余额联动",
                                                value = "已计入账户余额",
                                            )
                                        } else if (deletedSnapshot == null && canLinkUnlinkedBill) {
                                            BillDetailRow(
                                                label = "余额联动",
                                                value = "未计入账户余额",
                                            )
                                        }
                                    }
                                    if (deletedSnapshot != null) {
                                        GroupFooter("删除当时的账单快照，仅供查看；不代表当前账户余额。")
                                    }
                                }
                            }

                            if (deletedSnapshot == null && (bill.feeParentKey != null || canLinkUnlinkedBill)) {
                                item("link-or-parent") {
                                    InsetGroup {
                                        if (bill.feeParentKey != null) {
                                            val parent = allBills.firstOrNull { it.assetKey == bill.feeParentKey }
                                            ListRow(
                                                title = "手续费随原转账一同修改 / 删除",
                                                subtitle = "保持本金、手续费与双账户余额一致",
                                                chevron = parent != null,
                                                onClick = parent?.let { { parentId = it.id } },
                                            )
                                        } else if (canLinkUnlinkedBill) {
                                            ListRow(
                                                title = if (bill.type == "TRANSFER") "补全转账账户并计入余额" else "计入账户余额",
                                                subtitle = "仅在当前余额尚未包含这笔账单时使用",
                                                chevron = true,
                                                onClick = if (deleting || linking) null else {
                                                    { if (bill.type == "TRANSFER") beginEdit() else linkConfirm = true }
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            if (!isPureManual) {
                                item("recognition") {
                                    InsetGroup {
                                        ListRow(
                                            title = "来源与识别信息",
                                            onClick = { recognitionExpanded = !recognitionExpanded },
                                            trailing = {
                                                Text(
                                                    if (recognitionExpanded) "收起" else "展开",
                                                    style = AbType.footnote,
                                                    color = p.accent,
                                                )
                                            },
                                        )
                                        val reduceRecognitionMotion = LocalReduceMotion.current
                                        AnimatedVisibility(
                                            visible = recognitionExpanded,
                                            enter = if (reduceRecognitionMotion) fadeIn(tween(140, easing = AbEasing.EaseOut)) else expandVertically(AbSpring.morphSize(false)) + fadeIn(tween(160, easing = AbEasing.EaseOut)),
                                            exit = if (reduceRecognitionMotion) fadeOut(tween(110, easing = AbEasing.EaseOut)) else shrinkVertically(AbSpring.morphSize(false)) + fadeOut(tween(120, easing = AbEasing.EaseOut)),
                                        ) {
                                            Column(Modifier.fillMaxWidth()) {
                                                InsetDivider()
                                                Column(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = AbSpacing.xs),
                                                ) {
                                                    bill.merchant?.takeIf { it.isNotBlank() }?.let { BillDetailRow("商户原文", it) }
                                                    bill.method?.takeIf { it.isNotBlank() }?.let { BillDetailRow("支付方式", it) }
                                                    bill.orderTail?.takeIf { it.isNotBlank() }?.let { BillDetailRow("订单尾号", it) }
                                                    bill.sourcePkg.takeIf { it.isNotBlank() }?.let { BillDetailRow("来源包名", it) }
                                                    bill.strategy.takeIf { it.isNotBlank() }?.let { BillDetailRow("识别策略", it) }
                                                    BillDetailRow("创建时间", billTime(bill.createdAt))
                                                    BillDetailRow("账单编号", "#${bill.id}")
                                                    if (!bill.recognitionId.isNullOrBlank()) {
                                                        BillDetailRow("识别记录编号", bill.recognitionId)
                                                    }
                                                }
                                                GroupFooter("仅显示本地已保存字段；本地创建时间不代表实际支付或识别时间。")
                                            }
                                        }
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
        SlideSubPageValue(value = parentId, modifier = Modifier.fillMaxSize().zIndex(6f)) { id ->
            BillActionsHost(id, onClose = { parentId = null })
        }
        var retainedTransferBill by remember(billId) { mutableStateOf<BillEntity?>(null) }
        if (editing && editorWasTransfer && bill != null && deletedSnapshot == null) retainedTransferBill = bill
        SlideSubPage(
            visible = editing && editorWasTransfer && bill != null && deletedSnapshot == null,
            modifier = Modifier.fillMaxSize().zIndex(5f),
        ) {
            retainedTransferBill?.let { originalBill ->
                AssetTransferScreen(original = originalBill, onBack = { editing = false })
            }
        }
        if (editing && !editorWasTransfer && bill != null && deletedSnapshot == null) {
            BillEditorDialog(
                original = bill,
                newSource = bill.sourcePkg,
                newTime = bill.timeMillis,
                backdrop = if (sampleEditorBackdrop) editorBackdrop else null,
                backdropOrigin = editorHostOrigin,
                onClose = { editing = false },
            )
        }
    }
    }
}

private data class BillRead(val loaded: Boolean = false, val bill: BillEntity? = null, val error: String? = null)

@Composable
private fun BillDetailRow(
    label: String,
    value: String?,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val interaction = remember(onClick != null) { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .heightIn(min = AbSize.minTouch)
                        .abPressFeedback(interaction, pressedScale = 0.99f, tintOverlay = true)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else Modifier,
            )
            .padding(horizontal = AbSpacing.l, vertical = if (onClick != null) 12.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = AbType.subhead,
            color = p.secondaryLabel,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "未提供",
            style = AbType.subhead.tabular(),
            color = valueColor ?: p.label,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
        .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
