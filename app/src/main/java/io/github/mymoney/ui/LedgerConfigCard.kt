package io.github.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billkit.BillType
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.CategoryGroup
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbRadius
import io.github.mymoney.ui.design.AbSize
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AppDialog
import io.github.mymoney.ui.design.AppDialogActions
import io.github.mymoney.ui.design.CategoryBadge
import io.github.mymoney.ui.design.CategoryIcons
import io.github.mymoney.ui.design.CategoryPreset
import io.github.mymoney.ui.design.CategoryPresets
import io.github.mymoney.ui.design.DragReorderColumn
import io.github.mymoney.ui.design.EmptyState
import io.github.mymoney.ui.design.GroupFooter
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InlineMessage
import io.github.mymoney.ui.design.InputRow
import io.github.mymoney.ui.design.InsetDivider
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.SubPageScaffold
import io.github.mymoney.ui.design.rememberAbHaptic
import io.github.mymoney.ui.design.swatchFor

/**
 * 分类管理。账户候选清单由资产页单独管理。
 *
 * 两个层级：一级列表（点进去看这个分类的二级）→ 二级管理。
 * 名字仍然是唯一来源：识别匹配、确认页 chip、CSV 导出都读这里。
 */
@Composable
fun LedgerConfigPage(
    onBack: () -> Unit,
    initialTab: Int = 0,
    fixedTab: Int? = null,
    presetsOnly: Boolean = false,
) {
    val p = LocalAbPalette.current
    var tab by rememberSaveable(initialTab, fixedTab, presetsOnly) { mutableStateOf((fixedTab ?: initialTab).coerceIn(0, 1)) }          // 0 支出 1 收入
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) } // 非空 = 在二级页
    var version by remember { mutableStateOf(0) }               // 强制重读清单
    var newGroup by rememberSaveable { mutableStateOf("") }
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var showPresets by rememberSaveable { mutableStateOf(false) } // 非空 = 在「常用预设」二级页

    val groups = remember(version, tab) {
        if (tab == 0) LedgerConfig.expenseGroups else LedgerConfig.incomeGroups
    }
    val type = if (tab == 0) BillType.EXPENSE else BillType.INCOME

    if (resetConfirm) RestoreCategoriesDefaultsDialog(
        onDismiss = { resetConfirm = false },
        errorMessage = resetError,
        onConfirm = {
            resetError = null
            runCatching { LedgerConfig.resetCategoriesToDefault() }
                .onSuccess { version++; resetConfirm = false }
                .onFailure { resetError = it.message ?: "恢复默认清单失败，请重试" }
        },
    )
    val current = groups.firstOrNull { it.name == openGroup }

    if (showPresets) {
        ActiveBackHandler { showPresets = false }
        PresetPickerPage(
            type = type,
            existing = groups.map { it.name }.toSet(),
            onBack = { showPresets = false },
            onChanged = { version++ },
        )
        return
    }

    if (current != null && !presetsOnly) {
        ActiveBackHandler { openGroup = null }
        SubCategoryPage(
            group = current,
            type = type,
            version = version,
            onBack = { openGroup = null },
            onChanged = { version++ },
        )
        return
    }

    val title = when {
        presetsOnly -> "分类预设"
        fixedTab == 0 -> "支出分类"
        fixedTab == 1 -> "收入分类"
        else -> "分类"
    }
    SubPageScaffold(title = title, onBack = onBack) { padding ->
        io.github.mymoney.ui.design.SectionLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = AbSpacing.m,
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            if (fixedTab == null || presetsOnly) item(key = "seg") {
                io.github.mymoney.ui.design.Segmented(
                    options = listOf("支出分类", "收入分类"), selected = tab,
                    onSelect = { tab = it }, modifier = Modifier.fillMaxWidth().padding(bottom = AbSpacing.s),
                )
            }

            if (!presetsOnly) {
                item(key = "hdr") {
                    GroupHeader(if (tab == 0) "支出分类" else "收入分类")
                    if (groups.size > 1) {
                        Text(
                            "长按拖动排序",
                            style = AbType.caption1,
                            color = p.secondaryLabel,
                            modifier = Modifier.padding(start = AbSpacing.page, bottom = AbSpacing.xs),
                        )
                    }
                }

                item(key = "groups") {
                    InsetGroup {
                        if (groups.isEmpty()) {
                            EmptyState(
                                icon = AbIcons.Tag,
                                title = "还没有分类",
                                message = "在下面添加分类名称，用于本地账单归类与统计。",
                            )
                        } else {
                DragReorderColumn(
                    items = groups,
                    keyOf = { it.name },
                    onReorder = { next ->
                        runCatching {
                            LedgerConfig.reorderGroups(type, next.map { it.name })
                        }.onSuccess { version++ }
                    },
                ) { group, index, dragging ->
                                GroupRow(
                                    group = group,
                                    dividerAbove = index > 0 && !dragging,
                                    onClick = { openGroup = group.name },
                                )
                            }
                        }
                    }
                }

                item(key = "add") {
                    InsetGroup {
                        InputRow(
                            label = "添加",
                            value = newGroup,
                            onValueChange = { newGroup = it },
                            placeholder = "新分类名",
                            onDone = {
                                LedgerConfig.addGroup(type, newGroup)
                                newGroup = ""
                                version++
                            },
                        )
                    }
                }

            }

            if (presetsOnly || fixedTab == null) {
                // 预设：市面上大多数记账 App 都有的那一批名字。只补「当前还没有的」，
                // 点一下就连同二级分类一起建好；之后和手工添加的分类没有任何区别。
                val presets = CategoryPresets.forType(tab == 1)
                val existing = groups.map { it.name }.toSet()
                val missing = presets.filterNot { it.name in existing }
                if (missing.isNotEmpty()) {
                    item(key = "presets-entry") {
                        InsetGroup {
                            ListRow(
                                title = "常用预设",
                                subtitle = "一键添加缺少的 ${missing.size} 个分类",
                                leading = { CategoryBadge(category = "常用预设", tint = swatchFor("常用预设"), size = AbCardDensity.settingsIcon) },
                                chevron = true,
                                onClick = { showPresets = true },
                            )
                        }
                    }
                } else if (presetsOnly) {
                    item(key = "presets-complete") {
                        InsetGroup { ListRow(title = "常用预设已齐全", subtitle = "现有分类已包含全部常用预设") }
                    }
                }
            }

            if (presetsOnly) item(key = "meal-presets") {
                InsetGroup {
                    ListRow(title = "补齐餐饮子类", subtitle = "为支出餐饮添加缺少的早餐、午餐、晚餐、夜宵",
                        onClick = {
                            resetError = null
                            runCatching { LedgerConfig.addMealSubCategories() }
                                .onSuccess { version++ }
                                .onFailure { resetError = it.message ?: "补齐餐饮子类失败，请重试" }
                        })
                }
                if (resetError != null && !resetConfirm) InlineMessage(resetError.orEmpty(), isError = true, announce = true)
            }

            if (presetsOnly || fixedTab == null) item(key = "reset") {
                InsetGroup {
                    ListRow(
                        title = "恢复默认分类",
                        destructive = true,
                        subtitle = "替换支出与收入分类，保留账户和历史账单",
                        onClick = { resetError = null; resetConfirm = true },
                    )
                }
            }

        }
    }
}

