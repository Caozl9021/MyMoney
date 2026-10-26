package com.example.autobook.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 玻璃
// ---------------------------------------------------------------------------

/**
 * 拟态 Liquid Glass。
 *
 * 技术上不是真模糊（Compose 的 blur 在 API<31 无效、且只能模糊自身内容），
 * 而是「半透明底色 + 上亮下暗描边 + 极淡投影」的视觉等效：
 * 底下内容自然透出来，边缘有一圈高光，这是玻璃感的关键。
 *
 * 打开「降低透明度」后退化为不透明卡片底色（无障碍 / 低端机性能）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AbRadius.group),
    tint: Color? = null,
    borderWidth: Dp = 0.75.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    // 固定取值（v0.5.0 定下来的观感）：底色直接落在卡片底和玻璃色调中间，
    // 靠高光 + 描边 + 阴影撑质感。不做运行时强度插值 —— 见 LiquidGlassTabBar 的注释。
    val bg = if (reduce) p.cardBg else lerp(p.cardBg, tint ?: p.glassTint, 0.62f)
    Box(
        modifier = modifier
            .shadow(
                elevation = if (reduce) 0.dp else 18.dp,
                shape = shape,
                ambientColor = p.glassShadow,
                spotColor = p.glassShadow,
            )
            .clip(shape)
            .background(bg)
            .then(
                if (reduce) {
                    Modifier.border(borderWidth, p.separator, shape)
                } else {
                    Modifier.border(
                        width = borderWidth,
                        brush = Brush.verticalGradient(listOf(p.glassStrokeTop, p.glassStrokeBottom)),
                        shape = shape,
                    )
                },
            )
            // iOS 27 的两处修正：顶部镜面高光（只占上方 40%，不是整块泛白）+ 底边压暗，
            // 用来在复杂壁纸/深色背景上把玻璃的层级"压"出来。
            .then(
                if (reduce) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val h = size.height
                    // 镜面高光
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                p.glassHighlight.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = h * 0.40f,
                        ),
                        size = androidx.compose.ui.geometry.Size(size.width, h * 0.40f),
                    )
                    // 底边暗线（1dp）：让卡片和后面的内容分开
                    drawRect(
                        color = Color.Black.copy(alpha = 0.16f),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, h - 1f),
                        size = androidx.compose.ui.geometry.Size(size.width, 1f),
                    )
                },
            ),
        content = content,
    )
}

/** 圆形玻璃按钮（页面右上角那种）。 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = AbSize.iconMd,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    GlassSurface(
        modifier = modifier.size(size.coerceAtLeast(48.dp)),
        shape = CircleShape,
        content = {
            Box(
                Modifier
                    .size(size.coerceAtLeast(48.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptic.select()
                        onClick()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription, Modifier.size(iconSize), tint = p.label)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 分组卡（inset grouped）
// ---------------------------------------------------------------------------

/** 分组标题：13sp 次级色，左侧与卡片文字对齐。 */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    val p = LocalAbPalette.current
    Text(
        text = text,
        style = AbType.sectionHeader,
        color = p.secondaryLabel,
        modifier = modifier.padding(start = AbSpacing.xs, bottom = 7.dp, top = AbSpacing.xs),
    )
}

/** 分组脚注说明。 */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) {
    val p = LocalAbPalette.current
    Text(
        text = text,
        style = AbType.caption1,
        color = p.secondaryLabel,
        modifier = modifier.padding(start = AbSpacing.xs, end = AbSpacing.xs, top = 6.dp),
    )
}

/**
 * 一组行组成的一张圆角卡。
 * 行之间由 [ListRow] 自己画分隔线，分隔线左侧留出缩进 —— 这是 iOS 的标志性细节。
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalAbPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AbRadius.group))
            .background(p.cardBg),
        content = content,
    )
}

/** 组内分隔线，带左缩进。 */
@Composable
fun InsetDivider(inset: Dp = AbSize.separatorInset) {
    val p = LocalAbPalette.current
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 0.5.dp,
        color = p.separator,
    )
}

/**
 * 一行。三种形态；都保证最小触控高度。
 *
 * @param leading   左侧图标（可选）
 * @param title     主文字
 * @param subtitle  副文字（可选，15sp 次级色）
 * @param trailing  右侧自定义内容（金额、开关…）
 * @param chevron   是否显示 >
 * @param dividerAbove 是否在本行上方画分隔线（组内非首行传 true）
 * @param destructive 主文字用红色（删除类操作）
 */
