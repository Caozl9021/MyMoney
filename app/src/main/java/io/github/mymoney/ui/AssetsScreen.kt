package io.github.mymoney.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.*
import io.github.mymoney.ui.design.*
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val assetKinds = linkedMapOf("CASH" to "现金", "PAYMENT" to "支付账户", "SAVINGS" to "储蓄卡", "CREDIT" to "信用账户", "OTHER_ASSET" to "其他资产", "OTHER_DEBT" to "其他负债")
/** 资产 Tab 显示账户数达到该值才按类型分组（用户 2026-11-12 确认：少于 5 个不分组）。 */
internal const val ASSET_GROUP_THRESHOLD = 5
private val assetGroupOrder = listOf("PAYMENT", "SAVINGS", "CREDIT", "CASH", "OTHER_ASSET", "OTHER_DEBT")
internal fun assetMoney(cents: Long): String = (if (cents < 0) "−" else "") + "¥" + BigDecimal.valueOf(cents, 2).abs().toPlainString()
private fun assetNumber(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
private fun positionLabel(account: AssetAccount, cents: Long): String = when {
    !account.initialized -> "未设置余额"
    AssetMath.isDebt(account.kind) && cents <= 0 -> "欠款 ${assetMoney(cents).removePrefix("−")}"
    AssetMath.isDebt(account.kind) -> "溢缴 ${assetMoney(cents)}"
    cents < 0 -> "透支 ${assetMoney(cents).removePrefix("−")}"
    else -> assetMoney(cents)
}
private fun assetTime(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Immutable
private data class AssetRoute(
    val selected: String? = null,
    val form: String? = null,
    val wizardStep: String = "kind",
)

private val AssetRouteListSaver = listSaver<List<AssetRoute>, String>(
    save = { list -> list.flatMap { listOf(it.selected.orEmpty(), it.form.orEmpty(), it.wizardStep) } },
    restore = { flat ->
        flat.chunked(3).map { chunk ->
            AssetRoute(
                selected = chunk.getOrNull(0)?.takeIf { it.isNotEmpty() },
                form = chunk.getOrNull(1)?.takeIf { it.isNotEmpty() },
                wizardStep = chunk.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "kind",
            )
        }.ifEmpty { listOf(AssetRoute()) }
    },
)

/** A local asset ledger, using the same account identities as the receipt picker. */
@Composable
fun AssetsScreen(onBack: () -> Unit, isTab: Boolean = false, onSubpageVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val accounts by AssetStore.accounts.collectAsStateWithLifecycle()
    val entries by AssetStore.entries.collectAsStateWithLifecycle()
    val ready by AssetStore.ready.collectAsStateWithLifecycle()
    val storeError by AssetStore.error.collectAsStateWithLifecycle()
    val allBills by remember { AppDb.get(context).bills().observeRange(Long.MIN_VALUE, Long.MAX_VALUE) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var routePath by rememberSaveable(stateSaver = AssetRouteListSaver) { mutableStateOf(listOf(AssetRoute())) }
    val currentRoute = routePath.last()
    val selected = currentRoute.selected
    val form = currentRoute.form
    val wizardDraft = remember { WizardDraftState() }
    var billId by rememberSaveable { mutableStateOf<Long?>(null) }
    var billPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var openingBill by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("asset_display", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("hide_amount", false)) }
    var showArchived by remember { mutableStateOf(prefs.getBoolean("show_archived", false)) }
    var moreOpen by remember { mutableStateOf(false) }
    var balanceOpen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingReverse by remember { mutableStateOf<AssetEntry?>(null) }
    val scope = rememberCoroutineScope()
    val account = accounts.firstOrNull { it.id == selected }
    val summary = remember(accounts, entries) { AssetMath.summary(accounts, entries) }
    val clock = rememberLedgerClock()
    val startDay = rememberMonthStartDay()
    val currentPeriod = LedgerMonthPeriod.containing(clock.date, startDay, clock.zone)
    var expandedPeriods by rememberSaveable(account?.id, startDay, currentPeriod.month.toString(), clock.zone.id) {
        mutableStateOf(listOf(currentPeriod.month.toString()))
    }
    var periods by remember(account?.id, startDay, currentPeriod.month, clock.zone) { mutableStateOf<List<AccountHistoryPeriod>?>(null) }
    LaunchedEffect(account, allBills, entries, clock.date, clock.zone, startDay) {
        periods = account?.let { selectedAccount -> withContext(Dispatchers.Default) {
            accountPeriodHistory(selectedAccount, allBills, entries, clock.date, startDay, clock.zone)
        } }
    }

    var predictiveSnapEpoch by remember { mutableIntStateOf(0) }
    val assetTransition = key(predictiveSnapEpoch) { updateTransition(routePath, label = "assetRoute") }
    val backPixels = RememberBackPixels()
    val previews = remember { mutableMapOf<AssetRoute, ImageBitmap>() }
    var navigating by remember { mutableStateOf(false) }
    var navRevision by remember { mutableIntStateOf(0) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val predictiveEnabled = LocalPredictiveBackEnabled.current
    LaunchedEffect(predictiveEnabled) { previews.clear() }

    fun navigateAsset(target: AssetRoute) {
        if (navigating || target == currentRoute) return
        if (!predictiveEnabled) {
            navRevision++
            previews.keys.retainAll(routePath.toSet())
            routePath = routePath + target
            return
        }
        val ticket = ++navRevision
        navigating = true
        val origin = routePath
        navigationJob = scope.launch {
            try {
                snapshotFlow { !assetTransition.isRunning && assetTransition.currentState == assetTransition.targetState }.first { it }
                withFrameNanos { }
                if (routePath != origin || navRevision != ticket) return@launch
                backPixels.snapshot()?.let { previews[origin.last()] = it }
                if (routePath != origin || navRevision != ticket) return@launch
                previews.keys.retainAll(routePath.toSet())
                routePath = routePath + target
            } finally {
                if (navRevision == ticket) navigating = false
            }
        }
    }

    fun startNewAccountWizard() {
        wizardDraft.reset()
        navigateAsset(AssetRoute(selected = selected, form = "new", wizardStep = "kind"))
    }

    fun popAssetRoute() {
        if (navigating) return
        if (routePath.size > 1) {
            routePath = routePath.dropLast(1)
        } else {
            onBack()
        }
    }

    fun openBill(id: Long) {
        if (openingBill || billId != null) return
        openingBill = true
        scope.launch {
            try {
                billPreview = backPixels.snapshot()
                billId = id
            } finally {
                openingBill = false
            }
        }
    }

    fun closePage() {
        when {
            balanceOpen -> balanceOpen = false
            billId != null -> { billId = null; billPreview = null }
            routePath.size > 1 -> popAssetRoute()
            else -> onBack()
        }
    }
    val currentVisibility by rememberUpdatedState(onSubpageVisibilityChanged)
    LaunchedEffect(routePath, billId, balanceOpen) {
        currentVisibility(routePath.size > 1 || billId != null || balanceOpen)
    }
    DisposableEffect(Unit) { onDispose { currentVisibility(false) } }
    fun mutate(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (!actionBusy) { actionBusy = true; error = null
            scope.launch { runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { onSuccess() }
                .onFailure { error = it.message ?: "操作失败，请重试" }; actionBusy = false }
        }
    }
    val reduceMotion = LocalReduceMotion.current
    val balanceBackdrop = rememberGraphicsLayer()
    var balanceHostOrigin by remember { mutableStateOf(Offset.Zero) }
    val sampleBalanceBackdrop = android.os.Build.VERSION.SDK_INT >= 31 &&
        LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current &&
        balanceOpen && account != null
    val contentReady = !assetTransition.isRunning && assetTransition.currentState == routePath && assetTransition.targetState == routePath
    val innerBackEnabled = routePath.size > 1 && billId == null && !balanceOpen && !moreOpen &&
        !confirmArchive && !confirmDelete && pendingReverse == null

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) balanceHostOrigin = coords.boundsInRoot().topLeft
            },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (sampleBalanceBackdrop) Modifier.drawWithContent {
                    balanceBackdrop.record { this@drawWithContent.drawContent() }
                    drawLayer(balanceBackdrop)
                } else Modifier,
            ),
    ) {
    BackSurface(
        enabled = innerBackEnabled,
        onBack = ::popAssetRoute,
        routeKey = routePath,
        contentReady = contentReady,
        preview = previews[routePath.dropLast(1).lastOrNull()],
        pixels = backPixels,
        onPredictiveCommit = { predictiveSnapEpoch++ },
    ) {
    val predictiveCommit = LocalPredictiveBackCommit.current
    key(predictiveSnapEpoch) {
    assetTransition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            liquidNavigationTransition(
                goingBack = targetState.size < initialState.size,
                reduceMotion = reduceMotion,
                predictiveCommit = predictiveCommit,
            )
        },
    ) { shownPath ->
    val shownRoute = shownPath.last()
    val shownSelected = shownRoute.selected
    val shownForm = shownRoute.form
    val shownAccount = accounts.firstOrNull { it.id == shownSelected }
    CompositionLocalProvider(LocalBackActive provides (shownPath == routePath)) {
    when (shownForm) {
        "new" -> AssetAccountWizardStep(
            shownStep = shownRoute.wizardStep,
            draft = wizardDraft,
            onStepTo = { nextStep -> navigateAsset(AssetRoute(selected = shownSelected, form = "new", wizardStep = nextStep)) },
            onBack = { popAssetRoute() },
            onSaved = { savedId ->
                previews.clear()
                routePath = listOf(AssetRoute(), AssetRoute(selected = savedId))
            },
        )
        "edit" -> shownAccount?.let {
            AssetAccountForm(
                original = it,
                balance = AssetMath.balance(it.id, entries),
                onBack = { popAssetRoute() },
                onSaved = { popAssetRoute() },
                onEditBalance = { balanceOpen = true },
            )
        }
        "transfer" -> AssetTransferScreen(initialFromId = shownAccount?.id, onBack = { popAssetRoute() })
        else -> AssetScaffold(title = if (shownAccount != null) "账户详情" else "资产", isRoot = isTab && shownAccount == null,
            onBack = { closePage() }, onAdd = if (shownAccount == null) ({ startNewAccountWizard() }) else null) { padding ->
            AssetPageColumn(detail = shownAccount != null, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
                if (storeError != null || error != null) item {
                    InlineMessage(error ?: storeError.orEmpty(), isError = true, announce = true)
                }
                if (shownAccount == null) {
                    // 资产总览只列已设余额的账户；未设置的不占位，走空态。
                    val visible = accounts.filter { (showArchived || !it.archived) && it.initialized }
                    val anyValue = accounts.any { it.included && it.initialized }
                    val archivedCount = accounts.count { it.archived }
                    if (isTab) item { AssetNetWorthCard(summary = summary,
                        hidden = hidden, hasValue = anyValue,
                        onToggleHidden = {
                            hidden = !hidden; prefs.edit().putBoolean("hide_amount", hidden).apply()
                        }) }
                    // 账户少时一张卡即可；≥5 个才按类型分组，组标题右侧给小计。
                    val groups: List<Pair<String, List<AssetAccount>>> =
                        if (visible.size >= ASSET_GROUP_THRESHOLD) assetGroupOrder.mapNotNull { kind ->
                            visible.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { (assetKinds[kind] ?: kind) to it }
                        } + visible.filter { it.kind !in assetGroupOrder }.takeIf { it.isNotEmpty() }?.let { listOf("其他" to it) }.orEmpty()
                        else listOf("资金账户（${visible.size}）" to visible)
                    val grouped = visible.size >= ASSET_GROUP_THRESHOLD
                    groups.forEach { (title, rows) ->
                        item(key = "asset-group-$title") {
                            Column {
                                AssetGroupTitle(
                                    title,
                                    if (!grouped || hidden) null
                                    else assetMoney(rows.fold(0L) { sum, a -> Math.addExact(sum, AssetMath.balance(a.id, entries)) }),
                                )
                                InsetGroup {
                                    if (rows.isEmpty()) {
                                        ListRow(
                                            title = "还没有账户",
                                            subtitle = "点这里添加，保存后即可记账",
                                            onClick = { startNewAccountWizard() },
                                        )
                                    } else {
                                        DragReorderColumn(
                                            items = rows,
                                            keyOf = { it.id },
                                            onReorder = { next ->
                                                // 只传本组 id：reorderActive 在原槽位内重排，其它账户不动。
                                                runCatching {
                                                    AssetStore.reorderActive(next.map { it.id })
                                                }.onFailure { error = it.message ?: "排序失败，请重试" }
                                            },
                                        ) { row, _, _ ->
                                            AssetAccountRow(
                                                account = row,
                                                balance = AssetMath.balance(row.id, entries),
                                                hidden = hidden,
                                                dividerAbove = false,
                                                onClick = { navigateAsset(AssetRoute(selected = row.id)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (archivedCount > 0) {
                        item(key = "archived-toggle") {
                            InsetGroup {
                                ListRow(
                                    title = "显示已归档账户（$archivedCount）",
                                    dividerAbove = false,
                                    trailing = {
                                        AbSwitch(showArchived) {
                                            showArchived = it
                                            prefs.edit().putBoolean("show_archived", it).apply()
                                        }
                                    },
                                    onClick = {
                                        showArchived = !showArchived
                                        prefs.edit().putBoolean("show_archived", showArchived).apply()
                                    },
                                )
                            }
                        }
                    }

                } else {
                    item {
                        AccountBalanceCard(shownAccount, AssetMath.balance(shownAccount.id, entries), hidden,
                            onMore = { moreOpen = true }, onEditBalance = { balanceOpen = true })
                    }
                    if (periods == null) item { InlineMessage("正在读取账单…") }
                    else accountPeriodCards(periods.orEmpty(), expandedPeriods, hidden, clock.zone, currentPeriod.month,
                        onToggle = { key -> expandedPeriods = if (key in expandedPeriods) expandedPeriods - key else expandedPeriods + key },
                        reversedIds = entries.mapNotNull { it.reversalOf }.toSet(),
                        onBill = ::openBill, onReverse = { pendingReverse = it })
                }
            }
        }
    }
    }
    }
    }
    }
    }
    if (balanceOpen && account != null) {
        AssetBalanceSheet(
            account = account,
            current = AssetMath.balance(account.id, entries),
            backdrop = if (sampleBalanceBackdrop) balanceBackdrop else null,
            backdropOrigin = balanceHostOrigin,
            onDismiss = { balanceOpen = false },
        )
    }
    }
    if (moreOpen && account != null) AppDialog("账户操作", onDismiss = { moreOpen = false }) {
        ListRow(title = "纳入资产总览", trailing = { AbSwitch(account.included, onCheckedChange = { checked ->
            if (!actionBusy) mutate { AssetStore.updateAccount(account.copy(included = checked)) }
        }) })
        ListRow(title = "账户设置", chevron = true, onClick = {
            moreOpen = false
            navigateAsset(AssetRoute(selected = account.id, form = "edit"))
        })
        ListRow(title = if (account.archived) "恢复账户" else "归档账户",
            onClick = if (actionBusy) null else ({ moreOpen = false; confirmArchive = true }))
        ListRow(title = "删除账户", destructive = true, onClick = if (actionBusy) null else ({ moreOpen = false; confirmDelete = true }))
        SecondaryButton("关闭", onClick = { moreOpen = false }, modifier = Modifier.fillMaxWidth())
    }
    if (confirmArchive && account != null) AssetConfirmation(
        title = if (account.archived) "恢复此账户？" else "归档此账户？",
        message = if (account.archived) "恢复后可以为新账单选择此账户。" else "新账单不再显示此账户；原余额、历史和总览计入状态保留。",
        onDismiss = { confirmArchive = false }, onConfirm = {
            confirmArchive = false; mutate { AssetStore.setArchived(account.id, !account.archived) }
        })
    if (confirmDelete && account != null) {
        val linkedBillsCount = remember(account.id, allBills) {
            allBills.count { it.accountId == account.id || it.transferAccountId == account.id || it.reimburseAccountId == account.id }
        }
        DeleteAccountVerifyDialog(
            account = account,
            balance = AssetMath.balance(account.id, entries),
            linkedBillsCount = linkedBillsCount,
            busy = actionBusy,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                mutate(onSuccess = { previews.clear(); routePath = listOf(AssetRoute()) }) { AssetStore.deleteAccount(account.id) }
            },
        )
    }
    pendingReverse?.let { entry -> AssetConfirmation("撤销余额调整？", "将产生一条反向流水，原调整仍可追溯。",
        onDismiss = { pendingReverse = null }, onConfirm = { pendingReverse = null; mutate { AssetStore.reverseAdjustment(entry.id) } }) }
    SlideSubPageValue(value = billId, modifier = Modifier.fillMaxSize()) { id ->
        BillActionsHost(id, onClose = { billId = null; billPreview = null }, sourcePreview = billPreview)
    }
}

/**
 * 资产总览单卡：「净资产 + 眼睛」、超大金额；有负债时卡内分隔线下并排「总资产 │ 总负债」。
 *
 * 无负债时总资产必然等于净资产，那一行只会重复同一个数字，所以整行隐藏（用户 2026-11-12 选 A）。
 */
@Composable
private fun AssetNetWorthCard(
    summary: AssetSummary,
    hidden: Boolean,
    hasValue: Boolean,
    onToggleHidden: () -> Unit,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AbRadius.group))
            .background(p.cardBg)
            .padding(AbCardDensity.content),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("净资产", style = AbType.subhead, color = p.secondaryLabel)
            val eyeInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.size(48.dp)
                    .abPressFeedback(eyeInteraction, pressedScale = 0.90f, pressedAlpha = 0.68f)
                    .clip(CircleShape)
                    .clickable(interactionSource = eyeInteraction, indication = null) { haptic.select(); onToggleHidden() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (hidden) AbIcons.EyeOff else AbIcons.Eye,
                    contentDescription = if (hidden) "显示金额" else "隐藏金额",
                    modifier = Modifier.size(AbSize.iconSm),
                    tint = p.secondaryLabel,
                )
            }
        }
        Text(
            text = when {
                hidden -> "••••"
                !hasValue -> "还没有可计入的余额"
                else -> assetMoney(summary.net)
            },
            style = if (hasValue || hidden) AbType.amountHero else AbType.body,
            color = if (hasValue && !hidden && summary.net < 0) p.expense else if (hasValue || hidden) p.label else p.secondaryLabel,
        )
        if (hasValue && summary.debts > 0) {
            Box(Modifier.padding(vertical = AbSpacing.m).fillMaxWidth().height(1.dp).background(p.separator))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                AssetSplitValue("总资产", if (hidden) "••••" else assetMoney(summary.assets), p.label, Modifier.weight(1f))
                Box(Modifier.padding(horizontal = AbSpacing.m).width(1.dp).fillMaxHeight().background(p.separator))
                AssetSplitValue("总负债", if (hidden) "••••" else assetMoney(summary.debts), if (hidden) p.label else p.expense, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AssetSplitValue(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = AbType.caption1, color = LocalAbPalette.current.secondaryLabel, maxLines = 1)
        Text(value, style = AbType.amountRow, color = color, maxLines = 1)
    }
}

/** 分组标题一行：左侧组名，右侧（分组时）该组余额小计。 */
@Composable
private fun AssetGroupTitle(text: String, trailing: String?) {
    val p = LocalAbPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = AbSpacing.m, end = AbSpacing.m, top = AbSpacing.xs, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AbType.sectionHeader, color = p.secondaryLabel, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = AbType.footnote.tabular(), color = p.secondaryLabel, maxLines = 1)
    }
}