@Composable
private fun GroupRow(group: CategoryGroup, dividerAbove: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalAbPalette.current
    val tint = swatchFor(group.name)
    ListRow(
        title = group.name,
        subtitle = if (group.subs.isEmpty()) "无二级分类" else group.subs.joinToString(" / "),
        dividerAbove = dividerAbove,
        chevron = trailing == null,
        trailing = trailing,
        leading = { CategoryBadge(category = group.name, tint = tint, size = AbCardDensity.settingsIcon) },
        onClick = onClick,
    )
}

/** 「常用预设」二级页：一个卡组，行内统一单色图标 + 右侧圆形加号，点一下连同二级分类一起建好。 */
@Composable
private fun PresetPickerPage(
    type: BillType,
    existing: Set<String>,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val p = LocalAbPalette.current
    val presets = remember(existing) {
        CategoryPresets.forType(type == BillType.INCOME).filterNot { it.name in existing }
    }
    SubPageScaffold(title = "常用预设", onBack = onBack) { padding ->
        io.github.mymoney.ui.design.SectionLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page, end = AbSpacing.page,
                top = AbSpacing.m, bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.l),
        ) {
            if (presets.isEmpty()) {
                item(key = "empty") {
                    InsetGroup { ListRow(title = "常用预设已齐全", subtitle = "现有分类已包含全部常用预设") }
                }
            } else {
                item(key = "hint") { GroupHeader("可补 ${presets.size} 个分类") }
                item(key = "list") {
                    InsetGroup {
                        presets.forEachIndexed { index, preset ->
                            PresetRow(
                                preset = preset,
                                dividerAbove = index > 0,
                                onClick = {
                                    runCatching {
                                        LedgerConfig.addGroup(type, preset.name)
                                        preset.subs.forEach { LedgerConfig.addSubCategory(type, preset.name, it) }
                                    }.onSuccess { onChanged() }
                                },
                            )
                        }
                    }
                }
                item(key = "foot") { GroupFooter("点右侧加号，把这个分类和它的二级分类一起加入账本；加入后仍可改名、删改。") }
            }
        }
    }
}

