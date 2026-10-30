package io.github.caozl9021.mymoney.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
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
import io.github.caozl9021.mymoney.R
import io.github.caozl9021.mymoney.data.LedgerConfig
import io.github.caozl9021.mymoney.data.NoteTemplate
import io.github.caozl9021.mymoney.data.NoteSuggester
import io.github.caozl9021.mymoney.ui.design.amountTint
import io.github.caozl9021.mymoney.ui.design.AbIcons
import io.github.caozl9021.mymoney.ui.design.AbMotion
import io.github.caozl9021.mymoney.ui.design.AbRadius
import io.github.caozl9021.mymoney.ui.design.AbSpacing
import io.github.caozl9021.mymoney.ui.design.AbType
import io.github.caozl9021.mymoney.ui.design.LocalAbPalette
import io.github.caozl9021.mymoney.ui.design.LocalReduceMotion
import io.github.caozl9021.mymoney.ui.design.PrimaryButton
import io.github.caozl9021.mymoney.ui.design.Segmented
import io.github.caozl9021.mymoney.ui.design.rememberAbHaptic
import io.github.caozl9021.mymoney.ui.design.swatchFor
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
 * 金额、分类、账户与可选备注直接可编辑，来源信息折叠。
 * 手动新建/编辑支持账本时间；宿主仅在真实保存成功后收起。
 *
 * **两种关闭要分开**（用户确认的规则）：
 *  - [onIgnore]：右上角「忽略」，宿主保存 IGNORED，仍可在识别历史重新打开；
 *  - [onDismiss]：下滑、点空白、返回键 —— 只是"先不处理"，宿主把草稿放回待确认。
 *
 * 三个宿主共用这张卡：[io.github.caozl9021.mymoney.overlay.ConfirmOverlay]、[ConfirmActivity]、
 * [BillEditorDialog]。这里不碰窗口、不碰落库。
 */