@Composable
fun ListRow(
    title: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    dividerAbove: Boolean = false,
    destructive: Boolean = false,
    titleStyle: TextStyle = AbType.body,
    /** 副标题行数上限。默认不限；右侧徽标较宽的行需要收成一行，否则长包名会从中间折断。 */
    subtitleMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) InsetDivider(inset = if (leading != null) 62.dp else AbSize.separatorInset)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AbSize.minTouch)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptic.select()
                            onClick()
                        }
                    } else Modifier,
                )
                .padding(horizontal = AbSpacing.l, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = titleStyle,
                        color = if (destructive) p.red else p.label,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = AbType.footnote,
                        color = p.secondaryLabel,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            if (chevron) {
                Icon(
                    imageVector = AbIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = p.tertiaryLabel,
                )
            }
        }
    }
}

/** iOS 观感的开关：开=品牌绿，关=浅灰，滑块永远白色。 */
@Composable
fun AbSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalAbPalette.current
    val position by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(if (LocalReduceMotion.current) 0 else AbMotion.fast),
        label = "switchPosition",
    )
    Canvas(
        Modifier.size(width = 52.dp, height = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        val trackHeight = 30.dp.toPx()
        val top = (size.height - trackHeight) / 2f
        drawRoundRect(
            color = if (checked) p.accent else p.barTrack,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
        )
        val radius = 12.dp.toPx()
        val inset = 3.dp.toPx() + radius
        val center = androidx.compose.ui.geometry.Offset(inset + (size.width - inset * 2f) * position, size.height / 2f)
        drawCircle(Color.White, radius, center)
        if (checked) drawCircle(p.accent.copy(alpha = 0.5f), radius, center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------
// 分段控件
// ---------------------------------------------------------------------------

/**
 * iOS 分段控件：胶囊底槽 + 一枚会横向滑动的白色滑块。
 *
 * 滑块是**同一个** Box 在选项之间平移，不是每个选项各自淡入淡出；
 * 圆角与底槽一致（capsule），避免滑块看起来像贴在方形槽里的圆角方块。
 * 减少动效时动画时长归零且不画投影。
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalAbPalette.current
    val reduceMotion = LocalReduceMotion.current
    val count = options.size.coerceAtLeast(1)
    val target = selected.coerceIn(0, count - 1)
    val position by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else AbMotion.selection,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "segment-position",
    )
    val gap = 2.dp
    val shape = RoundedCornerShape(AbRadius.capsule)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.fill)
            .padding(2.dp),
    ) {
        val cellWidth = (maxWidth - gap * (count - 1)) / count
        // 滑块层与内容同大小：先让 Row 决定高度，再按最终高度铺满。
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset(x = (cellWidth + gap) * position)
                    .width(cellWidth)
                    .fillMaxHeight()
                    .then(if (reduceMotion) Modifier else Modifier.shadow(2.dp, shape))
                    .clip(shape)
                    .background(p.cardBg),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { index, label ->
                val active = index == target
                val fg by animateColorAsState(
                    if (active) p.label else p.secondaryLabel,
                    animationSpec = tween(if (reduceMotion) 0 else AbMotion.selection), label = "seg-fg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.Tab,
                        ) { onSelect(index) }
                        .semantics { this.selected = active; stateDescription = if (active) "已选中" else "未选中" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = AbType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = fg,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

/** 主按钮：按下只反馈触感，成功反馈必须由实际操作结果触发。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "处理中…",
) {
    ActionButton(text, onClick, modifier, enabled, busy, busyLabel, primary = true)
}

/** 次要按钮：透明底 + 细描边，共用按压、忙碌与读屏语义。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "处理中…",
) {
    ActionButton(text, onClick, modifier, enabled, busy, busyLabel, primary = false, tint = tint)
}

/** 危险操作使用次要层级；提交、失败与撤销仍由调用方决定。 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "处理中…",
) {
    val p = LocalAbPalette.current
    ActionButton(text, onClick, modifier, enabled, busy, busyLabel, primary = false, tint = p.red)
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    busy: Boolean,
    busyLabel: String,
    primary: Boolean,
    tint: Color? = null,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val canClick = enabled && !busy
    val reduceMotion = LocalReduceMotion.current
    val animatedScale by animateFloatAsState(
        if (pressed && canClick) AbMotion.pressedScale else 1f,
        animationSpec = tween(if (reduceMotion) 0 else AbMotion.press), label = "button-press",
    )
    val shape = RoundedCornerShape(AbRadius.capsule)
    Box(
        modifier = modifier.heightIn(min = 50.dp)
            .scale(if (reduceMotion) 1f else animatedScale)
            .clip(shape)
            .background(if (primary) { if (canClick || busy) p.accent else p.fill } else Color.Transparent)
            .then(if (primary) Modifier else Modifier.border(1.dp, p.separator, shape))
            .clickable(enabled = canClick, role = Role.Button, interactionSource = interaction, indication = null) {
                haptic.select()
                onClick()
            }
            .semantics {
                stateDescription = when { busy -> busyLabel; !enabled -> "不可用"; else -> "可用" }
                if (busy) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    liveRegion = LiveRegionMode.Polite
                }
            }
            .padding(horizontal = AbSpacing.m, vertical = AbSpacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) busyLabel else text,
            style = AbType.headline,
            color = when {
                primary && (canClick || busy) -> p.onAccent
                !canClick -> p.secondaryLabel
                else -> tint ?: p.label
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// 图标徽章
// ---------------------------------------------------------------------------

/** 分类图标：40dp 圆角方块，底色是分类色的 14% tint。 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size >= 40.dp) 12.dp else 9.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(size * 0.52f), tint = tint)
    }
}

/** 空状态：图标 + 标题 + 说明 + 可选按钮，整体居中。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val p = LocalAbPalette.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(p.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = p.secondaryLabel)
        }
        Text(title, style = AbType.headline, color = p.label)
        Text(
            message,
            style = AbType.footnote,
            color = p.secondaryLabel,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AbSpacing.xs))
            PrimaryButton(actionLabel, onAction, Modifier.width(180.dp))
        }
    }
}

/** 横向滚动的一组胶囊（账户 / 二级分类）。 */
@Composable
fun CapsuleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val p = LocalAbPalette.current
    val c = tint ?: p.accent
    val bg by animateColorAsState(if (selected) c.copy(alpha = 0.16f) else p.fill, animationSpec = tween(if (LocalReduceMotion.current) 0 else AbMotion.selection), label = "chip-bg")
    val fg = p.label
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(AbRadius.capsule))
            .background(bg)
            .border(1.dp, if (selected) c.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(AbRadius.capsule))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { this.selected = selected; role = Role.Tab; stateDescription = if (selected) "已选中" else "未选中" }
            .padding(horizontal = 14.dp, vertical = AbSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
            if (selected) Icon(AbIcons.Check, null, Modifier.size(14.dp), tint = fg)
            Text(text, style = AbType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = fg)
        }
    }
}

