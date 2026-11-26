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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbTextField
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AccountAvatar
import io.github.mymoney.ui.design.AppAlertDialog
import io.github.mymoney.ui.design.AppDialog
import io.github.mymoney.ui.design.AppDialogActions
import io.github.mymoney.ui.design.CategoryIcons
import io.github.mymoney.ui.design.ColorSwatchStrip
import io.github.mymoney.ui.design.DragReorderColumn
import io.github.mymoney.ui.design.EmptyState
import io.github.mymoney.ui.design.FloatingHeaderAction
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.GroupedIconGrid
import io.github.mymoney.ui.design.InlineMessage
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalLiquidGlassEnabled
import io.github.mymoney.ui.design.LocalReduceTransparency
import io.github.mymoney.ui.design.SectionLazyColumn
import io.github.mymoney.ui.design.SlideSubPageValue
import io.github.mymoney.ui.design.SubPageScaffold
import io.github.mymoney.ui.design.abPressFeedback
import io.github.mymoney.ui.design.badgeLiquidGlaze
import io.github.mymoney.ui.design.parseColorHex
import io.github.mymoney.ui.design.solidBadgeBackground
import io.github.mymoney.ui.design.swatchFor
import kotlinx.coroutines.launch

/**
 * 分类管理：一级列表（右上角 `+` 添加、长按拖动排序、点进二级）→ 二级管理（右上角 `+` 添加、点按改名/删除）。
 */