/** 预设行：原样彩色实心图标徽章 + 名称/二级 + 右侧圆形加号。 */
@Composable
private fun PresetRow(
    preset: CategoryPreset,
    dividerAbove: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) InsetDivider(inset = AbCardDensity.leadingInset)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AbSize.minTouch)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { haptic.select(); onClick() }
                .padding(horizontal = AbSpacing.l, vertical = AbSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            CategoryBadge(
                category = preset.name,
                tint = p.accent,
                size = AbCardDensity.settingsIcon,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(preset.name, style = AbType.body, color = p.label)
                if (preset.subs.isNotEmpty()) {
                    Text(
                        preset.subs.joinToString(" / "),
                        style = AbType.footnote,
                        color = p.secondaryLabel,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(AbRadius.capsule))
                    .background(p.accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = AbIcons.Plus,
                    contentDescription = "添加",
                    modifier = Modifier.size(18.dp),
                    tint = p.accent,
                )
            }
        }
    }
}

/** 二级分类管理：增删 + 删除整个一级分类。 */
@Composable
private fun SubCategoryPage(
    group: CategoryGroup,
    type: BillType,
    version: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val p = LocalAbPalette.current
    var draft by rememberSaveable { mutableStateOf("") }

    // 父级可能已被删掉，重新读一次保证 subs 是最新的
    val fresh = remember(group.name, version) {
        (if (type == BillType.EXPENSE) LedgerConfig.expenseGroups else LedgerConfig.incomeGroups)
            .firstOrNull { it.name == group.name } ?: group
    }

    SubPageScaffold(title = fresh.name, onBack = onBack) { padding ->
        io.github.mymoney.ui.design.SectionLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = AbSpacing.m,
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            item(key = "hdr") {
                GroupHeader("二级分类")
                if (fresh.subs.size > 1) {
                    Text(
                        "长按拖动排序",
                        style = AbType.caption1,
                        color = p.secondaryLabel,
                        modifier = Modifier.padding(start = AbSpacing.page, bottom = AbSpacing.xs),
                    )
                }
            }

            item(key = "subs") {
                InsetGroup {
                    if (fresh.subs.isEmpty()) {
                        EmptyState(
                            icon = AbIcons.Tag,
                            title = "还没有二级分类",
                            message = "可选。留空也可以正常记账和导出。",
                        )
                    } else {
                        DragReorderColumn(
                            items = fresh.subs,
                            keyOf = { it },
                            onReorder = { next ->
                                runCatching {
                                    LedgerConfig.reorderSubs(type, fresh.name, next)
                                }.onSuccess { onChanged() }
                            },
                        ) { sub, index, dragging ->
                            ListRow(
                                title = sub,
                                dividerAbove = index > 0 && !dragging,
                                trailing = {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(100.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) {
                                                LedgerConfig.removeSubCategory(type, fresh.name, sub)
                                                onChanged()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text("删除", style = AbType.footnote, color = p.red)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item(key = "add") {
                InsetGroup {
                    InputRow(
                        label = "添加",
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "新二级分类",
                        onDone = {
                            LedgerConfig.addSubCategory(type, fresh.name, draft)
                            draft = ""
                            onChanged()
                        },
                    )
                }
            }

            item(key = "danger") {
                InsetGroup {
                    ListRow(
                        title = "删除「${fresh.name}」",
                        destructive = true,
                        onClick = {
                            LedgerConfig.removeGroup(type, fresh.name)
                            onChanged()
                            onBack()
                        },
                    )
                }
            }

        }
    }
}

/** 账户清单：点选进入详情；长按拖动调整确认卡顺序。 */
@Composable
private fun AccountsSection(
    version: Int,
    assetAccounts: List<io.github.mymoney.data.AssetAccount>,
    onChanged: () -> Unit,
    onAdd: () -> Unit,
    onOpenAccount: (String) -> Unit,
) {
    val p = LocalAbPalette.current
    var accountError by remember { mutableStateOf<String?>(null) }
    val ordered = remember(version, assetAccounts) { assetAccounts.filter { !it.archived } }

    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        InsetGroup {
            ListRow(
                title = "添加账户",
                subtitle = "按类型从预设开通，保存即已设置",
                chevron = true,
                onClick = onAdd,
            )
        }
        InsetGroup {
            if (ordered.isEmpty()) {
                ListRow(title = "还没有账户", subtitle = "点上方添加账户")
            } else {
                DragReorderColumn(
                    items = ordered,
                    keyOf = { it.id },
                    onReorder = { next ->
                        accountError = null
                        runCatching {
                            AssetStore.reorderActive(next.map { it.id })
                        }.onSuccess { onChanged() }
                            .onFailure { accountError = it.message ?: "账户排序失败，请重试" }
                    },
                ) { row, index, dragging ->
                    ListRow(
                        title = row.name,
                        subtitle = if (!row.initialized) "待设置余额" else null,
                        dividerAbove = index > 0 && !dragging,
                        leading = {
                            io.github.mymoney.ui.design.AccountAvatar(
                                name = row.name,
                                size = AbCardDensity.settingsIcon,
                            )
                        },
                        trailing = {
                            androidx.compose.material3.Icon(
                                imageVector = AbIcons.Close,
                                contentDescription = "从清单移除",
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        accountError = null
                                        runCatching { LedgerConfig.removeAccount(row.name) }
                                            .onSuccess { onChanged() }
                                            .onFailure { accountError = it.message ?: "移除账户失败，请重试" }
                                    },
                                tint = p.secondaryLabel,
                            )
                        },
                        onClick = { onOpenAccount(row.id) },
                    )
                }
            }
        }
        if (accountError != null) InlineMessage(accountError.orEmpty(), isError = true, announce = true)
    }
}

/** All UI entry points explain the same bounded operation before any write. */
@Composable
internal fun RestoreLedgerDefaultsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, errorMessage: String? = null) {
    AppDialog(title = "恢复默认清单？", onDismiss = onDismiss) {
        Text("将替换全部支出分类、收入分类和账户候选清单；同名归档账户会恢复为候选。账户余额、资产流水、账单、记录历史、备注模板、识别设置和权限均保留。")
        if (errorMessage != null) InlineMessage(errorMessage, isError = true, announce = true)
        AppDialogActions(confirmLabel = "恢复默认清单", onConfirm = onConfirm, onDismiss = onDismiss)
    }
}


/** 资产 → 账户管理：与确认卡共用同一份账户候选清单。 */
@Composable
internal fun AccountCandidatesPage(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenAccount: (String) -> Unit,
) {
    val accounts by io.github.mymoney.data.AssetStore.accounts.collectAsStateWithLifecycle()
    val ready by io.github.mymoney.data.AssetStore.ready.collectAsStateWithLifecycle()
    val storeError by io.github.mymoney.data.AssetStore.error.collectAsStateWithLifecycle()
    var version by remember { mutableStateOf(0) }
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    // Store emissions also refresh the candidate projection when this page stays on screen.
    val listVersion = version + accounts.hashCode()
    SubPageScaffold(title = "账户管理", onBack = onBack) { padding ->
        io.github.mymoney.ui.design.SectionLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            if (storeError != null) item { InlineMessage(storeError.orEmpty(), isError = true, announce = true) }
            if (ready) {
                item {
                    Text(
                        "添加请用预设向导；清单内长按拖动排序",
                        style = AbType.footnote,
                        color = LocalAbPalette.current.secondaryLabel,
                    )
                }
                item { GroupHeader("确认卡账户清单") }
                item {
                    AccountsSection(
                        version = listVersion,
                        assetAccounts = accounts,
                        onChanged = { version++ },
                        onAdd = onAdd,
                        onOpenAccount = onOpenAccount,
                    )
                }
                item {
                    InsetGroup {
                        ListRow(title = "恢复默认账户清单", destructive = true,
                            subtitle = "恢复为仅「钱包」，保留账户余额与流水",
                            onClick = { resetError = null; resetConfirm = true })
                    }
                }
            }
        }
    }
    if (resetConfirm) AppDialog(title = "恢复默认账户清单？", onDismiss = { resetConfirm = false }) {
        Text("将恢复默认账户候选为仅「钱包」；其他账户归档，同名已归档默认账户会恢复。全部账户余额、流水、账单和分类保留。")
        if (resetError != null) InlineMessage(resetError.orEmpty(), isError = true, announce = true)
        AppDialogActions(
            confirmLabel = "恢复默认账户",
            onConfirm = {
                resetError = null
                runCatching { LedgerConfig.resetAccountsToDefault() }
                    .onSuccess { version++; resetConfirm = false }
                    .onFailure { resetError = it.message ?: "恢复默认账户清单失败，请重试" }
            },
            onDismiss = { resetConfirm = false },
        )
    }
}

@Composable
private fun RestoreCategoriesDefaultsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, errorMessage: String?) {
    AppDialog(title = "恢复默认分类？", onDismiss = onDismiss) {
        Text("将替换全部支出分类、收入分类和二级分类。账户清单、账户余额、资产流水、账单、记录历史、备注模板和识别设置保留。")
        if (errorMessage != null) InlineMessage(errorMessage, isError = true, announce = true)
        AppDialogActions(confirmLabel = "恢复默认分类", onConfirm = onConfirm, onDismiss = onDismiss)
    }
}