/** 非交互状态标签：文字始终表达状态，不以颜色作为唯一线索。 */
@Composable
fun StatusLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasized: Boolean = false,
) {
    val p = LocalAbPalette.current
    val shape = RoundedCornerShape(AbRadius.chip)
    Row(
        modifier = modifier.clip(shape)
            .background(if (emphasized) p.accent.copy(alpha = 0.12f) else p.fill)
            .border(0.75.dp, if (emphasized) p.accent.copy(alpha = 0.4f) else p.separator, shape)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = AbSpacing.s, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = p.label)
        Text(text, style = AbType.caption1, color = p.label)
    }
}

/** 就近说明/错误。静态呈现；只对操作结果或读取失败启用温和播报。 */
@Composable
fun InlineMessage(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    announce: Boolean = false,
) {
    val p = LocalAbPalette.current
    Row(
        modifier = modifier.fillMaxWidth()
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        // 错误除颜色外还有明确文字前缀；不用循环/入场动画打扰长列表。
        Text(if (isError) "错误" else "说明", style = AbType.caption1,
            color = if (isError) p.red else p.secondaryLabel)
        Text(text, style = AbType.footnote, color = if (isError) p.red else p.secondaryLabel,
            modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// 输入
// ---------------------------------------------------------------------------

/**
 * 行内输入框：无边框、透明底，靠所在分组卡的底色承托 —— iOS 设置里的输入框就是这样。
 *
 * 不用 M3 的 OutlinedTextField：它的描边和浮动 label 与这套视觉冲突。
 */
@Composable
fun AbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    imeAction: androidx.compose.ui.text.input.ImeAction =
        androidx.compose.ui.text.input.ImeAction.Done,
    onDone: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = AbType.body.copy(color = p.label),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { onDone?.invoke() },
        ),
        modifier = modifier.heightIn(min = 26.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = AbType.body, color = p.tertiaryLabel)
                }
                inner()
            }
        },
    )
}

/** 带左边标题的输入行，直接放进 InsetGroup。 */
@Composable
fun InputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    dividerAbove: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) InsetDivider()
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = AbSize.minTouch)
                .padding(horizontal = AbSpacing.l, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            Text(label, style = AbType.body, color = p.label)
            AbTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                onDone = onDone,
            )
        }
    }
}
