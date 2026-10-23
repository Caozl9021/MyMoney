package com.example.autobook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.autobook.R
import com.example.autobook.data.LedgerConfig
import com.example.autobook.export.CostCsv
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbMotion
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.sectionHeader
import com.example.autobook.ui.design.CapsuleChip
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.InputRow
import com.example.autobook.ui.design.ListRow
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PrimaryButton
import com.example.autobook.ui.design.rememberAbHaptic
import com.example.autobook.ui.design.SecondaryButton
import com.example.autobook.ui.design.swatchFor
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.util.VelocityTracker
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 确认卡本体（iOS 底部 Sheet 形态）。
 *
 * 四件事：看清金额、点分类（含二级）、点账户、改备注。
 * 分类/账户都来自 [LedgerConfig] 清单，不会出现清单外的名字。
 *
 * **这个 composable 有两个宿主**，保证前后台看到的是同一张卡：
 *  - [ConfirmActivity]：通知点开 / App 自己在前台时启动的透明 Activity；
 *  - [com.example.autobook.overlay.ConfirmOverlay]：识别瞬间直接盖在对方 App 上的悬浮窗
 *    （后台启动 Activity 会被 BAL 拦，悬浮窗不会）。
 * 所以这里只画「压暗层 + 底部卡片」，不碰窗口、不碰数据落库 —— 全都由宿主回调。
 */
