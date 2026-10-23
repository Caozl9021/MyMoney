package com.example.autobook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.autobook.R
import com.example.autobook.data.LedgerConfig
import com.example.autobook.data.NoteSuggester
import com.example.autobook.export.CostCsv
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbMotion
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.LocalReduceMotion
import com.example.autobook.ui.design.PrimaryButton
import com.example.autobook.ui.design.Segmented
import com.example.autobook.ui.design.SecondaryButton
import com.example.autobook.ui.design.rememberAbHaptic
import com.example.autobook.ui.design.swatchFor
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 确认卡本体（紧凑底部 Sheet，plan1.0 批次1）。
 *
 * 一眼看清：金额 → 分类 / 二级 / 账户各一行横滑 → 备注 + 建议气泡 → 两个主动作。
 * 商户、支付方式、单号不再各占一行；点头部可以展开看。
 *
 * **两种关闭要分开**（用户确认的规则）：
 *  - [onIgnore]：右上角「忽略」，唯一真正丢弃草稿的入口；
 *  - [onDismiss]：下滑、点空白、返回键 —— 只是"先不处理"，宿主把草稿放回待确认。
 *
 * 三个宿主共用这张卡：[com.example.autobook.overlay.ConfirmOverlay]、[ConfirmActivity]、
 * [BillEditorDialog]。这里不碰窗口、不碰落库。
 */