/**
 * 账户行：左图标 + 名称/类型 + 右侧金额。
 *
 * 右侧不放电竞式的「欠款/透支」前缀，只在真的为负时才加，正常账户就是一枚干净的金额，
 * 这样一列数字能直接对齐比较（参考图的做法）。
 */
@Composable
private fun AssetAccountRow(
    account: AssetAccount,
    balance: Long,
    hidden: Boolean,
    dividerAbove: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAbPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Reserve readable space for the account name; grow vertically for long balances.
        val amountMaxWidth = maxWidth * 0.45f
        val subtitleText = buildList {
            assetKinds[account.kind]?.let { add(it) }
            account.cardTail?.takeIf { it.isNotBlank() }?.let { add("尾号 $it") }
            if (account.kind == "CREDIT" && account.repaymentDay != null) {
                add("每月${account.repaymentDay}日还款")
            }
            if (account.archived) add("已归档")
        }.joinToString(" · ").ifEmpty { null }
        ListRow(
            title = account.name,
            subtitle = subtitleText,
            leading = { AccountAvatar(account = account, size = AbCardDensity.settingsIcon) },
            trailing = {
                Column(Modifier.widthIn(max = amountMaxWidth), horizontalAlignment = Alignment.End) {
                    Text(
                        text = when {
                            hidden && account.initialized -> "••••"
                            !account.initialized -> "未设置"
                            balance < 0 -> assetMoney(balance)
                            else -> assetMoney(balance)
                        },
                        style = AbType.amountRow,
                        color = if (account.initialized && balance < 0) p.expense else p.label,
                        textAlign = TextAlign.End,
                    )
                }
            },
            dividerAbove = dividerAbove,
            onClick = onClick,
        )
    }
}