@Composable
fun ConfirmSheet(
    draft: BillDraft,
    onSave: (BillDraft) -> Unit,
    // Kept for existing hosts; sharing is now available only after saving.
    onImport: (BillDraft) -> Unit,
    onIgnore: () -> Unit,
    onDismiss: (BillDraft) -> Unit = { onIgnore() },
    onDraftChange: (BillDraft) -> Unit = {},
    editableDetails: Boolean = false,
    saveLabel: String = "记一笔",
    ignoreLabel: String = "忽略",
    showImport: Boolean = true,
    saving: Boolean = false,
    busyLabel: String = "保存中…",
    errorMessage: String? = null,
    assetHint: String? = null,
    amountVisibility: Boolean = true,
    onAmountBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    onPickerBackHandler: (((() -> Boolean)?) -> Unit)? = null,
    noteSuggestions: (current: BillDraft) -> List<NoteSuggester.Suggestion> = { emptyList() },
) {
    val p = LocalAbPalette.current
    DisposableEffect(draft.historyId) {
        val id = draft.historyId
        if (id != null) io.github.caozl9021.mymoney.data.CaptureEditLocks.acquire(id)
        onDispose { if (id != null) io.github.caozl9021.mymoney.data.CaptureEditLocks.release(id) }
    }
    val reduceMotion = LocalReduceMotion.current
    val haptic = rememberAbHaptic()

    var type by rememberSaveable { mutableStateOf(draft.type) }
    val merchant = draft.merchant.orEmpty()
    var timeMillis by rememberSaveable { mutableStateOf(draft.timeMillis) }
    var timeOpen by remember { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(draft.category) }
    var subCategory by rememberSaveable { mutableStateOf(draft.subCategory) }
    var account by rememberSaveable { mutableStateOf(draft.account) }
    var transferAccountId by rememberSaveable { mutableStateOf(draft.transferAccountId) }
    var note by rememberSaveable { mutableStateOf(draft.note.orEmpty()) }
    var noteOrigin by rememberSaveable { mutableStateOf(draft.noteOrigin) }
    val templateSnapshot = rememberSaveable { draft.noteTemplateSnapshot ?: LedgerConfig.noteTemplate }
    var discardPrompt by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 仅挡住宿主saving重组前的连续点按，不能在失败后留下永久锁。
    var dispatching by remember { mutableStateOf(false) }
    LaunchedEffect(dispatching) {
        if (dispatching) {
            withFrameNanos { }
            dispatching = false
        }
    }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var categoryOpen by rememberSaveable { mutableStateOf(false) }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    /** 点气泡前的字段；非空时显示「撤销」 */
    var undo by remember { mutableStateOf<String?>(null) }

    var configVersion by remember { mutableStateOf(0) }
    val groups = remember(type, configVersion) { LedgerConfig.groupsFor(type) }
    val accounts = remember(configVersion) { LedgerConfig.accounts }
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
        merchant = draft.merchant,
        timeMillis = timeMillis,
        category = category.takeUnless { type == BillType.TRANSFER },
        subCategory = subCategory.takeUnless { type == BillType.TRANSFER },
        transferAccountId = transferAccountId.takeIf { type == BillType.TRANSFER },
        account = account,
        note = note.trim().ifBlank { null },
        noteOrigin = noteOrigin, noteTemplateSnapshot = templateSnapshot,
    )
    LaunchedEffect(type, category, subCategory, account, transferAccountId, timeMillis) {
        if (noteOrigin == NoteTemplate.TEMPLATE) note = NoteTemplate.render(current(), templateSnapshot)
    }
    val editedDraft = current()
    val latestDraftChange by rememberUpdatedState(onDraftChange)
    LaunchedEffect(editedDraft) { latestDraftChange(editedDraft) }
    // noteSuggestions 本身也是 key：最近账单是异步读的，读完换了一个新的 lambda，要重算
    val suggestions = remember(noteSuggestions, editedDraft) { noteSuggestions(current()) }
    var closing by remember { mutableStateOf(false) }
    val locked = saving || dispatching || closing
    val picker = remember { CategoryPickerState() }
    var categoryViewport by remember { mutableStateOf(Rect.Zero) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentLocked by rememberUpdatedState(locked)
    fun selectSub(group: String, sub: String, pulse: Boolean = true) {
        if (locked || groups.none { it.name == group && sub in it.subs }) return
        if (pulse) haptic.tick()
        category = group
        subCategory = sub
        undo = null
    }
    val currentSelectSub by rememberUpdatedState<(String, String) -> Unit>({ group, sub -> selectSub(group, sub, false) })
    val currentOpenPicker by rememberUpdatedState<(String, Rect, Boolean) -> Unit>({ group, anchor, sliding ->
        if (!locked) groups.firstOrNull { it.name == group }?.takeIf { it.subs.isNotEmpty() }?.let {
            haptic.select()
            picker.show(CategoryPickerRequest(group, it.subs, anchor,
                subCategory.takeIf { category == group }, sliding, categoryViewport))
        }
    })
    LaunchedEffect(locked) { if (locked) picker.cancel() }
    DisposableEffect(onPickerBackHandler) {
        onPickerBackHandler?.invoke { when { timeOpen -> { timeOpen = false; true }; picker.open -> { picker.cancel(); true }; else -> false } }
        onDispose { onPickerBackHandler?.invoke(null) }
    }

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

    var closeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // A failed asynchronous dismiss/ignore keeps the host alive; bring its editor back.
    // Include saving so another attempt reporting the same error also unlocks the card.
    LaunchedEffect(errorMessage, saving) {
        if (!saving && errorMessage != null) {
            closeJob?.cancel()
            closeJob = null
            dragging = false
            dragOffset = 0f
            offsetY.snapTo(0f)
            closing = false
        }
    }

    fun closeAnimated(then: () -> Unit) {
        if (locked || closing) return
        closing = true
        closeJob = scope.launch {
            var delivered = false
            try {
                offsetY.animateTo(screenHeightPx, tween(if (reduceMotion) 0 else AbMotion.normal))
                delivered = true
                then()
            } finally {
                if (!delivered) closing = false
            }
        }
    }
    fun requestClose(action: () -> Unit) {
        if (locked) return
        if (editableDetails && editorFieldsChanged(draft, current(), amount.text)) {
            discardAction = action
            discardPrompt = true
        } else closeAnimated(action)
    }
    fun dismiss() { if (locked) return; if (timeOpen) { timeOpen = false; return }; if (picker.open) picker.cancel() else { haptic.select(); requestClose { onDismiss(current()) } } }
    fun ignore() { if (!locked) { haptic.warn(); requestClose(onIgnore) } }
    if (discardPrompt) DiscardChangesDialog(onContinue = { discardPrompt = false; discardAction = null },
        onDiscard = { val action = discardAction; discardPrompt = false; discardAction = null; if (action != null) closeAnimated(action) })
    // 悬浮窗没有 OnBackPressedDispatcher（宿主在 View 层自己接返回键），只在 Activity 宿主里注册
    if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        ActiveBackHandler { if (!locked) dismiss() }
    }

    val scrimAlpha = (0.32f * (1f - (displayedOffset / (screenHeightPx * 0.7f)))).coerceIn(0f, 0.32f)

    Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft }, contentAlignment = Alignment.BottomCenter) {
        // 压暗层：识别草稿回待确认；编辑器由宿主处理取消。
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !locked) { dismiss() }
                .semantics { contentDescription = if (editableDetails) "取消编辑" else "关闭，稍后在待确认里处理" },
        )

        // Insets belong to the viewport, not the painted card. The padding modifiers
        // consume ancestor insets and share consumption, so IME/navigation avoidance
        // uses the remaining obstruction rather than adding two bottom gaps.
        BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp).fillMaxWidth()
                    .offset { IntOffset(0, displayedOffset.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = AbRadius.sheet, topEnd = AbRadius.sheet))
                    .background(p.cardBg)
                    .heightIn(max = maxHeight * 0.92f)
                    .testTag("confirm-sheet"),
            ) {
                // ---- 抓手：只在这里接下滑，避免和内部横滑/竖滚抢手势 ----
                Box(
                    Modifier.fillMaxWidth().height(28.dp)
                        .pointerInput(screenHeightPx, reduceMotion, locked) {
                            if (locked) return@pointerInput
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
                    // ---- 金额 ----
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl)
                            .onGloballyPositioned { onAmountBounds?.invoke(it.boundsInRoot()) }
                            .graphicsLayer { alpha = if (amountVisibility) 1f else 0f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("¥", style = AbType.amountTitle, color = amountTint(p, type.name), modifier = Modifier.padding(end = 6.dp))
                        val amountStyle = AbType.amountEntry.copy(color = amountTint(p, type.name),
                            fontSize = AbType.amountEntry.fontSize / density.fontScale.coerceAtLeast(1f))
                        BasicTextField(
                            value = amount,
                            onValueChange = { next ->
                                undo = null
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
                                .semantics { contentDescription = "${typeLabel(type)}金额"; if (amountInvalid) error("请输入大于0且不超过一百万元的金额") },
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (amount.text.isEmpty()) Text("0.00", style = amountStyle.copy(color = p.tertiaryLabel))
                                    inner()
                                }
                            },
                        )
                        Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                            .clickable(enabled = !locked) { ignore() }.semantics { role = Role.Button; contentDescription = if (editableDetails) "取消编辑" else "忽略，可在识别历史重新打开" },
                            contentAlignment = Alignment.Center) { Text(ignoreLabel, style = AbType.footnote, color = p.secondaryLabel) }
                    }
                    if (amountInvalid) Text("请输入有效金额", color = p.red, style = AbType.caption1,
                        modifier = Modifier.padding(horizontal = AbSpacing.xl).semantics { liveRegion = LiveRegionMode.Polite; error("金额无效") })
                    Segmented(
                        options = BillType.entries.map(::typeLabel), selected = BillType.entries.indexOf(type), enabled = !locked,
                        onSelect = { index ->
                            if (!locked) {
                                val next = BillType.entries[index]
                                if (type != next) { haptic.tick(); picker.cancel(); type = next; category = null; subCategory = null }
                                importError = null
                            }
                        }, modifier = Modifier.padding(horizontal = AbSpacing.xl),
                    )
                    val assetAccounts by io.github.caozl9021.mymoney.data.AssetStore.accounts.collectAsStateWithLifecycle()
                    val assetAccount = assetAccounts.firstOrNull { it.name == account }
                    val transferEligible = assetAccounts.filter { !it.archived && it.initialized }
                    val fromTransfer = transferEligible.firstOrNull { it.name == account }
                    val toTransfer = transferEligible.firstOrNull { it.id == transferAccountId }
                    val transferValid = type != BillType.TRANSFER || fromTransfer != null && toTransfer != null && fromTransfer.id != toTransfer.id
                    if (type == BillType.TRANSFER) {
                        ChipRow(label = "转出账户", items = transferEligible.map { it.name }, selected = account, enabled = !locked,
                            onToggle = { name -> haptic.tick(); account = name })
                        ChipRow(label = "转入账户", items = transferEligible.map { it.name }, selected = toTransfer?.name, enabled = !locked,
                            onToggle = { name -> haptic.tick(); transferAccountId = transferEligible.firstOrNull { it.name == name }?.id })
                        if (transferEligible.size < 2) Text("请先在设置→资产为至少两个账户设置余额", style = AbType.caption1, color = p.secondaryLabel,
                            modifier = Modifier.padding(horizontal = AbSpacing.xl))
                        else if (fromTransfer != null && fromTransfer.id == toTransfer?.id) Text("转出和转入账户不能相同", style = AbType.caption1, color = p.red,
                            modifier = Modifier.padding(horizontal = AbSpacing.xl))
                    } else {
                    if (groups.isEmpty()) EmptyLedgerPickerActions("分类", !locked,
                        onAdd = { LedgerConfig.addGroup(type, it); configVersion++ }, onRestored = { configVersion++ })
                    ChipRow(
                        label = "分类", items = groups.map { it.name }, selected = category, enabled = !locked,
                        displayName = { name -> if (category == name && subCategory != null) "$name · $subCategory" else name },
                        onViewport = { categoryViewport = it },
                        slideFor = { name -> groups.firstOrNull { it.name == name }?.subs?.isNotEmpty() == true },
                        onSlideStart = { name, anchor -> currentOpenPicker(name, anchor, true) }, onSlideMove = picker::move,
                        onSlideRelease = { picker.release()?.let { (group, sub) -> currentSelectSub(group, sub) } }, onSlideCancel = picker::cancel,
                        onOpenSub = { name, anchor -> currentOpenPicker(name, anchor, false) },
                        onToggle = { name -> if (!currentLocked) { haptic.tick(); category = if (category == name) null else name; subCategory = null } },
                    )
                    if (accounts.isEmpty()) EmptyLedgerPickerActions("账户", !locked,
                        onAdd = { LedgerConfig.addAccount(it); configVersion++ }, onRestored = { configVersion++ })
                    ChipRow(label = "账户", items = accounts, selected = account, enabled = !locked,
                        onToggle = { name -> haptic.tick(); account = if (account == name) null else name })
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl)) {
                        CompactNoteField(note, !locked) { note = it; noteOrigin = NoteTemplate.USER; undo = null }
                    }
                    if (suggestions.isNotEmpty() || undo != null) LazyRow(
                        contentPadding = PaddingValues(horizontal = AbSpacing.xl), horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "备注建议" },
                    ) {
                        undo?.let { previous -> item(key = "undo") { SuggestionBubble("撤销", accent = true, enabled = !locked) {
                            haptic.tick(); note = previous; noteOrigin = NoteTemplate.USER; undo = null
                        } } }
                        itemsIndexed(suggestions, key = { _, suggestion -> "s:" + suggestion.label }) { _, suggestion ->
                            SuggestionBubble(suggestion.label, enabled = !locked) {
                                haptic.tick(); undo = note; note = suggestion.note; noteOrigin = NoteTemplate.SUGGESTION
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl).heightIn(min = 48.dp)
                        .then(if (editableDetails || type == BillType.TRANSFER) Modifier.clickable(enabled = !locked) { picker.cancel(); timeOpen = true }
                            .semantics { role = Role.Button; contentDescription = "选择记账日期和时间" } else Modifier),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                        Text("时间", style = AbType.caption1, color = p.secondaryLabel)
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis)), style = AbType.footnote, color = p.label, modifier = Modifier.weight(1f))
                        if (editableDetails || type == BillType.TRANSFER) Icon(AbIcons.ChevronRight, null, Modifier.size(16.dp), tint = p.secondaryLabel)
                    }
                    if (editableDetails && timeMillis > System.currentTimeMillis() && assetAccount?.initialized == true)
                        Text("保存即更新当前余额", style = AbType.caption1, color = p.secondaryLabel, modifier = Modifier.padding(horizontal = AbSpacing.xl))
                    if (merchant.isNotBlank() || draft.method != null || draft.orderTail != null || sourceLabel(draft) != null || assetHint != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl).heightIn(min = 48.dp)
                            .clickable(enabled = !locked) { picker.cancel(); detailsOpen = !detailsOpen }
                            .semantics { role = Role.Button; stateDescription = if (detailsOpen) "已展开" else "已收起" }, verticalAlignment = Alignment.CenterVertically) {
                            Text("来源信息", style = AbType.caption1, color = p.secondaryLabel, modifier = Modifier.weight(1f))
                            Text(if (detailsOpen) "−" else "+", style = AbType.footnote, color = p.secondaryLabel)
                        }
                        AnimatedVisibility(visible = detailsOpen) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                if (merchant.isNotBlank()) DetailLine("商户", merchant)
                                draft.method?.let { DetailLine("支付方式", it) }
                                draft.orderTail?.let { DetailLine("单号尾号", it) }
                                sourceLabel(draft)?.let { DetailLine("来源", it) }
                                assetHint?.let { Text(it, style = AbType.caption1, color = p.secondaryLabel) }
                            }
                        }
                    }

                    // ---- 动作 ----
                    (errorMessage ?: importError)?.let {
                        Text("操作未完成：$it", color = p.red, style = AbType.footnote,
                            modifier = Modifier.padding(horizontal = AbSpacing.xl).semantics {
                                liveRegion = LiveRegionMode.Polite
                                error(it)
                            })
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xl).padding(top = AbSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                    ) {
                        PrimaryButton(
                            text = saveLabel,
                            busy = saving,
                            busyLabel = busyLabel,
                            enabled = validAmount != null && transferValid && !locked,
                            onClick = {
                                if (validAmount != null && !saving && !dispatching) {
                                    dispatching = true
                                    try { onSave(current()) }
                                    catch (error: IllegalArgumentException) {
                                        dispatching = false
                                        importError = error.message ?: "无法保存，请检查账单"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (timeOpen) LedgerDateTimeDialog(timeMillis, onDismiss = { timeOpen = false }, onConfirm = { timeMillis = it; timeOpen = false })
        CategoryPicker(picker, hostOrigin) { group, sub -> selectSub(group, sub) }
    }
}

/** 摘要本身就是修改入口，长文本换行且保持48dp触控。 */
@Composable
private fun ReceiptSummaryRow(label: String, value: String, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AbSpacing.l, vertical = AbSpacing.s)
            .semantics { role = Role.Button; contentDescription = "$label：$value，点击修改"; stateDescription = if (expanded) "已展开" else "已收起" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        Text(label, style = AbType.footnote, color = p.secondaryLabel)
        Text(value, style = AbType.body.copy(fontWeight = FontWeight.Medium), color = p.label, modifier = Modifier.weight(1f))
        Text(if (expanded) "收起" else "修改", style = AbType.caption1, color = p.accent)
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
    enabled: Boolean = true,
    slideFor: (String) -> Boolean = { false },
    onSlideStart: (String, Rect) -> Unit = { _, _ -> },
    onSlideMove: (Offset) -> Unit = {},
    onSlideRelease: () -> Unit = {},
    onSlideCancel: () -> Unit = {},
    onOpenSub: (String, Rect) -> Unit = { _, _ -> },
    onLabelClick: ((Rect) -> Unit)? = null,
    displayName: (String) -> String = { it },
    onViewport: (Rect) -> Unit = {},
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    // Empty category/account lists already expose their local add/reset actions above.
    if (items.isEmpty()) return
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
    var labelCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentStart by rememberUpdatedState(onSlideStart)
    val currentMove by rememberUpdatedState(onSlideMove)
    val currentRelease by rememberUpdatedState(onSlideRelease)
    val currentCancel by rememberUpdatedState(onSlideCancel)
    val currentOpen by rememberUpdatedState(onOpenSub)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (onLabelClick != null) "二级 ›" else label,
            style = AbType.caption1.copy(fontWeight = FontWeight.SemiBold),
            color = p.secondaryLabel,
            modifier = Modifier.onGloballyPositioned { labelCoordinates = it }
                .then(if (onLabelClick != null) Modifier.heightIn(min = 48.dp).clickable(enabled = enabled) {
                    labelCoordinates?.takeIf { it.isAttached }?.let { onLabelClick(it.boundsInRoot()) }
                }.semantics { contentDescription = "选择二级分类"; role = Role.Button } else Modifier)
                .padding(start = AbSpacing.xl).widthIn(min = 30.dp).wrapContentHeight(),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = AbSpacing.s, end = AbSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
            modifier = Modifier.weight(1f).onGloballyPositioned { onViewport(it.boundsInRoot()) },
        ) {
            itemsIndexed(items, key = { _, name -> name }) { _, name ->
                var suppressHeldClick by remember { mutableStateOf(false) }
                val gestureScope = rememberCoroutineScope()
                fun endHeldClick() { gestureScope.launch { withFrameNanos { }; suppressHeldClick = false } }
                var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val hasSubs = slideFor(name)
                SheetChip(displayName(name), selected == name, tintFor?.invoke(name), enabled = enabled,
                    modifier = Modifier.onGloballyPositioned { coordinates = it }
                        .categorySlide(name, enabled && hasSubs, { coordinates },
                            onStart = { suppressHeldClick = true; currentStart(name, it) }, onMove = { currentMove(it) },
                            onRelease = { currentRelease(); endHeldClick() }, onCancel = { currentCancel(); endHeldClick() },
                            onPress = { suppressHeldClick = false })
                        .semantics {
                            if (hasSubs && enabled) customActions = listOf(
                                androidx.compose.ui.semantics.CustomAccessibilityAction("选择${name}二级分类") {
                                    coordinates?.takeIf { it.isAttached }?.let { currentOpen(name, it.boundsInRoot()) }
                                    true
                                })
                        },
                ) {
                    if (suppressHeldClick) return@SheetChip
                    if (hasSubs && selected == name) coordinates?.takeIf { it.isAttached }?.let { currentOpen(name, it.boundsInRoot()) }
                    else onToggle(name)
                }
            }
        }
    }
}

/** 紧凑胶囊：视觉 36dp、触控 48dp；选中时用分类色（没有就用强调色）。 */
@Composable
private fun SheetChip(text: String, selected: Boolean, tint: Color?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    val duration = if (LocalReduceMotion.current) 0 else AbMotion.selection
    val c = tint ?: p.accent
    val bg by androidx.compose.animation.animateColorAsState(if (selected) c.copy(alpha = 0.16f) else p.fill, tween(duration), label = "chip-bg")
    val fg by androidx.compose.animation.animateColorAsState(if (enabled) p.label else p.secondaryLabel, tween(duration), label = "chip-fg")
    Box(
        modifier.heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab; stateDescription = if (selected) "已选中" else "未选中" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(AbRadius.capsule)).background(bg)
                .border(1.dp, if (selected) c.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(AbRadius.capsule))
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
private fun SuggestionBubble(text: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    var preview by remember { mutableStateOf(false) }
    if (preview) androidx.compose.ui.window.Dialog(onDismissRequest = { preview = false }) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(AbRadius.card), color = p.cardBg) {
            Column(Modifier.padding(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                Text("使用这条备注？", style = AbType.navTitle, color = p.label)
                Text(text.removePrefix("商品 · "), style = AbType.body, color = p.label)
                PrimaryButton("填入备注", onClick = { preview = false; onClick() }, enabled = enabled)
                io.github.caozl9021.mymoney.ui.design.SecondaryButton("取消", onClick = { preview = false })
            }
        }
    }
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = { if (!accent && text.codePointCount(0, text.length) > 18) preview = true else onClick() })
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
            Text(text, style = AbType.footnote, color = if (!enabled) p.secondaryLabel else if (accent) p.accent else p.label, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

/** No label icon or reserved multiline space; content grows to three lines. */
@Composable
private fun CompactNoteField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val p = LocalAbPalette.current
    BasicTextField(value = value, onValueChange = onChange, enabled = enabled,
        minLines = 1, maxLines = 3, textStyle = AbType.body.copy(color = p.label), cursorBrush = SolidColor(p.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AbRadius.row)).background(p.fill)
            .heightIn(min = 44.dp).padding(horizontal = AbSpacing.l, vertical = 10.dp)
            .semantics { contentDescription = "备注（可选）" },
        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("备注（可选）", style = AbType.body, color = p.tertiaryLabel)
            inner()
        } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    val p = LocalAbPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
        Text(label, style = AbType.footnote, color = p.secondaryLabel, modifier = Modifier.widthIn(min = 64.dp))
        Text(value, style = AbType.footnote, color = p.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun sourceLabel(draft: BillDraft): String? = when {
    draft.sourcePkg.isBlank() || draft.sourcePkg.startsWith("io.github.caozl9021.mymoney.manual") -> null
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