@Composable
fun ConfirmSheet(
    draft: BillDraft,
    onSave: (BillDraft) -> Unit,
    onImport: (BillDraft) -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit = onIgnore,
    editableDetails: Boolean = false,
    saveLabel: String = "记一笔",
    ignoreLabel: String = "忽略",
    showImport: Boolean = true,
    saving: Boolean = false,
    errorMessage: String? = null,
    amountVisibility: Boolean = true,
    onAmountBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    noteSuggestions: (current: BillDraft) -> List<NoteSuggester.Suggestion> = { emptyList() },
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val haptic = rememberAbHaptic()

    var type by rememberSaveable { mutableStateOf(draft.type) }
    var merchant by rememberSaveable { mutableStateOf(draft.merchant.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(draft.category) }
    var subCategory by rememberSaveable { mutableStateOf(draft.subCategory) }
    var account by rememberSaveable { mutableStateOf(draft.account) }
    var note by rememberSaveable { mutableStateOf(draft.note.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    /** 点气泡前的字段；非空时显示「撤销」 */
    var undo by remember { mutableStateOf<NoteSuggester.Fields?>(null) }

    val groups = remember(type) { LedgerConfig.groupsFor(type) }
    val accounts = remember { LedgerConfig.accounts }
    val subs = remember(category, groups) { groups.firstOrNull { it.name == category }?.subs.orEmpty() }

    // ---- 金额：新建为空（0.00 只是占位）；已有金额首次点入全选，直接键入即替换 ----
    val isNew = draft.priceCents <= 0L
    var amount by remember { mutableStateOf(TextFieldValue(initialAmountText(draft.priceCents))) }
    var selectAllOnFocus by remember { mutableStateOf(!isNew) }
    var forceSelectAll by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew && editableDetails) runCatching { amountFocus.requestFocus() } }
    val validAmount = parseAmountCents(amount.text)
    val amountInvalid = amount.text.isNotBlank() && validAmount == null

    fun current(): BillDraft = draft.copy(
        priceCents = validAmount ?: draft.priceCents,
        type = type,
        merchant = merchant.trim().ifBlank { null },
        category = category,
        subCategory = subCategory,
        account = account,
        note = note.trim().ifBlank { null },
    )
    // noteSuggestions 本身也是 key：最近账单是异步读的，读完换了一个新的 lambda，要重算
    val suggestions = remember(noteSuggestions, type, merchant, category, account, note) { noteSuggestions(current()) }
    val locked = saving || submitted

    // ---- 进出场与下滑关闭 ----
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val offsetY = remember { Animatable(if (reduceMotion) 0f else screenHeightPx) }
    val tracker = remember { VelocityTracker() }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val displayedOffset = if (dragging) dragOffset else offsetY.value
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) offsetY.snapTo(0f) else offsetY.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
    }

    fun closeAnimated(then: () -> Unit) {
        if (locked) return
        scope.launch {
            offsetY.animateTo(screenHeightPx, tween(if (reduceMotion) 0 else AbMotion.normal))
            then()
        }
    }
    fun dismiss() { haptic.select(); closeAnimated(onDismiss) }
    fun ignore() { haptic.warn(); closeAnimated(onIgnore) }
    // 悬浮窗没有 OnBackPressedDispatcher（宿主在 View 层自己接返回键），只在 Activity 宿主里注册
    if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = !locked) { dismiss() }
    }

    val scrimAlpha = (0.32f * (1f - (displayedOffset / (screenHeightPx * 0.7f)))).coerceIn(0f, 0.32f)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 压暗层：点一下 = 先放一边（不是丢弃）
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(remember { MutableInteractionSource() }, indication = null) { dismiss() }
                .semantics { contentDescription = "关闭，稍后在待确认里处理" },
        )

        Column(
            modifier = Modifier
                .widthIn(max = 560.dp).fillMaxWidth()
                .offset { IntOffset(0, displayedOffset.roundToInt()) }
                .clip(RoundedCornerShape(topStart = AbRadius.sheet, topEnd = AbRadius.sheet))
                .background(p.cardBg)
                .imePadding()
                .navigationBarsPadding()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.92f)
                .testTag("confirm-sheet"),
        ) {
            // ---- 抓手：只在这里接下滑，避免和内部横滑/竖滚抢手势 ----
            Box(
                Modifier.fillMaxWidth().height(28.dp)
                    .pointerInput(screenHeightPx, reduceMotion, locked) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragOffset = offsetY.value; dragging = true; tracker.resetTracking()
                                scope.launch { offsetY.stop() }
                            },
                            onDragEnd = {
                                val released = dragOffset
                                val velocity = tracker.calculateVelocity().y
                                val shouldClose = released > with(density) { 120.dp.toPx() } || velocity > 1000f
                                scope.launch {
                                    offsetY.snapTo(released); dragging = false
                                    when {
                                        shouldClose && !locked -> dismiss()
                                        reduceMotion -> offsetY.snapTo(0f)
                                        else -> offsetY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f), initialVelocity = velocity)
                                    }
                                }
                            },
                            onDragCancel = {
                                val cancelled = dragOffset
                                scope.launch {
                                    offsetY.snapTo(cancelled); dragging = false
                                    if (reduceMotion) offsetY.snapTo(0f) else offsetY.animateTo(0f, spring(stiffness = 600f))
                                }
                            },
                        ) { change, delta ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, Offset(change.position.x, change.position.y + dragOffset))
                            dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                        }
                    }
                    .semantics { contentDescription = "下滑关闭" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100.dp))
                    .background(p.tertiaryLabel.copy(alpha = 0.5f)))
            }

            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(bottom = AbSpacing.l),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
            ) {
                // ---- 头部：品牌 + 标题/副标题（点开看详情）+ 忽略 ----
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_color),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                    )
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(AbRadius.row))
                            .clickable(remember { MutableInteractionSource() }, indication = null) {
                                haptic.tick(); detailsOpen = !detailsOpen
                            }
                            .semantics { contentDescription = if (detailsOpen) "收起账单详情" else "展开账单详情" },
                    ) {
                        Text(
                            merchant.ifBlank { if (editableDetails && isNew) "记一笔" else typeLabel(type) },
                            style = AbType.headline, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(typeLabel(type))
                                draft.method?.let { append(" · ").append(it) }
                                append(" · ").append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(draft.timeMillis)))
                                if (hasDetails(draft, editableDetails)) append(if (detailsOpen) "  ▴" else "  ▾")
                            },
                            style = AbType.caption1, color = p.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        ignoreLabel,
                        style = AbType.body,
                        color = p.secondaryLabel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !locked) { ignore() }
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                            .semantics { role = Role.Button },
                    )
                }

                AnimatedVisibility(
                    visible = detailsOpen && hasDetails(draft, editableDetails),
                    enter = if (reduceMotion) fadeIn(tween(0)) else expandVertically() + fadeIn(),
                    exit = if (reduceMotion) fadeOut(tween(0)) else shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl)
                            .clip(RoundedCornerShape(AbRadius.row)).background(p.fill)
                            .padding(horizontal = AbSpacing.m, vertical = AbSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!editableDetails && merchant.isNotBlank()) DetailLine("商户", merchant)
                        draft.method?.let { DetailLine("支付方式", it) }
                        draft.orderTail?.let { DetailLine("单号尾号", it) }
                        sourceLabel(draft)?.let { DetailLine("来源", it) }
                    }
                }

                // ---- 金额 ----
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl)
                        .onGloballyPositioned { onAmountBounds?.invoke(it.boundsInRoot()) }
                        .graphicsLayer { alpha = if (amountVisibility) 1f else 0f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("¥", style = AbType.amountTitle, color = p.secondaryLabel)
                    val amountStyle = AbType.amountEntry.copy(color = p.label,
                        fontSize = AbType.amountEntry.fontSize / density.fontScale.coerceAtLeast(1f))
                    BasicTextField(
                        value = amount,
                        onValueChange = { next ->
                            amount = if (forceSelectAll) {
                                forceSelectAll = false
                                next.copy(selection = TextRange(0, next.text.length))
                            } else next
                            importError = null
                        },
                        singleLine = true,
                        enabled = !locked,
                        textStyle = amountStyle,
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).focusRequester(amountFocus)
                            .onFocusChanged { state ->
                                // 只在第一次获得焦点时全选；之后再点保留光标位置，不丢已输入内容
                                if (state.isFocused && selectAllOnFocus) {
                                    selectAllOnFocus = false
                                    forceSelectAll = true
                                    amount = amount.copy(selection = TextRange(0, amount.text.length))
                                }
                            }
                            .semantics { contentDescription = "金额" },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (amount.text.isEmpty()) Text("0.00", style = amountStyle.copy(color = p.tertiaryLabel))
                                inner()
                            }
                        },
                    )
                }
                if (amountInvalid) {
                    Text("请输入大于0且不超过一百万元的金额", color = p.red, style = AbType.caption1,
                        modifier = Modifier.padding(horizontal = AbSpacing.xl))
                }

                if (editableDetails) {
                    Segmented(
                        options = BillType.entries.map(::typeLabel),
                        selected = BillType.entries.indexOf(type),
                        onSelect = { index ->
                            val next = BillType.entries[index]
                            if (type != next) { haptic.tick(); type = next; category = null; subCategory = null; undo = null }
                            importError = null
                        },
                        modifier = Modifier.padding(horizontal = AbSpacing.xl),
                    )
                }

                // ---- 分类 / 二级 / 账户：各一行横滑 ----
                ChipRow(
                    label = "分类",
                    items = groups.map { it.name },
                    selected = category,
                    tintFor = { swatchFor(it) },
                    onToggle = { name ->
                        haptic.tick()
                        category = if (category == name) null else name
                        subCategory = null
                    },
                )
                AnimatedVisibility(
                    visible = subs.isNotEmpty(),
                    enter = if (reduceMotion) fadeIn(tween(0)) else expandVertically(tween(AbMotion.normal)) + fadeIn(),
                    exit = if (reduceMotion) fadeOut(tween(0)) else shrinkVertically(tween(AbMotion.fast)) + fadeOut(),
                ) {
                    ChipRow(
                        label = "二级",
                        items = subs,
                        selected = subCategory,
                        tintFor = { swatchFor(category ?: "") },
                        // 换一级分类时整行重建：旧二级的滚动位置/选中态不能残留
                        key = category,
                        onToggle = { sub -> haptic.tick(); subCategory = if (subCategory == sub) null else sub },
                    )
                }
                ChipRow(
                    label = "账户",
                    items = accounts,
                    selected = account,
                    onToggle = { name -> haptic.tick(); account = if (account == name) null else name },
                )

                // ---- 备注 + 建议气泡（编辑器里商户也在这一组） ----
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl)
                        .clip(RoundedCornerShape(AbRadius.row)).background(p.fill),
                ) {
                    if (editableDetails) {
                        InlineField("商户", merchant, "可留空", enabled = !locked) { merchant = it; undo = null }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = AbSpacing.l).background(p.separator))
                    }
                    InlineField("备注", note, "可留空，导出时写备注列", enabled = !locked) { note = it; undo = null }
                }
                if (suggestions.isNotEmpty() || undo != null) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = AbSpacing.xl),
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "备注建议" },
                    ) {
                        undo?.let { previous ->
                            item(key = "undo") {
                                SuggestionBubble("撤销", accent = true) {
                                    haptic.tick()
                                    note = previous.note; merchant = previous.merchant; category = previous.category
                                    subCategory = previous.subCategory; account = previous.account; undo = null
                                }
                            }
                        }
                        itemsIndexed(suggestions, key = { _, s -> "s:" + s.label }) { _, s ->
                            SuggestionBubble(s.label) {
                                haptic.tick()
                                val before = NoteSuggester.Fields(note, merchant, category, subCategory, account)
                                val after = NoteSuggester.apply(before, s)
                                // 只接受当前清单里还存在的名字，历史账单可能引用已删掉的分类/账户
                                val group = LedgerConfig.groupsFor(type).firstOrNull { it.name == after.category }
                                note = after.note
                                merchant = after.merchant
                                category = group?.name ?: before.category
                                subCategory = after.subCategory?.takeIf { group != null && it in group.subs } ?: before.subCategory.takeIf { group?.name == before.category }
                                account = after.account?.takeIf { it in LedgerConfig.accounts } ?: before.account
                                undo = before
                            }
                        }
                    }
                }

                // ---- 动作 ----
                (errorMessage ?: importError)?.let {
                    Text(it, color = p.red, style = AbType.footnote, modifier = Modifier.padding(horizontal = AbSpacing.xl))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl).padding(top = AbSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                ) {
                    if (showImport) SecondaryButton(
                        text = "导入 iCost",
                        onClick = {
                            when {
                                locked -> Unit
                                validAmount == null -> importError = "请输入有效金额后再导入"
                                type == BillType.REFUND -> importError = CostCsv.REFUND_UNSUPPORTED
                                else -> {
                                    haptic.select()
                                    try { onImport(current()); submitted = true }
                                    catch (error: IllegalArgumentException) { importError = error.message ?: "无法导入，请检查账单" }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = if (saving) "保存中…" else saveLabel,
                        enabled = validAmount != null && !locked,
                        onClick = {
                            if (validAmount != null && !locked) {
                                if (!editableDetails) submitted = true
                                onSave(current())
                            }
                        },
                        modifier = Modifier.weight(if (showImport) 1.25f else 1f),
                    )
                }
            }
        }
    }
}