@Composable
fun ConfirmSheet(
    draft: BillDraft,
    onSave: (BillDraft) -> Unit,
    onImport: (BillDraft) -> Unit,
    onDiscard: () -> Unit,
    editableDetails: Boolean = false,
    saveLabel: String = "记一笔",
    discardLabel: String = "忽略",
    showImport: Boolean = true,
    saving: Boolean = false,
    errorMessage: String? = null,
    amountVisibility: Boolean = true,
    onAmountBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val reduceMotion = com.example.autobook.ui.design.LocalReduceMotion.current
    var type by rememberSaveable { mutableStateOf(draft.type) }
    var merchant by rememberSaveable { mutableStateOf(draft.merchant.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val groups = remember(type) { LedgerConfig.groupsFor(type) }
    val accounts = remember { LedgerConfig.accounts }

    // 金额一律按 Locale.US 格式化：默认 Locale 的小数点可能是逗号（19,99），
    // 显示出来再 toDoubleOrNull() 会解析失败、静默回退成原值。
    var amountText by rememberSaveable { mutableStateOf(formatForInput(draft.priceCents)) }
    var category by rememberSaveable { mutableStateOf(draft.category) }
    var subCategory by rememberSaveable { mutableStateOf(draft.subCategory) }
    var account by rememberSaveable { mutableStateOf(draft.account) }
    var note by rememberSaveable { mutableStateOf(draft.note.orEmpty()) }

    val subs = remember(category, groups) {
        groups.firstOrNull { it.name == category }?.subs.orEmpty()
    }

    val validAmount = parseAmountCents(amountText)
    fun currentDraft(): BillDraft? {
        val cents = validAmount ?: return null
        return draft.copy(
            priceCents = cents,
            type = type,
            merchant = merchant.trim().ifBlank { null },
            category = category,
            subCategory = subCategory,
            account = account,
            note = note.ifBlank { null },
        )
    }

    // ---- 进出场与下滑关闭 ----
    // 进场：从屏幕外滑入（真挡一下"突然出现"的突兀感）；
    // 下滑：只允许拖抓手（SheetHandle 区域），否则和内部竖滚动抢手势。
    val scope = rememberCoroutineScope()
    val haptic = rememberAbHaptic()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val offsetY = remember { Animatable(if (reduceMotion) 0f else screenHeightPx) }
    val tracker = remember { VelocityTracker() }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) { glow.snapTo(0.12f); glow.animateTo(0f, tween(300)) } else glow.snapTo(0f)
    }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val displayedOffset = if (dragging) dragOffset else offsetY.value

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) offsetY.snapTo(0f) else offsetY.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
    }

    fun closeAnimated() {
        if (saving || submitted) return
        haptic.warn()
        scope.launch {
            offsetY.animateTo(
                targetValue = screenHeightPx,
                animationSpec = tween(durationMillis = if (reduceMotion) 0 else AbMotion.fast),
            )
            onDiscard()
        }
    }

    // 压暗层跟随位移变淡；释放按距离和向下速度判断关闭。
    val scrimAlpha = (0.32f * (1f - (displayedOffset / (screenHeightPx * 0.7f)))).coerceIn(0f, 0.32f)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 压暗层：点一下等于忽略（与 iOS 点卡片外侧关闭 sheet 一致）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { closeAnimated() },
                ),
        )

        Column(
            modifier = Modifier
                .widthIn(max = 560.dp).fillMaxWidth()
                .offset { IntOffset(0, displayedOffset.roundToInt()) }
                .clip(RoundedCornerShape(topStart = AbRadius.sheet, topEnd = AbRadius.sheet))
                .background(p.cardBg)
                .imePadding()
                .navigationBarsPadding()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.92f),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(screenHeightPx, reduceMotion, saving, submitted) {
                        detectVerticalDragGestures(
                            onDragStart = { _ ->
                                dragOffset = offsetY.value
                                dragging = true
                                tracker.resetTracking()
                                scope.launch { offsetY.stop() }
                            },
                            onDragEnd = {
                                val releasedOffset = dragOffset
                                val velocity = tracker.calculateVelocity().y
                                val shouldClose = releasedOffset > with(density) { 120.dp.toPx() } || velocity > 1000f
                                scope.launch {
                                    // Commit the synchronous drag before handing control back to animation.
                                    // No per-event coroutines can overwrite the release animation.
                                    offsetY.snapTo(releasedOffset)
                                    dragging = false
                                    if (shouldClose && !saving && !submitted) {
                                        closeAnimated()
                                    } else if (reduceMotion) {
                                        offsetY.snapTo(0f)
                                    } else {
                                        offsetY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f), initialVelocity = velocity)
                                    }
                                }
                            },
                            onDragCancel = {
                                val cancelledOffset = dragOffset
                                scope.launch {
                                    offsetY.snapTo(cancelledOffset)
                                    dragging = false
                                    if (reduceMotion) offsetY.snapTo(0f) else offsetY.animateTo(0f, spring(stiffness = 600f))
                                }
                            },
                        ) { change, delta ->
                            change.consume()
                            // The handle moves with the sheet: convert its local coordinate back
                            // to a stable screen coordinate for the velocity estimate.
                            tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(change.position.x, change.position.y + dragOffset))
                            dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                        }
                    },
            ) {
                SheetHandle()
            }

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AbSpacing.xl)
                    .padding(bottom = AbSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
            ) {
                // ---- 头部：类型 + 商户 ----
                Row(
                    Modifier.fillMaxWidth().padding(top = AbSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_color),
                        contentDescription = "AutoBook",
                        modifier = Modifier.size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            merchant.ifBlank { typeLabel(type) },
                            style = AbType.headline,
                            color = p.label,
                            maxLines = 1,
                        )
                        Text(
                            buildString {
                                append(typeLabel(type))
                                draft.method?.let { append(" · ").append(it) }
                                append(" · ")
                                append(
                                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(draft.timeMillis)),
                                )
                            },
                            style = AbType.caption1,
                            color = p.secondaryLabel,
                            maxLines = 1,
                        )
                    }
                    Text(
                        discardLabel,
                        style = AbType.body,
                        color = p.secondaryLabel,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (!saving && !submitted) onDiscard() },
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                // ---- 金额（可编辑，大字号） ----
                Row(
                    Modifier.fillMaxWidth().background(p.accent.copy(alpha = glow.value), RoundedCornerShape(16.dp)).padding(vertical = AbSpacing.xs).onGloballyPositioned { onAmountBounds?.invoke(it.boundsInRoot()) }.graphicsLayer { alpha = if (amountVisibility) 1f else 0f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("¥", style = AbType.amountTitle, color = p.secondaryLabel)
                    Box(Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = amountText,
                            onValueChange = {
                                amountText = it
                            },
                            singleLine = true,
                            textStyle = AbType.amountEntry.copy(color = p.label, fontSize = AbType.amountEntry.fontSize / density.fontScale.coerceAtLeast(1f)),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (validAmount == null) {
                    Text("请输入大于0且不超过一百万元的有效金额", color = p.red, style = AbType.caption1)
                }
                if (editableDetails) {
                    SectionLabel("类型")
                    ChipFlow(
                        items = BillType.entries.map(::typeLabel),
                        selected = typeLabel(type),
                        onToggle = { label ->
                            val next = BillType.entries.first { typeLabel(it) == label }
                            if (type != next) { type = next; category = null; subCategory = null }
                            importError = null
                        },
                    )
                    InsetGroup {
                        InputRow(label = "商户", value = merchant, onValueChange = { merchant = it }, placeholder = "可留空")
                    }
                }
                InsetDivider()

                // ---- 分类 ----
                SectionLabel("分类")
                ChipFlow(
                    items = groups.map { it.name },
                    selected = category,
                    onToggle = { name ->
                        category = if (category == name) null else name
                        subCategory = null
                    },
                )

                if (subs.isNotEmpty()) {
                    SectionLabel("二级分类")
                    ChipFlow(
                        items = subs,
                        selected = subCategory,
                        onToggle = { sub -> subCategory = if (subCategory == sub) null else sub },
                        tint = swatchFor(category ?: ""),
                    )
                }

                SectionLabel("账户")
                ChipFlow(
                    items = accounts,
                    selected = account,
                    onToggle = { name -> account = if (account == name) null else name },
                )

                // ---- 备注 ----
                InsetGroup {
                    InputRow(
                        label = "备注",
                        value = note,
                        onValueChange = { note = it },
                        placeholder = "可留空，导出时写备注列",
                    )
                }

                // ---- 抓取元信息 ----
                InsetGroup {
                    ListRow(title = "商户", subtitle = merchant.ifBlank { "—" })
                    InsetDivider()
                    ListRow(
                        title = "支付方式",
                        subtitle = draft.method ?: "—",
                        dividerAbove = true,
                    )
                    if (draft.orderTail != null) {
                        ListRow(
                            title = "单号尾号",
                            subtitle = draft.orderTail,
                            dividerAbove = true,
                        )
                    }
                }

                Spacer(Modifier.height(AbSpacing.xs))

                // ---- 动作 ----
                (errorMessage ?: importError)?.let {
                    Text(it, color = p.red, style = AbType.footnote)
                }
                PrimaryButton(
                    text = if (saving) "保存中…" else saveLabel,
                    enabled = validAmount != null && !saving && !submitted,
                    onClick = {
                        val edited = currentDraft()
                        if (edited != null && !saving && !submitted) {
                            if (!editableDetails) submitted = true
                            haptic.confirm()
                            onSave(edited)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
                ) {
                    if (showImport) SecondaryButton(
                        text = "导入 iCost",
                        onClick = {
                            val edited = currentDraft()
                            when {
                                saving || submitted -> Unit
                                edited == null -> importError = "请输入有效金额后再导入"
                                edited.type == BillType.REFUND -> importError = CostCsv.REFUND_UNSUPPORTED
                                else -> {
                                    haptic.select()
                                    try {
                                        onImport(edited)
                                        submitted = true
                                    } catch (error: IllegalArgumentException) {
                                        importError = error.message ?: "无法导入，请检查账单"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = discardLabel,
                        onClick = { closeAnimated() },
                        modifier = Modifier.weight(1f),
                        tint = p.red,
                    )
                }
            }
        }
    }
}

/** 顶部拖拽指示条（4×36 的浅灰胶囊）。 */
@Composable
private fun SheetHandle() {
    val p = LocalAbPalette.current
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(p.tertiaryLabel.copy(alpha = 0.5f)),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val p = LocalAbPalette.current
    Text(
        text,
        style = AbType.sectionHeader,
        color = p.secondaryLabel,
        modifier = Modifier.padding(top = AbSpacing.xs),
    )
}

/** chip 自动换行网格（不用 LazyRow：确认卡里要一屏看全，横滑会藏选项）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(
    items: List<String>,
    selected: String?,
    onToggle: (String) -> Unit,
    tint: Color? = null,
) {
    if (items.isEmpty()) {
        val p = LocalAbPalette.current
        Text(
            "清单是空的，去「设置 → 分类与账户」添加",
            style = AbType.caption1,
            color = p.tertiaryLabel,
        )
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        items.forEach { name ->
            CapsuleChip(
                text = name,
                selected = selected == name,
                onClick = { onToggle(name) },
                tint = tint,
            )
        }
    }
}

private fun typeLabel(type: BillType): String = when (type) {
    BillType.EXPENSE -> "支出"
    BillType.INCOME -> "收入"
    BillType.TRANSFER -> "转账"
    BillType.REFUND -> "退款"
}