@Composable
private fun AssetScaffold(title: String, isRoot: Boolean, onBack: () -> Unit, onAdd: (() -> Unit)?,
    content: @Composable (PaddingValues) -> Unit) {
    val actions: @Composable RowScope.() -> Unit = {
        if (onAdd != null) FloatingHeaderAction(AbIcons.Plus, "添加账户", onAdd)
    }
    if (isRoot) PageScaffold(title = title, listState = androidx.compose.foundation.lazy.rememberLazyListState(),
        compact = true, floatingHeader = true, actions = actions, content = content)
    else SubPageScaffold(title = title, onBack = onBack, reserveDock = false, actions = actions, content = content)
}

@Composable
private fun AssetConfirmation(
    title: String,
    message: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppAlertDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        confirmLabel = "确认",
        onConfirm = onConfirm,
        dismissLabel = "取消",
        destructive = destructive,
    )
}

@Composable
private fun DeleteAccountVerifyDialog(
    account: AssetAccount,
    balance: Long,
    linkedBillsCount: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val p = LocalAbPalette.current
    val verifyCode = rememberSaveable(account.id) { kotlin.random.Random.nextInt(1000, 10000).toString() }
    var input by rememberSaveable(account.id) { mutableStateOf("") }
    val matched = input == verifyCode
    AppDialog(title = "删除账户「${account.name}」？", onDismiss = { if (!busy) onDismiss() }) {
        val summary = buildString {
            append("将永久删除此账户及其余额流水")
            if (account.initialized) append("（当前 ${positionLabel(account, balance)}）")
            append("，且无法撤销。")
            if (linkedBillsCount > 0) append("\n已关联的 $linkedBillsCount 笔账单仍保留在账本中，仅解除余额联动。")
        }
        Text(summary, style = AbType.body, color = p.secondaryLabel)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AbRadius.row))
                .background(p.fill)
                .padding(vertical = AbSpacing.m, horizontal = AbSpacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xxs),
        ) {
            Text("输入下方 4 位随机数字以确认删除", style = AbType.footnote, color = p.secondaryLabel)
            Text(
                verifyCode.toCharArray().joinToString(" "),
                style = AbType.title2.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = p.expense,
            )
        }
        AbTextField(
            value = input,
            onValueChange = { next -> input = next.filter(Char::isDigit).take(4) },
            placeholder = "请输入 4 位确认数字",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.fillMaxWidth(),
            onDone = { if (matched && !busy) onConfirm() },
        )
        AppDialogActions(
            confirmLabel = if (busy) "删除中…" else "确认删除",
            confirmEnabled = matched && !busy,
            destructive = true,
            onDismiss = { if (!busy) onDismiss() },
            onConfirm = { if (matched && !busy) onConfirm() },
        )
    }
}