/**
 * 一行横滑的胶囊：左侧固定小标签，右侧 LazyRow 不换行。
 * 打开时和选中项变化时自动把选中项滚进可视区（N1.3：当前项可见）。
 */
@Composable
private fun ChipRow(
    label: String,
    items: List<String>,
    selected: String?,
    onToggle: (String) -> Unit,
    tintFor: ((String) -> Color)? = null,
    key: Any? = null,
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val state = remember(key) { androidx.compose.foundation.lazy.LazyListState() }
    LaunchedEffect(key, selected, items) {
        val index = items.indexOf(selected)
        if (index >= 0) {
            val visible = state.layoutInfo.visibleItemsInfo
            val fully = visible.any { it.index == index && it.offset >= 0 &&
                it.offset + it.size <= state.layoutInfo.viewportEndOffset }
            if (!fully) {
                if (reduceMotion || visible.isEmpty()) state.scrollToItem(index, -24)
                else state.animateScrollToItem(index, -24)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = AbType.caption1.copy(fontWeight = FontWeight.SemiBold),
            color = p.secondaryLabel,
            modifier = Modifier.padding(start = AbSpacing.xl).widthIn(min = 30.dp),
        )
        if (items.isEmpty()) {
            Text("清单是空的，去「设置 → 分类与账户」添加", style = AbType.caption1, color = p.tertiaryLabel,
                modifier = Modifier.padding(start = AbSpacing.s, end = AbSpacing.xl))
            return@Row
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = AbSpacing.s, end = AbSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(items, key = { _, name -> name }) { _, name ->
                SheetChip(name, selected == name, tintFor?.invoke(name)) { onToggle(name) }
            }
        }
    }
}

/** 紧凑胶囊：视觉 36dp、触控 48dp；选中时用分类色（没有就用强调色）。 */
@Composable
private fun SheetChip(text: String, selected: Boolean, tint: Color?, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    val duration = if (LocalReduceMotion.current) 0 else 180
    val c = tint ?: p.accent
    val bg by androidx.compose.animation.animateColorAsState(if (selected) c.copy(alpha = 0.16f) else p.fill, tween(duration), label = "chip-bg")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) c else p.label, tween(duration), label = "chip-fg")
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(AbRadius.capsule)).background(bg)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selected) Icon(AbIcons.Check, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Text(text, style = AbType.footnote.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
        }
    }
}

