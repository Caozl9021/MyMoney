package com.example.mymoney.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mymoney.data.*
import com.example.mymoney.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val assetKinds = linkedMapOf("CASH" to "现金", "PAYMENT" to "支付账户", "SAVINGS" to "储蓄卡", "CREDIT" to "信用账户", "OTHER_ASSET" to "其他资产", "OTHER_DEBT" to "其他负债")
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
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var billId by rememberSaveable { mutableStateOf<Long?>(null) }
    val prefs = remember { context.getSharedPreferences("asset_display", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("hide_amount", false)) }
    var showArchived by remember { mutableStateOf(prefs.getBoolean("show_archived", false)) }
    var moreOpen by remember { mutableStateOf(false) }
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

    fun closePage() {
        when { billId != null -> billId = null; form != null -> form = null; selected != null -> selected = null; else -> onBack() }
    }
    val currentVisibility by rememberUpdatedState(onSubpageVisibilityChanged)
    LaunchedEffect(form, selected, billId) { currentVisibility(form != null || selected != null || billId != null) }
    DisposableEffect(Unit) { onDispose { currentVisibility(false) } }
    ActiveBackHandler(enabled = !isTab || form != null || selected != null || billId != null) { closePage() }
    fun mutate(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (!actionBusy) { actionBusy = true; error = null
            scope.launch { runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { onSuccess() }
                .onFailure { error = it.message ?: "操作失败，请重试" }; actionBusy = false }
        }
    }
    when (form) {
        "candidates" -> AccountCandidatesPage(onBack = { form = null })
        "new", "edit" -> AssetAccountForm(if (form == "edit") account else null,
            onBack = { form = null }, onSaved = { selected = it; form = null })
        "balance" -> account?.let { AssetBalanceForm(it, AssetMath.balance(it.id, entries), onBack = { form = null }) }
        "transfer" -> AssetTransferScreen(initialFromId = account?.id, onBack = { form = null })
        else -> AssetScaffold(title = if (account != null) "账户详情" else "资产", isRoot = isTab && account == null,
            onBack = { closePage() }, onAdd = if (account == null) ({ form = "new" }) else null) { padding ->
            AssetPageColumn(detail = account != null, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
                if (storeError != null || error != null) item {
                    InlineMessage(error ?: storeError.orEmpty(), isError = true, announce = true)
                }
                if (account == null) {
                    val visible = accounts.filter { showArchived || !it.archived }
                    val anyValue = accounts.any { it.included && it.initialized }
                    if (!isTab) item { InsetGroup {
                        ListRow(title = "转账 / 还款", subtitle = "两端资金划转，本金不计收支", chevron = true,
                            dividerAbove = false,
                            onClick = if (accounts.count { !it.archived && it.initialized } >= 2) { { form = "transfer" } } else null)
                        ListRow(title = "账户管理", subtitle = "确认卡账户清单、排序与默认账户", chevron = true,
                            dividerAbove = true, onClick = { form = "candidates" })
                        ListRow(title = "显示归档账户", subtitle = "归档保留余额，默认继续纳入总览", dividerAbove = true,
                            trailing = { AbSwitch(showArchived) { showArchived = it; prefs.edit().putBoolean("show_archived", it).apply() } },
                            onClick = { showArchived = !showArchived; prefs.edit().putBoolean("show_archived", showArchived).apply() })
                    } }
                    if (isTab) item { AssetNetWorthCard(summary = summary,
                        hidden = hidden, hasValue = anyValue,
                        onToggleHidden = {
                            hidden = !hidden; prefs.edit().putBoolean("hide_amount", hidden).apply()
                        }) }
                    if (isTab) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
                            AssetMetricTile("总资产", summary.assets, up = true, hidden = hidden, hasValue = anyValue, modifier = Modifier.weight(1f))
                            AssetMetricTile("总负债", summary.debts, up = false, hidden = hidden, hasValue = anyValue, modifier = Modifier.weight(1f))
                        }
                    }
                    item { AssetGroupTitle("资金账户（${visible.size}）",
                        if (!anyValue || hidden) null else "余额 ${assetMoney(summary.net)}") }
                    item {
                        InsetGroup {
                            if (visible.isEmpty()) {
                                ListRow(title = "还没有账户", subtitle = "添加账户后即可设置当前余额", onClick = { form = "new" })
                            } else visible.forEachIndexed { index, row ->
                                AssetAccountRow(account = row, balance = AssetMath.balance(row.id, entries), hidden = hidden,
                                    dividerAbove = index > 0, onClick = { selected = row.id })
                            }
                        }
                    }

                } else {
                    item {
                        AccountBalanceCard(account, AssetMath.balance(account.id, entries), hidden,
                            onMore = { moreOpen = true }, onEditBalance = { form = "balance" })
                    }
                    if (periods == null) item { InlineMessage("正在读取账单…") }
                    else accountPeriodCards(periods.orEmpty(), expandedPeriods, hidden, clock.zone, currentPeriod.month,
                        onToggle = { key -> expandedPeriods = if (key in expandedPeriods) expandedPeriods - key else expandedPeriods + key },
                        reversedIds = entries.mapNotNull { it.reversalOf }.toSet(),
                        onBill = { billId = it }, onReverse = { pendingReverse = it })
                }
            }
        }
    }
    if (moreOpen && account != null) AppDialog("账户操作", onDismiss = { moreOpen = false }) {
        ListRow(title = "纳入资产总览", trailing = { AbSwitch(account.included, onCheckedChange = { checked ->
            if (!actionBusy) mutate { AssetStore.updateAccount(account.copy(included = checked)) }
        }) })
        ListRow(title = if (account.initialized) "调整余额" else "设置当前余额", chevron = true,
            onClick = if (actionBusy) null else ({ moreOpen = false; form = "balance" }))
        ListRow(title = "账户设置", chevron = true, onClick = { moreOpen = false; form = "edit" })
        ListRow(title = if (account.archived) "恢复账户" else "归档账户",
            onClick = if (actionBusy) null else ({ moreOpen = false; confirmArchive = true }))
        if (!account.initialized && entries.none { it.accountId == account.id } && allBills.none { it.accountId == account.id || it.transferAccountId == account.id })
            ListRow(title = "删除空账户", destructive = true, onClick = if (actionBusy) null else ({ moreOpen = false; confirmDelete = true }))
        SecondaryButton("关闭", onClick = { moreOpen = false }, modifier = Modifier.fillMaxWidth())
    }
    if (confirmArchive && account != null) AssetConfirmation(
        title = if (account.archived) "恢复此账户？" else "归档此账户？",
        message = if (account.archived) "恢复后可以为新账单选择此账户。" else "新账单不再显示此账户；原余额、历史和总览计入状态保留。",
        onDismiss = { confirmArchive = false }, onConfirm = {
            confirmArchive = false; mutate { AssetStore.setArchived(account.id, !account.archived) }
        })
    if (confirmDelete && account != null) AssetConfirmation("删除空账户？", "将移除此账户名称与别名。已有余额、流水或账单关联的账户会被保留，请改用归档。",
        onDismiss = { confirmDelete = false }, onConfirm = {
            confirmDelete = false
            mutate(onSuccess = { selected = null }) { AssetStore.deleteEmptyAccount(account.id) }
        })
    pendingReverse?.let { entry -> AssetConfirmation("撤销余额调整？", "将产生一条反向流水，原调整仍可追溯。",
        onDismiss = { pendingReverse = null }, onConfirm = { pendingReverse = null; mutate { AssetStore.reverseAdjustment(entry.id) } }) }
    billId?.let { id -> BillActionsHost(id, onClose = { billId = null }) }
}