/** 编辑已有账户设置；新增统一走 [AssetAccountWizardStep]。 */
@Composable
private fun AssetAccountForm(
    original: AssetAccount,
    balance: Long,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onEditBalance: () -> Unit,
) {
    val p = LocalAbPalette.current
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var kind by rememberSaveable(original.id) { mutableStateOf(original.kind) }
    var aliases by rememberSaveable(original.id) { mutableStateOf(
        runCatching { (MiniJson.decode(original.aliases) as? List<*>)?.filterIsInstance<String>()?.joinToString("，") }.getOrNull().orEmpty()
    ) }
    var cardTail by rememberSaveable(original.id) { mutableStateOf(original.cardTail.orEmpty()) }
    var included by rememberSaveable(original.id) { mutableStateOf(original.included) }
    var quickSelect by rememberSaveable(original.id) { mutableStateOf(original.quickSelect) }
    var creditLimit by rememberSaveable(original.id) { mutableStateOf(original.creditLimitCents?.let(::assetNumber).orEmpty()) }
    var billingDayText by rememberSaveable(original.id) { mutableStateOf(original.billingDay?.toString().orEmpty()) }
    var repaymentDayText by rememberSaveable(original.id) { mutableStateOf(original.repaymentDay?.toString().orEmpty()) }
    var iconKey by rememberSaveable(original.id) { mutableStateOf(original.iconKey) }
    var colorHex by rememberSaveable(original.id) { mutableStateOf(original.colorHex) }
    var stylePickerOpen by remember { mutableStateOf(false) }
    var kindPickerOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val editableKinds = remember(original.kind) {
        if (original.kind in assetKinds) assetKinds else assetKinds + (original.kind to original.kind)
    }
    val kindLabel = editableKinds[kind] ?: kind
    val labelWidth = 76.dp
    SubPageScaffold("账户设置", onBack = { if (!busy) onBack() }, reserveDock = false) { padding ->
        SectionLazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
        ) {
            item {
                InsetGroup {
                    ListRow(
                        title = "图标与配色",
                        leading = {
                            AccountAvatar(
                                name = name.ifBlank { original.name },
                                size = AbCardDensity.settingsIcon,
                                kind = kind,
                                iconKey = iconKey,
                                colorHex = colorHex,
                            )
                        },
                        chevron = true,
                        onClick = { stylePickerOpen = true },
                    )
                    InputRow(
                        label = "名称",
                        value = name,
                        onValueChange = { name = it; error = null },
                        placeholder = "账户名称",
                        dividerAbove = true,
                        labelWidth = labelWidth,
                    )
                    ListRow(
                        title = "类型",
                        dividerAbove = true,
                        trailing = { Text(kindLabel, style = AbType.body, color = p.secondaryLabel) },
                        chevron = true,
                        onClick = { kindPickerOpen = true },
                    )
                    if (kind in setOf("SAVINGS", "CREDIT")) {
                        InputRow(
                            label = "卡尾号",
                            value = cardTail,
                            onValueChange = { cardTail = it.take(8); error = null },
                            placeholder = "后 4 位（选填）",
                            dividerAbove = true,
                            labelWidth = labelWidth,
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    InputRow(
                        label = "别名",
                        value = aliases,
                        onValueChange = { aliases = it; error = null },
                        placeholder = "逗号分隔（选填）",
                        dividerAbove = true,
                        labelWidth = labelWidth,
                    )
                }
            }
            if (kind == "CREDIT") {
                item {
                    InsetGroup {
                        InputRow(
                            label = "信用额度",
                            value = creditLimit,
                            onValueChange = { creditLimit = it; error = null },
                            placeholder = "元（选填）",
                            labelWidth = labelWidth,
                            keyboardType = KeyboardType.Decimal,
                        )
                        InputRow(
                            label = "账单日",
                            value = billingDayText,
                            onValueChange = { billingDayText = it.filter(Char::isDigit).take(2); error = null },
                            placeholder = "每月 1–31 日（选填）",
                            dividerAbove = true,
                            labelWidth = labelWidth,
                            keyboardType = KeyboardType.Number,
                        )
                        InputRow(
                            label = "还款日",
                            value = repaymentDayText,
                            onValueChange = { repaymentDayText = it.filter(Char::isDigit).take(2); error = null },
                            placeholder = "每月 1–31 日（选填）",
                            dividerAbove = true,
                            labelWidth = labelWidth,
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
            }
            item {
                InsetGroup {
                    ListRow(title = "纳入资产总览", trailing = { AbSwitch(included) { included = it } })
                    ListRow(
                        title = "确认卡显示",
                        dividerAbove = true,
                        trailing = { AbSwitch(quickSelect) { quickSelect = it } },
                    )
                    ListRow(
                        title = if (original.initialized) "调整余额" else "设置余额",
                        dividerAbove = true,
                        trailing = { Text(positionLabel(original, balance), style = AbType.body, color = p.secondaryLabel) },
                        chevron = true,
                        onClick = onEditBalance,
                    )
                }
            }
            if (error != null) item { InlineMessage(error.orEmpty(), isError = true, announce = true) }
            item(contentType = SectionRole.ACTION) { PrimaryButton("保存账户", modifier = Modifier.fillMaxWidth(), busy = busy, onClick = {
                if (!busy) { busy = true; error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            val trimmed = name.trim(); require(trimmed.isNotEmpty()) { "请输入账户名称" }
                            val aliasList = aliases.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct()
                            val tailClean = cardTail.trim().takeIf { it.isNotEmpty() }
                            val limitCents = if (kind == "CREDIT" && creditLimit.isNotBlank()) {
                                AssetMath.parseCents(creditLimit).also { require(it >= 0L) { "信用额度不能为负" } }
                            } else null
                            val bDay = if (kind == "CREDIT" && billingDayText.isNotBlank()) {
                                billingDayText.toIntOrNull()?.also { require(it in 1..31) { "账单日须在 1–31 之间" } }
                                    ?: error("账单日格式无效")
                            } else null
                            val rDay = if (kind == "CREDIT" && repaymentDayText.isNotBlank()) {
                                repaymentDayText.toIntOrNull()?.also { require(it in 1..31) { "还款日须在 1–31 之间" } }
                                    ?: error("还款日格式无效")
                            } else null
                            AssetStore.updateAccount(original.copy(
                                name = trimmed,
                                kind = kind,
                                aliases = MiniJson.encode(aliasList),
                                included = included,
                                quickSelect = quickSelect,
                                cardTail = tailClean,
                                creditLimitCents = limitCents,
                                billingDay = bDay,
                                repaymentDay = rDay,
                                iconKey = iconKey,
                                colorHex = colorHex,
                            ))
                            original.id
                        } }.onSuccess(onSaved).onFailure { error = it.message ?: "保存失败" }
                        busy = false
                    }
                }
            }) }
        }
    }
    if (kindPickerOpen) {
        AppChoiceDialog(
            title = "选择账户类型",
            options = editableKinds.map { (value, label) -> AppDialogChoice(value, label) },
            selectedKey = kind,
            onChoose = { kind = it; kindPickerOpen = false },
            onDismiss = { kindPickerOpen = false },
        )
    }
    if (stylePickerOpen) {
        val display = name.ifBlank { original.name }
        IconColorStyleDialog(
            title = "账户图标与配色",
            previewName = display,
            initialIconKey = iconKey,
            initialColorHex = colorHex,
            defaultIconKey = AccountLogos.defaultIconKey(kind, display),
            defaultColor = AccountLogos.defaultColor(kind, display),
            iconGroups = AccountLogos.iconGroups,
            onDismiss = { stylePickerOpen = false },
            onConfirm = { nextIcon, nextColor ->
                iconKey = nextIcon
                colorHex = nextColor
                stylePickerOpen = false
            },
        )
    }
}

/** 同窗底部悬浮调整余额卡：共享液态玻璃质感、下拉手势关闭、紧凑大号金额与快捷原因胶囊，防窄屏/键盘裁剪。 */
@Composable
private fun AssetBalanceSheet(
    account: AssetAccount,
    current: Long,
    backdrop: GraphicsLayer? = null,
    backdropOrigin: Offset = Offset.Zero,
    onDismiss: () -> Unit,
) {
    val debt = AssetMath.isDebt(account.kind)
    var amount by rememberSaveable(account.id) { mutableStateOf(if (account.initialized) assetNumber(if (debt) -current else current) else "") }
    var reason by rememberSaveable(account.id) { mutableStateOf(if (account.initialized) "" else "设置当前余额") }
    var preview by remember { mutableStateOf<Long?>(null) }
    var previewCurrent by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = if (account.initialized) "调整余额" else "设置当前余额"
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val slideStartPx = minOf(screenHeightPx * 0.55f, with(density) { 420.dp.toPx() })
    val offsetY = remember { Animatable(if (reduceMotion) 0f else slideStartPx) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val animAlpha = remember { Animatable(if (reduceMotion) 0f else 1f) }
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    fun dismissAnimated(dragVelocity: Float? = null) {
        if (busy || closing) return
        closing = true
        scope.launch {
            try {
                if (reduceMotion) {
                    animAlpha.animateTo(0f, tween(110, easing = AbEasing.EaseOut))
                } else if (dragVelocity != null) {
                    offsetY.animateTo(slideStartPx, AbSpring.sheetFloat(false), initialVelocity = dragVelocity)
                } else {
                    offsetY.animateTo(slideStartPx, tween(175, easing = AbEasing.EaseOut))
                }
                latestOnDismiss()
            } finally {
                closing = false
            }
        }
    }

    ActiveBackHandler { dismissAnimated() }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            offsetY.snapTo(0f)
            animAlpha.animateTo(1f, tween(140, easing = AbEasing.EaseOut))
        } else {
            animAlpha.snapTo(1f)
            offsetY.animateTo(0f, AbSpring.sheetFloat(false))
        }
    }

    val maxDim = if (isDark) 0.40f else 0.24f
    val fieldShape = RoundedCornerShape(AbRadius.row)
    val fieldBg = p.fill
    val quickReasons = remember(account.initialized) {
        if (account.initialized) listOf("对账校准", "盘点差异", "利息收益", "手续费补记")
        else listOf("设置当前余额", "初始启用")
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val y = if (dragging) dragOffset else offsetY.value
                val progress = (1f - (y / slideStartPx.coerceAtLeast(1f))).coerceIn(0f, 1f)
                drawRect(Color.Black.copy(alpha = maxDim * progress * animAlpha.value))
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy && !closing,
                onClick = { dismissAnimated() },
            )
            .statusBarsPadding()
            .padding(floatingSheetViewportPadding()),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            GlassBottomSheetBox(
                backdrop = backdrop,
                backdropOrigin = backdropOrigin,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else offsetY.value
                        alpha = animAlpha.value
                    }
                    .heightIn(max = maxHeight * 0.90f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                CompositionLocalProvider(LocalInModalSurface provides true) {
                    Column(Modifier.fillMaxWidth()) {
                        SheetDragGrabber(
                            enabled = !busy && !closing,
                            offsetY = offsetY,
                            onDragOffsetChange = { isDrag, nextOffset ->
                                dragging = isDrag
                                dragOffset = nextOffset
                            },
                            onDismiss = { dismissAnimated() },
                            onDragDismiss = { v -> dismissAnimated(v) },
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(start = AbSpacing.xl, end = AbSpacing.xl, top = AbSpacing.xs, bottom = AbSpacing.m),
                            verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                            ) {
                                Text(title, style = AbType.navTitle, color = p.label, modifier = Modifier.weight(1f))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(AbRadius.capsule))
                                        .background(fieldBg)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "${account.name} · ${positionLabel(account, current)}",
                                        style = AbType.caption1,
                                        color = p.secondaryLabel,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (debt) "当前欠款（元）" else "当前余额（元）",
                                    style = AbType.caption1,
                                    color = p.secondaryLabel,
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(fieldShape)
                                        .background(fieldBg)
                                        .padding(horizontal = AbSpacing.l, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("¥", style = AbType.amountTitle, color = p.accent, modifier = Modifier.padding(end = 6.dp))
                                    BasicTextField(
                                        value = amount,
                                        onValueChange = { amount = it; error = null },
                                        singleLine = true,
                                        enabled = !busy,
                                        textStyle = AbType.amountTitle.copy(color = p.label),
                                        cursorBrush = SolidColor(p.accent),
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics { contentDescription = if (debt) "当前欠款" else "当前余额" },
                                        decorationBox = { inner ->
                                            Box(contentAlignment = Alignment.CenterStart) {
                                                if (amount.isEmpty()) {
                                                    Text(
                                                        if (debt) "欠款填正数，溢缴填负数" else "例如 1000.00，透支填负数",
                                                        style = AbType.body,
                                                        color = p.tertiaryLabel,
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                BasicTextField(
                                    value = reason,
                                    onValueChange = { reason = it; error = null },
                                    singleLine = true,
                                    enabled = !busy,
                                    textStyle = AbType.body.copy(color = p.label),
                                    cursorBrush = SolidColor(p.accent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(fieldShape)
                                        .background(fieldBg)
                                        .heightIn(min = 44.dp)
                                        .padding(horizontal = AbSpacing.l, vertical = 10.dp)
                                        .semantics { contentDescription = "调整原因" },
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (reason.isEmpty()) Text("调整原因（例如：对账校准）", style = AbType.body, color = p.tertiaryLabel)
                                            inner()
                                        }
                                    },
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                                ) {
                                    quickReasons.forEach { preset ->
                                        val selected = reason == preset
                                        val chipInteraction = remember(preset) { MutableInteractionSource() }
                                        Box(
                                            Modifier
                                                .heightIn(min = AbSize.minTouch)
                                                .abPressFeedback(chipInteraction, enabled = !busy, pressedScale = 0.955f, pressedAlpha = 0.8f)
                                                .clickable(interactionSource = chipInteraction, indication = null, enabled = !busy) {
                                                    reason = preset; error = null
                                                }
                                                .semantics {
                                                    role = Role.Button
                                                    this.selected = selected
                                                    stateDescription = if (selected) "已选中" else "未选中"
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(AbRadius.capsule))
                                                    .background(if (selected) p.accent.copy(alpha = if (isDark) 0.28f else 0.16f) else p.fill)
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                            ) {
                                                Text(
                                                    preset,
                                                    style = AbType.caption1.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                                                    color = if (selected) p.accent else p.secondaryLabel,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (error != null) InlineMessage(error.orEmpty(), isError = true, announce = true)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = AbSpacing.xl, end = AbSpacing.xl, bottom = AbSpacing.l),
                            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                        ) {
                            SecondaryButton(
                                "取消",
                                onClick = { dismissAnimated() },
                                modifier = Modifier.weight(1f),
                                enabled = !busy && !closing,
                            )
                            PrimaryButton(
                                "预览并确认",
                                modifier = Modifier.weight(1f),
                                busy = busy,
                                enabled = !closing,
                                onClick = {
                                    runCatching {
                                        require(reason.isNotBlank()) { "请填写原因" }
                                        val cents = AssetMath.parseCents(amount)
                                        val target = if (debt) Math.negateExact(cents) else cents
                                        require(target != Long.MIN_VALUE) { "金额超出范围" }
                                        Math.subtractExact(target, current)
                                        target
                                    }.onSuccess { previewCurrent = current; preview = it; error = null }
                                        .onFailure { error = it.message ?: "金额格式无效" }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    preview?.let { target -> AssetConfirmation(
        if (account.initialized) "确认余额调整" else "确认余额起点",
        "${positionLabel(account.copy(initialized = true), target)}\n登记差额 ${assetMoney(Math.subtractExact(target, previewCurrent))}\n不会计入收入或支出。",
        onDismiss = { preview = null },
        onConfirm = {
            preview = null; busy = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        AssetStore.setBalance(account.id, target, reason.trim(), expectedPosition = previewCurrent)
                    }
                }.onSuccess { dismissAnimated() }.onFailure { error = it.message ?: "设置失败" }
                busy = false
            }
        },
    ) }
}

/** Shared transfer editor: one operation with two accounts and an optional expense fee. */
@Composable
fun AssetTransferScreen(initialFromId: String? = null, original: BillEntity? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val accounts by AssetStore.accounts.collectAsStateWithLifecycle()
    val entries by AssetStore.entries.collectAsStateWithLifecycle()
    var from by rememberSaveable(original?.id) { mutableStateOf(original?.accountId ?: initialFromId) }
    var to by rememberSaveable(original?.id) { mutableStateOf(original?.transferAccountId) }
    var amount by rememberSaveable(original?.id) { mutableStateOf(original?.let { assetNumber(it.priceCents) }.orEmpty()) }
    var fee by rememberSaveable(original?.id) { mutableStateOf(original?.let { assetNumber(it.transferFeeCents) } ?: "0") }
    var note by rememberSaveable(original?.id) { mutableStateOf(original?.note.orEmpty()) }
    var billTime by rememberSaveable(original?.id) { mutableStateOf(original?.timeMillis ?: System.currentTimeMillis()) }
    var dateOpen by remember { mutableStateOf(false) }
    val date = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(billTime), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    var preview by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var previewBalances by remember { mutableStateOf(0L to 0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val operationId by rememberSaveable(original?.id) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val eligible = accounts.filter { !it.archived && it.initialized }
    val fromAccount = accounts.firstOrNull { it.id == from }
    val toAccount = accounts.firstOrNull { it.id == to }
    val initialFields = rememberSaveable(original?.id) { listOf(from.orEmpty(), to.orEmpty(), amount, fee, note, billTime.toString()) }
    var discardChanges by remember { mutableStateOf(false) }
    val isDirty = listOf(from.orEmpty(), to.orEmpty(), amount, fee, note, billTime.toString()) != initialFields
    fun requestClose() {
        if (!busy) {
            if (isDirty) discardChanges = true else onBack()
        }
    }
    ActiveBackHandler(enabled = original != null || isDirty) { requestClose() }
    if (discardChanges) DiscardChangesDialog(onContinue = { discardChanges = false }, onDiscard = { discardChanges = false; onBack() })
    SubPageScaffold(if (original == null) "转账 / 还款" else "修改转账 / 还款", onBack = { requestClose() }, reserveDock = false) { padding ->
        io.github.mymoney.ui.design.SectionLazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
            item(contentType = io.github.mymoney.ui.design.SectionRole.HEADER) { GroupHeader("转出账户") }
            item {
                InsetGroup {
                    eligible.forEach { candidate ->
                        ListRow(
                            title = candidate.name,
                            subtitle = positionLabel(candidate, AssetMath.balance(candidate.id, entries)),
                            trailing = {
                                if (from == candidate.id) {
                                    Icon(AbIcons.Check, contentDescription = "已选", tint = LocalAbPalette.current.accent, modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = { from = candidate.id },
                        )
                    }
                }
            }
            item(contentType = io.github.mymoney.ui.design.SectionRole.HEADER) { GroupHeader("转入 / 还款账户") }
            item {
                InsetGroup {
                    eligible.forEach { candidate ->
                        ListRow(
                            title = candidate.name,
                            subtitle = positionLabel(candidate, AssetMath.balance(candidate.id, entries)),
                            trailing = {
                                if (to == candidate.id) {
                                    Icon(AbIcons.Check, contentDescription = "已选", tint = LocalAbPalette.current.accent, modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = { to = candidate.id },
                        )
                    }
                }
            }
            if (eligible.size < 2) item { InlineMessage("请先为至少两个可用账户设置余额。", isError = true) }
            item {
                InsetGroup {
                    InputRow(label = "本金", value = amount, onValueChange = { amount = it }, placeholder = "元", labelWidth = 76.dp, keyboardType = KeyboardType.Decimal)
                    InputRow(label = "手续费", value = fee, onValueChange = { fee = it }, placeholder = "元，可为 0", dividerAbove = true, labelWidth = 76.dp, keyboardType = KeyboardType.Decimal)
                    ListRow(title = "账单时间", dividerAbove = true, trailing = { Text(date, style = AbType.footnote.tabular(), color = LocalAbPalette.current.secondaryLabel) }, chevron = true, onClick = if (busy) null else { { dateOpen = true } })
                    InputRow(label = "备注", value = note, onValueChange = { note = it }, placeholder = "选填", dividerAbove = true, labelWidth = 76.dp)
                }
            }
            if (error != null) item { InlineMessage(error.orEmpty(), isError = true, announce = true) }
            item(contentType = io.github.mymoney.ui.design.SectionRole.ACTION) { PrimaryButton("预览转账", modifier = Modifier.fillMaxWidth(), busy = busy, onClick = {
                error = null
                runCatching {
                    require(fromAccount != null && toAccount != null && from != to) { "请选择两个不同账户" }
                    require(fromAccount.initialized && toAccount.initialized && !fromAccount.archived && !toAccount.archived) { "账户须设置余额且未归档" }
                    val cents = AssetMath.parseCents(amount); val feeCents = AssetMath.parseCents(fee.ifBlank { "0" })
                    require(cents > 0 && feeCents >= 0) { "本金须大于0，手续费不能为负" }
                    Math.addExact(cents, feeCents)
                    val time = billTime
                    fun originalNet(accountId: String): Long {
                        if (original?.assetLinked != true) return 0L
                        val out = if (original.accountId == accountId) Math.negateExact(Math.addExact(original.priceCents, original.transferFeeCents)) else 0L
                        val incoming = if (original.transferAccountId == accountId) original.priceCents else 0L
                        return Math.addExact(out, incoming)
                    }
                    val fromBase = Math.subtractExact(AssetMath.balance(fromAccount.id, entries), originalNet(fromAccount.id))
                    val toBase = Math.subtractExact(AssetMath.balance(toAccount.id, entries), originalNet(toAccount.id))
                    previewBalances = Math.subtractExact(fromBase, Math.addExact(cents, feeCents)) to Math.addExact(toBase, cents)
                    Triple(cents, feeCents, time)
                }.onSuccess { preview = it }.onFailure { error = it.message ?: "请检查输入" }
            }) }
        }
    }
    if (dateOpen) LedgerDateTimeDialog(billTime, onDismiss = { dateOpen = false }, onConfirm = { billTime = it; dateOpen = false })
    val target = preview
    if (target != null && fromAccount != null && toAccount != null) {
        val fromAfter = previewBalances.first
        val toAfter = previewBalances.second
        AssetConfirmation("确认转账 / 还款", buildString {
            append(fromAccount.name).append(" → ").append(toAccount.name).append("\n本金 ").append(assetMoney(target.first))
            if (target.second > 0) append("\n手续费支出 ").append(assetMoney(target.second))
            append("\n转出后：").append(positionLabel(fromAccount, fromAfter))
            append("\n转入后：").append(positionLabel(toAccount, toAfter))
            if (fromAfter < 0 && !AssetMath.isDebt(fromAccount.kind)) append("\n转出账户将透支，请核对。")
            if (AssetMath.isDebt(toAccount.kind) && toAfter > 0) append("\n超过当前欠款，超还部分形成溢缴款。")
            append("\n保存即影响当前余额，含补记和未来日期。")
        }, onDismiss = { preview = null }, onConfirm = {
            preview = null; busy = true
            BillWriter.saveTransfer(context, fromAccount.id, toAccount.id, target.first, target.second, note.trim(), target.third, original, operationId = operationId) { result ->
                busy = false; result.onSuccess { onBack() }.onFailure { error = it.message ?: "转账失败，请重试" }
            }
        })
    }
}

@Composable
private fun AssetPageColumn(detail: Boolean, modifier: Modifier, contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    if (detail) LazyColumn(modifier, contentPadding = contentPadding, content = content)
    else SectionLazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)
}