/** 备注建议气泡：比分类胶囊更轻，避免和"选中态"混淆。 */
@Composable
private fun SuggestionBubble(text: String, accent: Boolean = false, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = if (accent) "撤销建议" else "用作备注：$text" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(AbRadius.capsule))
                .background(if (accent) p.accent.copy(alpha = 0.12f) else p.cardBg)
                .then(if (accent) Modifier else Modifier.border(1.dp, p.separator, RoundedCornerShape(AbRadius.capsule)))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!accent) Icon(AbIcons.Note, contentDescription = null, tint = p.secondaryLabel, modifier = Modifier.size(13.dp))
            Text(text, style = AbType.footnote, color = if (accent) p.accent else p.label, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

/** 灰底分组里的一行输入（左标签 + 输入框），比 InputRow 紧凑。 */
@Composable
private fun InlineField(label: String, value: String, placeholder: String, enabled: Boolean, onChange: (String) -> Unit) {
    val p = LocalAbPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AbSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        Text(label, style = AbType.body, color = p.label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = AbType.body.copy(color = p.label),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = AbType.body, color = p.tertiaryLabel, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val p = LocalAbPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
        Text(label, style = AbType.footnote, color = p.secondaryLabel, modifier = Modifier.widthIn(min = 64.dp))
        Text(value, style = AbType.footnote, color = p.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun hasDetails(draft: BillDraft, editable: Boolean): Boolean =
    (!editable && !draft.merchant.isNullOrBlank()) || draft.method != null || draft.orderTail != null || sourceLabel(draft) != null

private fun sourceLabel(draft: BillDraft): String? = when {
    draft.sourcePkg.isBlank() || draft.sourcePkg.startsWith("com.example.autobook.manual") -> null
    else -> when (draft.sourcePkg) {
        com.example.billkit.Packages.WECHAT -> "微信"
        com.example.billkit.Packages.ALIPAY -> "支付宝"
        com.example.billkit.Packages.UNIONPAY -> "云闪付"
        com.example.billkit.Packages.PDD -> "拼多多"
        in com.example.billkit.Packages.jdGroup -> "京东"
        in com.example.billkit.Packages.meituanGroup -> "美团"
        in com.example.billkit.Packages.taobaoGroup -> "淘宝"
        in com.example.billkit.Packages.douyinGroup -> "抖音"
        else -> draft.sourcePkg
    }
}

private fun typeLabel(type: BillType): String = when (type) {
    BillType.EXPENSE -> "支出"
    BillType.INCOME -> "收入"
    BillType.TRANSFER -> "转账"
    BillType.REFUND -> "退款"
}