/**
 * 净资产主卡：顶部一行「净资产 + 眼睛」，下面是超大金额。
 *
 * 金额是这一页唯一的主角，所以只放一枚数字；总资产/总负债拆到下面两枚并排小卡，
 * 避免同一组数字在一屏里重复两遍（参考图里主卡下面那一行也只是补充信息）。
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
            .clip(RoundedCornerShape(AbRadius.card))
            .background(p.cardBg)
            .padding(AbCardDensity.content),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("净资产", style = AbType.subhead, color = p.secondaryLabel)
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { haptic.select(); onToggleHidden() },
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
            style = AbType.amountHero,
            color = p.label,
            modifier = Modifier.padding(top = AbSpacing.xs),
        )
        if (summary.unknown > 0) {
            Text(
                text = "${summary.unknown} 个账户未设置余额，合计尚不完整",
                style = AbType.footnote,
                color = p.secondaryLabel,
                modifier = Modifier.padding(top = AbSpacing.s),
            )
        }
    }
}

/** 并排小卡：左上角一枚实心色块图标，右侧标签 + 金额。 */
@Composable
private fun AssetMetricTile(
    label: String,
    cents: Long,
    up: Boolean,
    hidden: Boolean,
    hasValue: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalAbPalette.current
    // 资产走收入绿，负债走与参考图同款的深色块；两者都用 pageBg 画图标，
    // 浅色/深色主题下都能立刻分辨，且不引入硬编码颜色。
    val tile = if (up) p.income else p.label
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AbRadius.card))
            .background(p.cardBg)
            .padding(AbCardDensity.content),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(AbRadius.chip)).background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (up) AbIcons.ArrowUpRight else AbIcons.ArrowDownLeft,
                contentDescription = null,
                modifier = Modifier.size(AbSize.iconSm),
                tint = p.pageBg,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = AbType.caption1, color = p.secondaryLabel, maxLines = 1)
            Text(
                text = when {
                    hidden -> "••••"
                    !hasValue -> "—"
                    else -> assetMoney(cents)
                },
                style = AbType.amountRow,
                color = p.label,
            )
        }
    }
}

