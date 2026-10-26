package com.example.autobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.autobook.data.CategoryGroup
import com.example.autobook.data.LedgerConfig
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.CapsuleChip
import com.example.autobook.ui.design.CategoryBadge
import com.example.autobook.ui.design.CategoryPresets
import com.example.autobook.ui.design.EmptyState
import com.example.autobook.ui.design.GroupFooter
import com.example.autobook.ui.design.GroupHeader
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InlineMessage
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.InputRow
import com.example.autobook.ui.design.ListRow
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.SubPageScaffold
import com.example.autobook.ui.design.swatchFor
import com.example.billkit.BillType

/**
 * 分类与账户 ——— 设置的第二级页面。
 *
 * 两个层级：一级列表（点进去看这个分类的二级）→ 二级管理。
 * 名字仍然是唯一来源：识别匹配、确认页 chip、CSV 导出都读这里。
 */
@Composable
fun LedgerConfigPage(
    onBack: () -> Unit,
    initialTab: Int = 0,
) {
    val p = LocalAbPalette.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }          // 0 支出 1 收入
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) } // 非空 = 在二级页
    var version by remember { mutableStateOf(0) }               // 强制重读清单
    var newGroup by rememberSaveable { mutableStateOf("") }
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var sorting by rememberSaveable { mutableStateOf(false) }   // 排序模式：行尾出现上移/下移

    val groups = remember(version, tab) {
        if (tab == 0) LedgerConfig.expenseGroups else LedgerConfig.incomeGroups
    }
    val type = if (tab == 0) BillType.EXPENSE else BillType.INCOME

    if (resetConfirm) RestoreLedgerDefaultsDialog(
        onDismiss = { resetConfirm = false },
        errorMessage = resetError,
        onConfirm = {
            resetError = null
            runCatching { LedgerConfig.resetToDefault() }
                .onSuccess { version++; resetConfirm = false }
                .onFailure { resetError = it.message ?: "恢复默认清单失败，请重试" }
        },
    )
    val current = groups.firstOrNull { it.name == openGroup }

    if (current != null) {
        androidx.activity.compose.BackHandler { openGroup = null }
        SubCategoryPage(
            group = current,
            type = type,
            version = version,
            onBack = { openGroup = null },
            onChanged = { version++ },
        )
        return
    }

    SubPageScaffold(title = "分类与账户", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = AbSpacing.m,
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            item(key = "seg") {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = AbSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                ) {
                    CapsuleChip("支出分类", tab == 0, { tab = 0 }, Modifier.weight(1f))
                    CapsuleChip("收入分类", tab == 1, { tab = 1 }, Modifier.weight(1f))
                }
            }

            item(key = "hdr") {
                SortableHeader(if (tab == 0) "支出分类" else "收入分类", sorting) { sorting = !sorting }
            }

            item(key = "groups") {
                InsetGroup {
                    if (groups.isEmpty()) {
                        EmptyState(
                            icon = AbIcons.Tag,
                            title = "还没有分类",
                            message = "在下面添加一个分类名，它必须和 iCost 里的名字一致。",
                        )
                    } else {
                        groups.forEachIndexed { index, group ->
                            GroupRow(
                                group = group,
                                dividerAbove = index > 0,
                                onClick = { if (!sorting) openGroup = group.name },
                                trailing = if (sorting) {
                                    { MoveButtons(group.name, index, groups.size) { from, to -> LedgerConfig.moveGroup(type, from, to); version++ } }
                                } else null,
                            )
                        }
                    }
                }
            }

            if (!sorting) item(key = "add") {
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

            if (!sorting) {
                // 预设：市面上大多数记账 App 都有的那一批名字。只补「当前还没有的」，
                // 点一下就连同二级分类一起建好；之后和手工添加的分类没有任何区别。
                val presets = CategoryPresets.forType(tab == 1)
                val existing = groups.map { it.name }.toSet()
                val missing = presets.filterNot { it.name in existing }
                if (missing.isNotEmpty()) {
                    item(key = "presets-hdr") {
                        GroupHeader("常用预设", Modifier.padding(top = AbSpacing.s))
                    }
                    item(key = "presets") {
                        InsetGroup {
                            Column(Modifier.padding(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                missing.chunked(3).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                        row.forEach { preset ->
                                            CapsuleChip(
                                                text = preset.name,
                                                selected = false,
                                                onClick = {
                                                    runCatching {
                                                        LedgerConfig.addGroup(type, preset.name)
                                                        preset.subs.forEach { LedgerConfig.addSubCategory(type, preset.name, it) }
                                                    }.onSuccess { version++ }
                                                },
                                            )
                                        }
                                    }
                                }
                                Text("点一下就把名字和二级分类建好，之后可以照常改名或删除。",
                                    style = AbType.caption1, color = p.secondaryLabel)
                            }
                        }
                    }
                }
            }

            item(key = "accounts-hdr") { SortableHeader("账户", sorting) { sorting = !sorting } }

            item(key = "accounts") {
                if (sorting) SortableAccounts(version = version, onChanged = { version++ })
                else AccountsSection(version = version, onChanged = { version++ })
            }

            if (!sorting) item(key = "reset") {
                InsetGroup {
                    ListRow(
                        title = "恢复默认清单",
                        destructive = true,
                        subtitle = "替换支出、收入分类和账户；保留账单、历史与备注模板",
                        onClick = { resetError = null; resetConfirm = true },
                    )
                }
            }

            item(key = "footer") {
                GroupFooter(
                    if (sorting) "确认卡按这里的顺序排列，常用的放前面。调整顺序不会改动已记账的旧账单，备份恢复后顺序不变。"
                    else "名字要和 iCost 里已有的完全一致，否则导入后会归到「未分类」。点右上「排序」调整确认卡里的先后。"
                )
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
        leading = { CategoryBadge(category = group.name, tint = tint, size = 32.dp) },
        onClick = onClick,
    )
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
    var sorting by rememberSaveable { mutableStateOf(false) }

    // 父级可能已被删掉，重新读一次保证 subs 是最新的
    val fresh = remember(group.name, version) {
        (if (type == BillType.EXPENSE) LedgerConfig.expenseGroups else LedgerConfig.incomeGroups)
            .firstOrNull { it.name == group.name } ?: group
    }

    SubPageScaffold(title = fresh.name, onBack = onBack) { padding ->
        LazyColumn(
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
                if (fresh.subs.size > 1) SortableHeader("二级分类", sorting) { sorting = !sorting }
                else GroupHeader("二级分类")
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
                        fresh.subs.forEachIndexed { index, sub ->
                            if (sorting && fresh.subs.size > 1) {
                                ListRow(
                                    title = sub,
                                    dividerAbove = index > 0,
                                    trailing = {
                                        MoveButtons(sub, index, fresh.subs.size) { from, to ->
                                            LedgerConfig.moveSub(type, fresh.name, from, to); onChanged()
                                        }
                                    },
                                )
                            } else ListRow(
                                title = sub,
                                dividerAbove = index > 0,
                                trailing = {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(100.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text("删除", style = AbType.footnote, color = p.red)
                                    }
                                },
                                onClick = {
                                    LedgerConfig.removeSubCategory(type, fresh.name, sub)
                                    onChanged()
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

            item(key = "footer") {
                GroupFooter("删除一级分类不会改动已记账的旧账单，只是以后不再建议这个名字。")
            }
        }
    }
}

/** 账户清单：CapsuleChip 网格 + 行内输入。 */
@Composable
private fun AccountsSection(version: Int, onChanged: () -> Unit) {
    val p = LocalAbPalette.current
    var draft by rememberSaveable { mutableStateOf("") }
    var accountError by remember { mutableStateOf<String?>(null) }
    val accounts = remember(version) { LedgerConfig.accounts }

    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        InsetGroup {
            Column(Modifier.padding(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                if (accounts.isEmpty()) {
                    Text("还没有账户", style = AbType.footnote, color = p.secondaryLabel)
                } else {
                    accounts.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                            row.forEach { name ->
                                CapsuleChip(
                                    text = "$name  ×",
                                    selected = false,
                                    onClick = {
                                        accountError = null
                                        runCatching { LedgerConfig.removeAccount(name) }
                                            .onSuccess { onChanged() }
                                            .onFailure { accountError = it.message ?: "移除账户失败，请重试" }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            InsetDivider()
            InputRow(
                label = "添加",
                value = draft,
                onValueChange = { draft = it; accountError = null },
                placeholder = "账户名（如 微信零钱）",
                onDone = {
                    accountError = null
                    runCatching {
                        require(draft.isNotBlank()) { "请输入账户名称" }
                        LedgerConfig.addAccount(draft)
                    }.onSuccess { draft = ""; onChanged() }
                        .onFailure { accountError = it.message ?: "添加账户失败，请重试" }
                },
            )
        }
        if (accountError != null) InlineMessage(accountError.orEmpty(), isError = true, announce = true)
        GroupFooter("账户名同样要与 iCost 一致；识别时会按支付方式自动匹配。移除即归档，余额与历史保留；同名归档账户请在资产页恢复。")
    }
}

/** 分组标题 + 右侧「排序 / 完成」切换。 */
@Composable
private fun SortableHeader(title: String, sorting: Boolean, onToggle: () -> Unit) {
    val p = LocalAbPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupHeader(title, Modifier.weight(1f))
        Text(
            if (sorting) "完成" else "排序",
            style = AbType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = p.accent,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(100.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
                .padding(horizontal = AbSpacing.m, vertical = 14.dp)
                .semantics { role = Role.Button; contentDescription = if (sorting) "完成排序" else "调整${title}顺序" },
        )
    }
}

/**
 * 上移 / 下移按钮（48dp）。用按钮而不是拖拽：TalkBack、单手、发抖的手都能精确操作，
 * 首项不能上移、末项不能下移。
 */
@Composable
private fun MoveButtons(name: String, index: Int, size: Int, onMove: (Int, Int) -> Unit) {
    val haptic = com.example.autobook.ui.design.rememberAbHaptic()
    Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
        MoveButton("上移", "把$name 上移", AbIcons.ChevronUp, enabled = index > 0) { haptic.tick(); onMove(index, index - 1) }
        MoveButton("下移", "把$name 下移", AbIcons.ChevronDown, enabled = index < size - 1) { haptic.tick(); onMove(index, index + 1) }
    }
}

@Composable
private fun MoveButton(label: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) p.fill else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = null, Modifier.size(18.dp),
                tint = if (enabled) p.label else p.tertiaryLabel.copy(alpha = 0.4f))
        }
    }
}

/** 排序模式下的账户：改成一列可移动的行，网格里没法表达先后。 */
@Composable
private fun SortableAccounts(version: Int, onChanged: () -> Unit) {
    val accounts = remember(version) { LedgerConfig.accounts }
    var accountError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        InsetGroup {
            accounts.forEachIndexed { index, name ->
                ListRow(title = name, dividerAbove = index > 0,
                    trailing = { MoveButtons(name, index, accounts.size) { from, to ->
                        accountError = null
                        runCatching { LedgerConfig.moveAccount(from, to) }
                            .onSuccess { onChanged() }
                            .onFailure { accountError = it.message ?: "账户排序失败，请重试" }
                    } },
                )
            }
        }
        if (accountError != null) InlineMessage(accountError.orEmpty(), isError = true, announce = true)
    }
}

/** All UI entry points explain the same bounded operation before any write. */
@Composable
internal fun RestoreLedgerDefaultsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, errorMessage: String? = null) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复默认清单？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            Text("将替换全部支出分类、收入分类和账户候选清单；同名归档账户会恢复为候选。账户余额、资产流水、账单、识别历史、备注模板、识别设置和权限均保留。")
            if (errorMessage != null) InlineMessage(errorMessage, isError = true, announce = true)
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("恢复默认清单") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