@Composable
fun LedgerConfigPage(
    onBack: () -> Unit,
    initialTab: Int = 0,
    fixedTab: Int? = null,
) {
    var tab by rememberSaveable(initialTab, fixedTab) { mutableStateOf((fixedTab ?: initialTab).coerceIn(0, 1)) } // 0 支出 1 收入
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    var addDialogOpen by rememberSaveable { mutableStateOf(false) }
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }

    val pagePixels = RememberBackPixels()
    val navScope = rememberCoroutineScope()
    var subPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var openingSub by remember { mutableStateOf(false) }

    val groups = remember(version, tab) {
        if (tab == 0) LedgerConfig.expenseGroups else LedgerConfig.incomeGroups
    }
    val type = if (tab == 0) BillType.EXPENSE else BillType.INCOME
    val current = groups.firstOrNull { it.name == openGroup }

    fun openSub(groupName: String) {
        if (openingSub || openGroup != null) return
        openingSub = true
        navScope.launch {
            try {
                subPreview = pagePixels.snapshot()
                openGroup = groupName
            } finally {
                openingSub = false
            }
        }
    }

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

    val title = when (fixedTab) {
        0 -> "支出分类"
        1 -> "收入分类"
        else -> "分类"
    }

    Box(Modifier.fillMaxSize()) {
        BackSurface(enabled = false, onBack = {}, pixels = pagePixels) {
            SubPageScaffold(
                title = title,
                onBack = onBack,
                actions = {
                    FloatingHeaderAction(AbIcons.Plus, "添加分类") { addDialogOpen = true }
                },
            ) { padding ->
                SectionLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AbSpacing.page,
                        end = AbSpacing.page,
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
                ) {
                    if (fixedTab == null) item(key = "seg") {
                        io.github.mymoney.ui.design.Segmented(
                            options = listOf("支出分类", "收入分类"),
                            selected = tab,
                            onSelect = { tab = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = AbSpacing.s),
                        )
                    }

                    item(key = "groups") {
                        InsetGroup {
                            if (groups.isEmpty()) {
                                EmptyState(
                                    icon = AbIcons.Tag,
                                    title = "暂无分类",
                                    message = "点击右上角「+」添加新分类。",
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
                                ) { group, _, _ ->
                                    GroupRow(
                                        group = group,
                                        dividerAbove = false,
                                        onClick = { openSub(group.name) },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "reset") {
                        InsetGroup {
                            ListRow(
                                title = "恢复默认分类",
                                destructive = true,
                                onClick = { resetError = null; resetConfirm = true },
                            )
                        }
                    }
                }
            }
        }

        SlideSubPageValue(value = current, modifier = Modifier.fillMaxSize()) { targetGroup ->
            BackSurface(
                enabled = current != null,
                onBack = { openGroup = null; subPreview = null },
                routeKey = targetGroup.name,
                preview = subPreview,
            ) {
                SubCategoryPage(
                    group = targetGroup,
                    type = type,
                    version = version,
                    onBack = { openGroup = null; subPreview = null },
                    onRenamed = { nextName -> openGroup = nextName; version++ },
                    onChanged = { version++ },
                )
            }
        }

        if (addDialogOpen) {
            CategoryEditorDialog(
                title = "添加${if (tab == 0) "支出" else "收入"}分类",
                initialName = "",
                initialIconKey = null,
                initialColorHex = null,
                existingNames = groups.map { it.name }.toSet(),
                onDismiss = { addDialogOpen = false },
                onSave = { name, iconKey, colorHex ->
                    LedgerConfig.addGroup(type, name, iconKey, colorHex)
                    version++
                    addDialogOpen = false
                },
            )
        }
    }
}

@Composable
private fun GroupRow(group: CategoryGroup, dividerAbove: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val tint = parseColorHex(group.colorHex) ?: swatchFor(group.name)
    ListRow(
        title = group.name,
        subtitle = if (group.subs.isEmpty()) "无二级分类" else group.subs.joinToString(" / "),
        dividerAbove = dividerAbove,
        chevron = trailing == null,
        trailing = trailing,
        leading = {
            CategoryBadgeWithIcon(
                name = group.name,
                iconKey = group.iconKey,
                tint = tint,
                size = AbCardDensity.settingsIcon,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun CategoryBadgeWithIcon(
    name: String,
    iconKey: String?,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val resolvedIcon = iconKey ?: CategoryIcons.keyFor(name)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(solidBadgeBackground(tint))
            .badgeLiquidGlaze(effectiveGlass),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.vectorFor(resolvedIcon),
            contentDescription = null,
            modifier = Modifier.size(size * 0.54f),
            tint = Color.White,
        )
    }
}

/** 二级分类管理：简洁三卡（主分类编辑、二级分类列表、删除主分类），右上角「+」添加二级分类。 */
@Composable
private fun SubCategoryPage(
    group: CategoryGroup,
    type: BillType,
    version: Int,
    onBack: () -> Unit,
    onRenamed: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val scope = rememberCoroutineScope()
    var addSubOpen by rememberSaveable { mutableStateOf(false) }
    var editGroupOpen by remember { mutableStateOf(false) }
    var editSubTarget by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val fresh = remember(group.name, version) {
        (if (type == BillType.EXPENSE) LedgerConfig.expenseGroups else LedgerConfig.incomeGroups)
            .firstOrNull { it.name == group.name } ?: group
    }
    val allGroupNames = remember(version, type) {
        LedgerConfig.groupsFor(type).map { it.name }.toSet()
    }

    SubPageScaffold(
        title = fresh.name,
        onBack = onBack,
        actions = {
            FloatingHeaderAction(AbIcons.Plus, "添加二级分类") { addSubOpen = true }
        },
    ) { padding ->
        SectionLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
        ) {
            item(key = "style") {
                InsetGroup {
                    ListRow(
                        title = fresh.name,
                        subtitle = "编辑名称、图标与配色",
                        leading = {
                            CategoryBadgeWithIcon(
                                name = fresh.name,
                                iconKey = fresh.iconKey,
                                tint = parseColorHex(fresh.colorHex) ?: swatchFor(fresh.name),
                                size = AbCardDensity.settingsIcon,
                            )
                        },
                        chevron = true,
                        onClick = { editGroupOpen = true },
                    )
                }
            }

            if (statusMessage != null) {
                item(key = "status") { InlineMessage(statusMessage.orEmpty(), announce = true) }
            }

            item(key = "subs") {
                InsetGroup {
                    if (fresh.subs.isEmpty()) {
                        EmptyState(
                            icon = AbIcons.Tag,
                            title = "暂无二级分类",
                            message = "点击右上角「+」添加。",
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
                        ) { sub, _, _ ->
                            ListRow(
                                title = sub,
                                dividerAbove = false,
                                chevron = true,
                                onClick = { editSubTarget = sub },
                            )
                        }
                    }
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

    if (addSubOpen) {
        var subDraft by remember { mutableStateOf("") }
        var subError by remember { mutableStateOf<String?>(null) }
        AppDialog(title = "添加二级分类", onDismiss = { addSubOpen = false }) {
            InsetGroup {
                Box(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s)) {
                    AbTextField(
                        value = subDraft,
                        onValueChange = { subDraft = it; subError = null },
                        placeholder = "输入二级分类名称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (subError != null) InlineMessage(subError.orEmpty(), isError = true, announce = true)
            AppDialogActions(
                confirmLabel = "添加",
                onDismiss = { addSubOpen = false },
                onConfirm = {
                    val trimmed = subDraft.trim()
                    when {
                        trimmed.isEmpty() -> subError = "请输入二级分类名称"
                        trimmed in fresh.subs -> subError = "已存在同名二级分类"
                        else -> {
                            LedgerConfig.addSubCategory(type, fresh.name, trimmed)
                            addSubOpen = false
                            onChanged()
                        }
                    }
                },
            )
        }
    }

    if (editGroupOpen) {
        var billCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(fresh.name) {
            billCount = LedgerConfig.countBillsForCategory(context, type, fresh.name)
        }
        CategoryEditorDialog(
            title = "编辑「${fresh.name}」",
            initialName = fresh.name,
            initialIconKey = fresh.iconKey,
            initialColorHex = fresh.colorHex,
            existingNames = allGroupNames - fresh.name,
            renameHistoryNotice = { next ->
                val count = billCount ?: 0
                if (next.trim() != fresh.name && count > 0) {
                    "保存后将同步把 $count 笔历史账单中的「${fresh.name}」更新为「${next.trim()}」"
                } else null
            },
            onDismiss = { editGroupOpen = false },
            onSaveAsync = { nextName, nextIcon, nextColor ->
                val synced = LedgerConfig.renameGroup(context, type, fresh.name, nextName, nextIcon, nextColor)
                editGroupOpen = false
                if (synced > 0) statusMessage = "已同步更新 $synced 笔历史账单"
                if (nextName.trim() != fresh.name) onRenamed(nextName.trim()) else onChanged()
            },
        )
    }

    editSubTarget?.let { oldSub ->
        var subDraft by remember(oldSub) { mutableStateOf(oldSub) }
        var subCount by remember(oldSub) { mutableStateOf(0) }
        var subError by remember(oldSub) { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(oldSub) {
            subCount = LedgerConfig.countBillsForSubCategory(context, type, fresh.name, oldSub)
        }
        AppDialog(title = "编辑二级分类", onDismiss = { if (!busy) editSubTarget = null }, dismissible = !busy) {
            InsetGroup {
                Box(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s)) {
                    AbTextField(
                        value = subDraft,
                        onValueChange = { subDraft = it; subError = null },
                        placeholder = "二级分类名称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (subDraft.trim() != oldSub && subCount > 0) {
                Text(
                    "保存后将同步更新 $subCount 笔历史账单中的二级分类",
                    style = AbType.caption1,
                    color = p.secondaryLabel,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                AbTextButton(
                    text = "删除此二级分类",
                    color = p.red,
                    enabled = !busy,
                    onClick = {
                        LedgerConfig.removeSubCategory(type, fresh.name, oldSub)
                        editSubTarget = null
                        onChanged()
                    },
                )
            }
            if (subError != null) InlineMessage(subError.orEmpty(), isError = true, announce = true)
            AppDialogActions(
                confirmLabel = if (busy) "保存中…" else "保存",
                confirmEnabled = !busy,
                onDismiss = { if (!busy) editSubTarget = null },
                onConfirm = {
                    val trimmed = subDraft.trim()
                    if (trimmed.isEmpty()) {
                        subError = "请输入二级分类名称"
                        return@AppDialogActions
                    }
                    if (trimmed != oldSub && trimmed in fresh.subs) {
                        subError = "已存在同名二级分类"
                        return@AppDialogActions
                    }
                    busy = true
                    scope.launch {
                        runCatching { LedgerConfig.renameSubCategory(context, type, fresh.name, oldSub, trimmed) }
                            .onSuccess { synced ->
                                busy = false
                                editSubTarget = null
                                if (synced > 0) statusMessage = "已同步更新 $synced 笔历史账单"
                                onChanged()
                            }
                            .onFailure {
                                busy = false
                                subError = it.message ?: "保存失败，请重试"
                            }
                    }
                },
            )
        }
    }
}

@Composable
private fun CategoryEditorDialog(
    title: String,
    initialName: String,
    initialIconKey: String?,
    initialColorHex: String?,
    existingNames: Set<String>,
    renameHistoryNotice: (String) -> String? = { null },
    onDismiss: () -> Unit,
    onSave: ((String, String?, String?) -> Unit)? = null,
    onSaveAsync: (suspend (String, String?, String?) -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val scope = rememberCoroutineScope()
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var pickedIcon by remember(initialIconKey) { mutableStateOf(initialIconKey) }
    var pickedColor by remember(initialColorHex) { mutableStateOf(initialColorHex) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val previewName = name.trim().ifBlank { "未命名" }
    val defaultIcon = CategoryIcons.suggestedKeyFor(previewName)
    val defaultColor = swatchFor(previewName)
    val previewColor = parseColorHex(pickedColor) ?: defaultColor
    val previewIcon = pickedIcon ?: defaultIcon
    val historyNotice = renameHistoryNotice(name)

    AppDialog(title = title, onDismiss = { if (!busy) onDismiss() }, dismissible = !busy) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(solidBadgeBackground(previewColor))
                    .badgeLiquidGlaze(effectiveGlass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CategoryIcons.vectorFor(previewIcon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                InsetGroup {
                    Box(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s)) {
                        AbTextField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            placeholder = "输入分类名称",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (historyNotice != null) {
            Text(historyNotice, style = AbType.caption1, color = p.secondaryLabel)
        }
        if (pickedIcon != null || pickedColor != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AbTextButton("恢复自动匹配样式", onClick = {
                    pickedIcon = null
                    pickedColor = null
                })
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Text("配色", style = AbType.footnote, color = p.secondaryLabel)
                ColorSwatchStrip(
                    selectedColorHex = pickedColor,
                    fallbackColor = defaultColor,
                    onSelect = { pickedColor = it },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Text("图标", style = AbType.footnote, color = p.secondaryLabel)
                GroupedIconGrid(
                    selectedKey = previewIcon,
                    accentColor = previewColor,
                    onSelect = { pickedIcon = it },
                )
            }
        }
        if (error != null) InlineMessage(error.orEmpty(), isError = true, announce = true)
        AppDialogActions(
            confirmLabel = if (busy) "保存中…" else "保存",
            confirmEnabled = !busy,
            onDismiss = { if (!busy) onDismiss() },
            onConfirm = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    error = "请输入分类名称"
                    return@AppDialogActions
                }
                if (trimmed in existingNames) {
                    error = "已存在同名分类「$trimmed」"
                    return@AppDialogActions
                }
                if (onSaveAsync != null) {
                    busy = true
                    scope.launch {
                        runCatching { onSaveAsync(trimmed, pickedIcon, pickedColor) }
                            .onFailure {
                                busy = false
                                error = it.message ?: "保存失败，请重试"
                            }
                    }
                } else {
                    onSave?.invoke(trimmed, pickedIcon, pickedColor)
                }
            },
        )
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

@Composable
private fun RestoreCategoriesDefaultsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, errorMessage: String?) {
    AppDialog(title = "恢复默认分类？", onDismiss = onDismiss) {
        Text("将重置支出与收入分类，不影响历史账单与账户。")
        if (errorMessage != null) InlineMessage(errorMessage, isError = true, announce = true)
        AppDialogActions(confirmLabel = "恢复", onConfirm = onConfirm, onDismiss = onDismiss)
    }
}