/** 分组标题一行：左侧「资金账户（5）」，右侧当前余额。 */
@Composable
private fun AssetGroupTitle(text: String, trailing: String?) {
    val p = LocalAbPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = AbSpacing.xs, end = AbSpacing.xs, top = AbSpacing.xs, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AbType.sectionHeader, color = p.secondaryLabel, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = AbType.footnote, color = p.tertiaryLabel, maxLines = 1)
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
        ListRow(
            title = account.name,
            subtitle = assetKinds[account.kind]?.let { if (account.archived) "$it · 已归档" else it },
            leading = { AccountAvatar(name = account.name, size = AbCardDensity.settingsIcon) },
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
        if (onAdd != null && isRoot) com.example.mymoney.ui.design.FloatingHeaderAction(AbIcons.Plus, "添加账户", onAdd)
        else if (onAdd != null) TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("添加账户", color = LocalAbPalette.current.accent)
        }
    }
    if (isRoot) PageScaffold(title = title, listState = androidx.compose.foundation.lazy.rememberLazyListState(),
        compact = true, floatingHeader = true, actions = actions, content = content)
    else SubPageScaffold(title = title, onBack = onBack, reserveDock = false, actions = actions, content = content)
}

@Composable
private fun AssetConfirmation(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppDialog(title, onDismiss) {
        Text(message, style = AbType.body, color = LocalAbPalette.current.secondaryLabel)
        PrimaryButton("确认", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
        SecondaryButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AssetFormField(label: String, value: String, placeholder: String = "", onValue: (String) -> Unit) {
    InsetGroup { Column(Modifier.fillMaxWidth().padding(AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        Text(label, style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
        AbTextField(value, onValue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), placeholder = placeholder)
    } }
}

@Composable
private fun AssetAccountForm(original: AssetAccount?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var kind by rememberSaveable(original?.id) { mutableStateOf(original?.kind ?: "PAYMENT") }
    var aliases by rememberSaveable(original?.id) { mutableStateOf(original?.aliases?.let {
        runCatching { (MiniJson.decode(it) as? List<*>)?.filterIsInstance<String>()?.joinToString("，") }.getOrNull()
    }.orEmpty()) }
    var order by rememberSaveable(original?.id) { mutableStateOf((original?.sortOrder ?: 0).toString()) }
    var included by rememberSaveable(original?.id) { mutableStateOf(original?.included ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 已有账户名：预设里已经存在的就不再重复列出来。
    val existingNames = remember { AssetStore.accounts.value.map { it.name }.toSet() }
    ActiveBackHandler { if (!busy) onBack() }
    SubPageScaffold(if (original == null) "添加账户" else "账户设置", onBack = { if (!busy) onBack() }, reserveDock = false) { padding ->
        com.example.mymoney.ui.design.SectionLazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
            item { AssetFormField("账户名称", name, "例如：招商储蓄卡") { name = it } }
            if (original == null) {
                // 预设账户：名字 + 类型一次填好，logo 由名字自动匹配（见 AccountLogos）。
                // 只列当前还没有的，避免重复建同名账户。
                val missing = AccountPresets.all.filterNot { it.name in existingNames }
                if (missing.isNotEmpty()) {
                    item(contentType = com.example.mymoney.ui.design.SectionRole.HEADER) { GroupHeader("常用账户") }
                    item {
                        InsetGroup {
                            Column(Modifier.padding(AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                missing.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        row.forEach { preset ->
                                            CapsuleChip(
                                                text = preset.name,
                                                selected = name == preset.name,
                                                onClick = {
                                                    name = preset.name
                                                    kind = preset.kind
                                                    if (aliases.isBlank()) aliases = preset.aliases
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(contentType = com.example.mymoney.ui.design.SectionRole.HEADER) { GroupHeader("账户类型") }
            items(assetKinds.toList()) { (value, label) -> InsetGroup {
                ListRow(title = label, trailing = { Text(if (kind == value) "✓" else "", color = LocalAbPalette.current.accent) }, onClick = { kind = value })
            } }
            item { AssetFormField("别名（逗号分隔）", aliases, "仅明确填写的别名参与精确匹配") { aliases = it } }
            item { AssetFormField("排序序号", order) { order = it } }
            item { InsetGroup { ListRow(title = "纳入资产总览", trailing = { AbSwitch(included) { included = it } }) } }
            if (error != null) item { InlineMessage(error.orEmpty(), isError = true, announce = true) }
            item(contentType = com.example.mymoney.ui.design.SectionRole.ACTION) { PrimaryButton("保存账户", modifier = Modifier.fillMaxWidth(), busy = busy, onClick = {
                if (!busy) { busy = true; error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            val trimmed = name.trim(); require(trimmed.isNotEmpty()) { "请输入账户名称" }
                            val aliasList = aliases.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct()
                            val sort = order.toIntOrNull() ?: throw IllegalArgumentException("排序序号须为整数")
                            require(sort >= 0) { "排序序号不能为负" }
                            if (original == null) AssetStore.createAccountWithDetails(trimmed, kind, MiniJson.encode(aliasList), included, sortOrder = sort)
                            else {
                                AssetStore.updateAccount(original.copy(name = trimmed, kind = kind, aliases = MiniJson.encode(aliasList), included = included, sortOrder = sort))
                                original.id
                            }
                        } }.onSuccess(onSaved).onFailure { error = it.message ?: "保存失败" }
                        busy = false
                    }
                }
            }) }
        }
    }
}

@Composable
private fun AssetBalanceForm(account: AssetAccount, current: Long, onBack: () -> Unit) {
    val debt = AssetMath.isDebt(account.kind)
    var amount by rememberSaveable(account.id) { mutableStateOf(if (account.initialized) assetNumber(if (debt) -current else current) else "") }
    var reason by rememberSaveable(account.id) { mutableStateOf(if (account.initialized) "" else "设置当前余额") }
    var preview by remember { mutableStateOf<Long?>(null) }
    var previewCurrent by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ActiveBackHandler { if (!busy) onBack() }
    SubPageScaffold(if (account.initialized) "调整余额" else "设置当前余额", onBack = { if (!busy) onBack() }, reserveDock = false) { padding ->
        com.example.mymoney.ui.design.SectionLazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
            item { InsetGroup { ListRow(title = account.name, subtitle = positionLabel(account, current)) } }
            item { AssetFormField(if (debt) "当前欠款（元）" else "当前余额（元）", amount, if (debt) "欠款填正数，溢缴填负数" else "例如 1000.00，透支填负数") { amount = it } }
            item { AssetFormField("原因", reason, "请填写这次调整的原因") { reason = it } }
            if (error != null) item { InlineMessage(error.orEmpty(), isError = true, announce = true) }
            item(contentType = com.example.mymoney.ui.design.SectionRole.ACTION) { PrimaryButton("预览并确认", modifier = Modifier.fillMaxWidth(), busy = busy, onClick = {
                runCatching {
                    require(reason.isNotBlank()) { "请填写原因" }
                    val cents = AssetMath.parseCents(amount)
                    val target = if (debt) Math.negateExact(cents) else cents
                    require(target != Long.MIN_VALUE) { "金额超出范围" }
                    Math.subtractExact(target, current)
                    target
                }.onSuccess { previewCurrent = current; preview = it }.onFailure { error = it.message ?: "金额格式无效" }
            }) }
        }
    }
    preview?.let { target -> AssetConfirmation(if (account.initialized) "确认余额调整" else "确认余额起点",
        "${positionLabel(account.copy(initialized = true), target)}\n登记差额 ${assetMoney(Math.subtractExact(target, previewCurrent))}\n不会计入收入或支出。",
        onDismiss = { preview = null }, onConfirm = {
            preview = null; busy = true
            scope.launch { runCatching { withContext(Dispatchers.IO) { AssetStore.setBalance(account.id, target, reason.trim(), expectedPosition = previewCurrent) } }
                .onSuccess { onBack() }.onFailure { error = it.message ?: "设置失败" }; busy = false }
        }) }
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
    fun requestClose() {
        if (!busy) {
            if (listOf(from.orEmpty(), to.orEmpty(), amount, fee, note, billTime.toString()) != initialFields) discardChanges = true else onBack()
        }
    }
    ActiveBackHandler { requestClose() }
    if (discardChanges) DiscardChangesDialog(onContinue = { discardChanges = false }, onDiscard = { discardChanges = false; onBack() })
    SubPageScaffold(if (original == null) "转账 / 还款" else "修改转账 / 还款", onBack = { requestClose() }, reserveDock = false) { padding ->
        com.example.mymoney.ui.design.SectionLazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
            item(contentType = com.example.mymoney.ui.design.SectionRole.HEADER) { GroupHeader("转出账户") }
            items(eligible, key = { "from-${it.id}" }) { candidate -> InsetGroup {
                ListRow(title = candidate.name, subtitle = positionLabel(candidate, AssetMath.balance(candidate.id, entries)),
                    trailing = { Text(if (from == candidate.id) "✓" else "", color = LocalAbPalette.current.accent) }, onClick = { from = candidate.id })
            } }
            item(contentType = com.example.mymoney.ui.design.SectionRole.HEADER) { GroupHeader("转入 / 还款账户") }
            items(eligible, key = { "to-${it.id}" }) { candidate -> InsetGroup {
                ListRow(title = candidate.name, subtitle = positionLabel(candidate, AssetMath.balance(candidate.id, entries)),
                    trailing = { Text(if (to == candidate.id) "✓" else "", color = LocalAbPalette.current.accent) }, onClick = { to = candidate.id })
            } }
            if (eligible.size < 2) item { InlineMessage("请先为至少两个可用账户设置余额。", isError = true) }
            item { AssetFormField("本金（元）", amount) { amount = it } }
            item { AssetFormField("手续费（元）", fee) { fee = it } }
            item { InsetGroup { ListRow(title = "账单时间", subtitle = date, onClick = if (busy) null else { { dateOpen = true } }) } }
            item { AssetFormField("备注", note) { note = it } }
            if (error != null) item { InlineMessage(error.orEmpty(), isError = true, announce = true) }
            item(contentType = com.example.mymoney.ui.design.SectionRole.ACTION) { PrimaryButton("预览转账", modifier = Modifier.fillMaxWidth(), busy = busy, onClick = {
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
