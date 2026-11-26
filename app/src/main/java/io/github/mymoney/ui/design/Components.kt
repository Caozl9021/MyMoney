package io.github.mymoney.ui.design

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val reduce = !LocalLiquidGlassEnabled.current || LocalReduceTransparency.current
    val isDark = p.pageBg.luminance() < 0.1f
    val clarity = LocalLiquidGlassClaritySpec.current.header
    // 固定取值（v0.5.0 定下来的观感）并随通透度微调：底色落在卡片底和玻璃色调中间，靠高光 + 描边 + 阴影撑质感。
    val mixed = lerp(p.cardBg, tint ?: p.glassTint, 0.62f)
    val bg = if (reduce) p.cardBg else mixed.copy(
        alpha = glassSurfaceAlpha(
            baseLight = mixed.alpha.coerceIn(0.72f, 0.86f),
            baseDark = mixed.alpha.coerceIn(0.72f, 0.86f),
            isDark = isDark,
            clarity = clarity,
        ),
    )
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
                        brush = liquidSpecularRimBrush(isDark),
                        shape = shape,
                    )
                },
            )
            .then(
                if (reduce) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val h = size.height
                    // 顶部 40% 柔和镜面高光（移除旧底部 1px 直黑线，保持圆角弧度洁净）
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                p.glassHighlight.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = h * 0.40f,
                        ),
                        size = Size(size.width, h * 0.40f),
                    )
                },
            ),
        content = content,
    )
}

/** 圆形玻璃按钮（统一复用 [GlassCircleIconButton]）。 */
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
    GlassCircleIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = p.label,
        visualSize = size,
        iconSize = iconSize,
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
        modifier = modifier.layoutId(SectionRole.HEADER).padding(start = AbSpacing.m, end = AbSpacing.m),
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
        modifier = modifier.layoutId(SectionRole.FOOTER).padding(start = AbSpacing.m, end = AbSpacing.m),
    )
}

/**
 * 一组行组成的一张圆角卡。
 * 行之间由 [ListRow] 自己画分隔线，分隔线左侧留出缩进 —— 这是 iOS 的标志性细节。
 * 当处于模态弹窗或底部 Sheet 内（[LocalInModalSurface] 为 true）时自动切换为微凹对比槽底，避免同色叠同色。
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalAbPalette.current
    val inModal = LocalInModalSurface.current
    val isDark = p.pageBg.luminance() < 0.1f
    val shape = RoundedCornerShape(if (inModal) 16.dp else AbRadius.group)
    val bg = if (inModal) {
        if (isDark) Color(0x18FFFFFF) else Color(0x0E767680)
    } else {
        p.cardBg
    }
    Column(
        modifier = modifier
            .layoutId(SectionRole.CARD)
            .fillMaxWidth()
            .clip(shape)
            .background(bg),
        content = content,
    )
}

/** 组内分隔线，左右留出缩进（左侧对齐文字，右侧不顶到卡片边缘）。 */
@Composable
fun InsetDivider(
    inset: Dp = AbSize.separatorInset,
    endInset: Dp = inset.coerceAtMost(AbSize.separatorInset),
) {
    val p = LocalAbPalette.current
    HorizontalDivider(
        modifier = Modifier.padding(start = inset, end = endInset),
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
    val rowInteraction = remember(onClick != null) { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) InsetDivider(inset = if (leading != null) AbCardDensity.leadingInset else AbSize.separatorInset)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AbSize.minTouch)
                .then(
                    if (onClick != null) {
                        Modifier
                            .abPressFeedback(
                                interactionSource = rowInteraction,
                                pressedScale = 0.992f,
                                pressedAlpha = 0.76f,
                            )
                            .clickable(
                                interactionSource = rowInteraction,
                                indication = null,
                            ) {
                                haptic.select()
                                onClick()
                            }
                    } else Modifier,
                )
                .padding(horizontal = AbSpacing.l, vertical = AbCardDensity.rowVertical),
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

/** 液态玻璃开关：开=品牌绿，关=浅灰，支持点击与 1:1 跟手横滑，静止零离屏录制。 */
@Composable
fun AbSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    SwitchCanvas(checked, compact = false, contentDescription = null, onCheckedChange = onCheckedChange)

@Composable
fun AbSwitch(checked: Boolean, contentDescription: String?, onCheckedChange: (Boolean) -> Unit) =
    SwitchCanvas(checked, compact = false, contentDescription = contentDescription, onCheckedChange = onCheckedChange)

@Composable
fun CompactAbSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    SwitchCanvas(checked, compact = true, contentDescription = null, onCheckedChange = onCheckedChange)

@Composable
fun CompactAbSwitch(checked: Boolean, contentDescription: String?, onCheckedChange: (Boolean) -> Unit) =
    SwitchCanvas(checked, compact = true, contentDescription = contentDescription, onCheckedChange = onCheckedChange)

@Composable
private fun SwitchCanvas(
    checked: Boolean,
    compact: Boolean,
    contentDescription: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val isDark = p.pageBg.luminance() < 0.1f
    val density = LocalDensity.current.density

    val position = remember { Animatable(if (checked) 1f else 0f) }
    val lift = remember { Animatable(0f) }
    val lens = remember { Animatable(0f) }
    val previousChecked = remember { booleanArrayOf(checked) }
    val isDragging = remember { booleanArrayOf(false) }
    val committedFromDrag = remember { booleanArrayOf(false) }
    val releaseVel = remember { floatArrayOf(0f) }
    val scope = rememberCoroutineScope()

    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentEffectiveGlass by rememberUpdatedState(effectiveGlass)

    LaunchedEffect(checked, reduceMotion, effectiveGlass) {
        val changed = previousChecked[0] != checked
        previousChecked[0] = checked
        val fromDrag = committedFromDrag[0]
        val vel = releaseVel[0]
        committedFromDrag[0] = false
        releaseVel[0] = 0f
        val target = if (checked) 1f else 0f

        if (reduceMotion) {
            position.snapTo(target)
            lift.snapTo(0f)
            lens.snapTo(0f)
        } else if (!effectiveGlass) {
            lift.snapTo(0f)
            lens.snapTo(0f)
            position.animateTo(target, tween(AbMotion.fast))
        } else if (fromDrag || changed) {
            coroutineScope {
                launch {
                    position.animateTo(
                        targetValue = target,
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = if (fromDrag) 520f else 580f,
                        ),
                        initialVelocity = if (fromDrag) vel else position.velocity,
                    )
                }
                launch {
                    lift.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 520f))
                }
                launch {
                    lens.animateTo(1f, spring(dampingRatio = 0.94f, stiffness = 460f))
                }
                launch {
                    delay(130)
                    withTimeoutOrNull(110) {
                        androidx.compose.runtime.snapshotFlow { abs(position.value - target) to abs(position.velocity) }
                            .first { (dist, v) -> dist <= 0.08f && v <= 1.8f }
                    }
                    launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                    launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                }
            }
        } else if (!isDragging[0]) {
            launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
            launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
        }
    }

    val boxWidth = if (compact) 48.dp else 60.dp
    val boxHeight = 48.dp
    val trackWidthDp = if (compact) 42.dp else 56.dp
    val trackHeightDp = if (compact) 22.dp else 28.dp
    val pillWidthDp = if (compact) 26.dp else 34.dp
    val pillHeightDp = if (compact) 18.dp else 22.dp
    val bleedDp = if (compact) 6.dp else 8.dp
    val useApi33Lens = effectiveGlass && !reduceMotion && Build.VERSION.SDK_INT >= 33
    val trackLayer = rememberGraphicsLayer()

    Box(
        modifier = Modifier
            .size(width = boxWidth, height = boxHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (contentDescription != null) this.contentDescription = contentDescription
                onClick {
                    onCheckedChange(!checked)
                    true
                }
            }
            .pointerInput(compact) {
                val trackW = trackWidthDp.toPx()
                val pillW = pillWidthDp.toPx()
                val insetPx = ((trackHeightDp.toPx() - pillHeightDp.toPx()) * 0.5f).coerceAtLeast(0f)
                val travelPx = (trackW - insetPx * 2f - pillW).coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val startPos = position.value
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, downPos) }
                    var dragging = false
                    var cancelled = false
                    var lastHalf = startPos >= 0.5f
                    var lastPointer = downPos
                    val slop = viewConfiguration.touchSlop * 0.6f

                    if (currentEffectiveGlass && !currentReduceMotion) {
                        scope.launch { lift.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 480f)) }
                        scope.launch { lens.animateTo(1f, spring(dampingRatio = 0.95f, stiffness = 420f)) }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        lastPointer = change.position
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.isConsumed) {
                            cancelled = true
                            break
                        }
                        val dx = change.position.x - downPos.x
                        val dy = change.position.y - downPos.y
                        if (!dragging && abs(dx) > slop && abs(dx) >= abs(dy) * 0.9f) {
                            dragging = true
                            isDragging[0] = true
                        } else if (!dragging && abs(dy) > slop && abs(dy) > abs(dx) * 1.15f) {
                            cancelled = true
                            break
                        }
                        if (dragging) {
                            change.consume()
                            val nextPos = rubberBandSwitchPosition(startPos + dx / travelPx)
                            val half = nextPos >= 0.5f
                            if (half != lastHalf) {
                                lastHalf = half
                                haptic.tick()
                            }
                            scope.launch { position.snapTo(nextPos) }
                        }
                    }

                    isDragging[0] = false
                    if (cancelled) {
                        val target = if (currentChecked) 1f else 0f
                        if (currentReduceMotion) {
                            scope.launch {
                                position.snapTo(target)
                                lift.snapTo(0f)
                                lens.snapTo(0f)
                            }
                        } else {
                            scope.launch { position.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 520f)) }
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                            scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                        }
                    } else if (dragging) {
                        val velPerSec = (tracker.calculateVelocity().x / travelPx).coerceIn(-14f, 14f)
                        val targetBool = resolveSwitchTarget(position.value, velPerSec)
                        if (targetBool != currentChecked) {
                            committedFromDrag[0] = true
                            releaseVel[0] = velPerSec
                            currentOnCheckedChange(targetBool)
                        } else {
                            val target = if (targetBool) 1f else 0f
                            if (currentReduceMotion) {
                                scope.launch {
                                    position.snapTo(target)
                                    lift.snapTo(0f)
                                    lens.snapTo(0f)
                                }
                            } else {
                                scope.launch {
                                    position.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 520f), initialVelocity = velPerSec)
                                }
                                scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                                scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                            }
                        }
                    } else {
                        val inBounds = lastPointer.x in (-8.dp.toPx())..(size.width + 8.dp.toPx()) &&
                            lastPointer.y in (-8.dp.toPx())..(size.height + 8.dp.toPx())
                        if (inBounds) {
                            haptic.tick()
                            currentOnCheckedChange(!currentChecked)
                        } else if (!currentReduceMotion) {
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                            scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val bleedPx = bleedDp.roundToPx()
                    val placeable = measurable.measure(
                        Constraints.fixed(constraints.maxWidth + bleedPx * 2, constraints.maxHeight + bleedPx * 2),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(-bleedPx, -bleedPx)
                    }
                },
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val frame = computeSwitchPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            trackWidth = trackWidthDp.toPx(),
                            trackHeight = trackHeightDp.toPx(),
                            pillWidth = pillWidthDp.toPx(),
                            pillHeight = pillHeightDp.toPx(),
                            position = if (reduceMotion) (if (checked) 1f else 0f) else position.value,
                            velocityPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                        )
                        if (useApi33Lens && frame.lensMix > 0.005f) {
                            trackLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(trackLayer)
                        } else {
                            drawContent()
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val trackW = trackWidthDp.toPx()
                    val trackH = trackHeightDp.toPx()
                    val trackLeft = (size.width - trackW) * 0.5f
                    val trackTop = (size.height - trackH) * 0.5f
                    val pos = (if (reduceMotion) (if (checked) 1f else 0f) else position.value).coerceIn(0f, 1f)
                    val trackColor = lerp(p.barTrack, p.accent, pos)
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(trackLeft, trackTop),
                        size = Size(trackW, trackH),
                        cornerRadius = CornerRadius(trackH * 0.5f),
                    )
                    if (effectiveGlass) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = if (isDark) 0.14f else 0.06f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (isDark) 0.06f else 0.16f),
                                ),
                                startY = trackTop,
                                endY = trackTop + trackH,
                            ),
                            topLeft = Offset(trackLeft, trackTop),
                            size = Size(trackW, trackH),
                            cornerRadius = CornerRadius(trackH * 0.5f),
                        )
                    }
                }
            }

            if (useApi33Lens) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val frame = computeSwitchPillFrame(
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                trackWidth = trackWidthDp.toPx(),
                                trackHeight = trackHeightDp.toPx(),
                                pillWidth = pillWidthDp.toPx(),
                                pillHeight = pillHeightDp.toPx(),
                                position = position.value,
                                velocityPerSec = position.velocity,
                                lift = lift.value,
                                lens = lens.value,
                                isDark = isDark,
                            )
                            renderEffect = if (frame.lensMix > 0.005f && Build.VERSION.SDK_INT >= 33) {
                                ControlLensApi33.createEffect(
                                    canvasWidth = size.width,
                                    canvasHeight = size.height,
                                    frame = frame,
                                    density = density,
                                    isDark = isDark,
                                    refractionBandDp = if (compact) 7f else 9f,
                                    refractionAmountDp = if (compact) 9f else 11f,
                                )
                            } else null
                        }
                        .drawBehind {
                            val frame = computeSwitchPillFrame(
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                trackWidth = trackWidthDp.toPx(),
                                trackHeight = trackHeightDp.toPx(),
                                pillWidth = pillWidthDp.toPx(),
                                pillHeight = pillHeightDp.toPx(),
                                position = position.value,
                                velocityPerSec = position.velocity,
                                lift = lift.value,
                                lens = lens.value,
                                isDark = isDark,
                            )
                            if (frame.lensMix > 0.005f) {
                                drawLayer(trackLayer)
                            }
                        },
                )
            }

            Canvas(Modifier.fillMaxSize()) {
                val frame = computeSwitchPillFrame(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    trackWidth = trackWidthDp.toPx(),
                    trackHeight = trackHeightDp.toPx(),
                    pillWidth = pillWidthDp.toPx(),
                    pillHeight = pillHeightDp.toPx(),
                    position = if (reduceMotion) (if (checked) 1f else 0f) else position.value,
                    velocityPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                    lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                    lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                    isDark = isDark,
                )
                val restingAlpha = if (useApi33Lens) {
                    1f - smoothstep01(frame.lensMix)
                } else {
                    if (effectiveGlass) frame.fillAlpha else 1f
                }
                if (restingAlpha > 0.005f) {
                    val corner = CornerRadius(frame.height * 0.5f)
                    val topLeft = Offset(frame.left, frame.top)
                    val pillSize = Size(frame.width, frame.height)
                    if (frame.shadowAlpha > 0.005f && !reduceMotion) {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = frame.shadowAlpha * restingAlpha),
                            topLeft = Offset(frame.left, frame.top + 1.dp.toPx()),
                            size = pillSize,
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(
                        color = Color.White.copy(alpha = restingAlpha),
                        topLeft = topLeft,
                        size = pillSize,
                        cornerRadius = corner,
                    )
                    if (effectiveGlass) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.55f * restingAlpha),
                                    Color.Black.copy(alpha = 0.04f * restingAlpha),
                                ),
                                startY = frame.top,
                                endY = frame.top + frame.height,
                            ),
                            topLeft = topLeft,
                            size = pillSize,
                            cornerRadius = corner,
                        )
                        val knobStroke = 0.5.dp.toPx()
                        drawRoundRect(
                            color = Color.Black.copy(alpha = (if (isDark) 0.16f else 0.06f) * restingAlpha),
                            topLeft = Offset(frame.left + knobStroke * 0.5f, frame.top + knobStroke * 0.5f),
                            size = Size((frame.width - knobStroke).coerceAtLeast(1f), (frame.height - knobStroke).coerceAtLeast(1f)),
                            cornerRadius = corner,
                            style = Stroke(knobStroke),
                        )
                    }
                    if (!useApi33Lens && effectiveGlass && frame.rimAlpha > 0.005f) {
                        val stroke = 0.85.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = frame.rimAlpha),
                            topLeft = Offset(frame.left + stroke * 0.5f, frame.top + stroke * 0.5f),
                            size = Size((frame.width - stroke).coerceAtLeast(1f), (frame.height - stroke).coerceAtLeast(1f)),
                            cornerRadius = corner,
                            style = Stroke(stroke),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分段控件
// ---------------------------------------------------------------------------

/**
 * 液态玻璃分段控件：胶囊底槽 + 柔光玻璃白囊，按压/横滑/切换时液化为 AGSL 凸镜水滴并实时折射文字。
 * 支持点击与 1:1 跟手横滑；关闭全局液态玻璃或降低透明度时退化为清爽高对比实底滑块。
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val isDark = p.pageBg.luminance() < 0.1f
    val density = LocalDensity.current.density
    val count = options.size.coerceAtLeast(1)
    val target = selected.coerceIn(0, count - 1)

    val position = remember { Animatable(target.toFloat()) }
    val lift = remember { Animatable(0f) }
    val liftY = remember { Animatable(0f) }
    val lens = remember { Animatable(0f) }
    val dragSqueeze = remember { Animatable(0f) }
    val previousSelection = remember { intArrayOf(target) }
    val isDragging = remember { booleanArrayOf(false) }
    val committedFromDrag = remember { booleanArrayOf(false) }
    val releaseVel = remember { floatArrayOf(0f) }
    var scrubbingIdx by remember { mutableIntStateOf(-1) }
    val visualActive = if (scrubbingIdx >= 0) scrubbingIdx else target
    val scope = rememberCoroutineScope()

    val currentTarget by rememberUpdatedState(target)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentEffectiveGlass by rememberUpdatedState(effectiveGlass)

    LaunchedEffect(target, reduceMotion, effectiveGlass) {
        val changed = previousSelection[0] != target
        previousSelection[0] = target
        val fromDrag = committedFromDrag[0]
        val vel = releaseVel[0]
        committedFromDrag[0] = false
        releaseVel[0] = 0f

        if (reduceMotion) {
            position.snapTo(target.toFloat())
            lift.snapTo(0f)
            liftY.snapTo(0f)
            lens.snapTo(0f)
            dragSqueeze.snapTo(0f)
        } else if (!effectiveGlass) {
            lift.snapTo(0f)
            liftY.snapTo(0f)
            lens.snapTo(0f)
            dragSqueeze.snapTo(0f)
            position.animateTo(target.toFloat(), tween(AbMotion.selection, easing = AbEasing.EaseOut))
        } else if (fromDrag || changed) {
            val targetPos = target.toFloat()
            coroutineScope {
                launch {
                    position.animateTo(
                        targetValue = targetPos,
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = if (fromDrag) 520f else 620f,
                        ),
                        initialVelocity = if (fromDrag) vel else position.velocity,
                    )
                }
                launch { lift.animateTo(1f, spring(dampingRatio = 0.64f, stiffness = 460f)) }
                launch { liftY.animateTo(1f, spring(dampingRatio = 0.76f, stiffness = 520f)) }
                launch { lens.animateTo(1f, spring(dampingRatio = 0.94f, stiffness = 480f)) }
                launch { dragSqueeze.animateTo(0f, spring(dampingRatio = 0.44f, stiffness = 220f)) }
                launch {
                    delay(110)
                    withTimeoutOrNull(120) {
                        androidx.compose.runtime.snapshotFlow { abs(position.value - targetPos) to abs(position.velocity) }
                            .first { (dist, v) -> dist <= 0.12f && v <= 2.2f }
                    }
                    launch { lift.animateTo(0f, spring(dampingRatio = 0.60f, stiffness = 210f)) }
                    launch { liftY.animateTo(0f, spring(dampingRatio = 0.74f, stiffness = 260f)) }
                    launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 135f)) }
                }
            }
        } else if (!isDragging[0]) {
            launch { lift.animateTo(0f, spring(dampingRatio = 0.60f, stiffness = 220f)) }
            launch { liftY.animateTo(0f, spring(dampingRatio = 0.74f, stiffness = 270f)) }
            launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
            launch { dragSqueeze.animateTo(0f, spring(dampingRatio = 0.44f, stiffness = 220f)) }
        }
    }

    val padDp = 2.dp
    val gapDp = 2.dp
    val bleedDp = if (effectiveGlass && !reduceMotion) (if (compact) 6.dp else 8.dp) else 0.dp
    val shape = RoundedCornerShape(AbRadius.capsule)
    val minHeight = if (compact) 32.dp else 48.dp
    val useApi33Lens = effectiveGlass && !reduceMotion && Build.VERSION.SDK_INT >= 33
    val contentLayer = rememberGraphicsLayer()
    val outsideClipPath = remember { Path() }
    val insideClipPath = remember { Path() }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val bleedPx = bleedDp.roundToPx()
                    val extra = bleedPx * 2
                    val childConstraints = constraints.copy(
                        minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
                        maxWidth = if (constraints.maxWidth == Constraints.Infinity) Constraints.Infinity else constraints.maxWidth + extra,
                        minHeight = (constraints.minHeight + extra).coerceAtLeast(0),
                        maxHeight = if (constraints.maxHeight == Constraints.Infinity) Constraints.Infinity else constraints.maxHeight + extra,
                    )
                    val placeable = measurable.measure(childConstraints)
                    layout((placeable.width - extra).coerceAtLeast(0), (placeable.height - extra).coerceAtLeast(0)) {
                        placeable.place(-bleedPx, -bleedPx)
                    }
                },
        ) {
            // 仅录制轨道、静止囊与文字，不包含下方的透镜叠加层，避免 RenderNode 自引用递归。
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val frame = computeSegmentPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            bleedPx = bleedDp.toPx(),
                            paddingPx = padDp.toPx(),
                            gapPx = gapDp.toPx(),
                            count = count,
                            position = if (reduceMotion) target.toFloat() else position.value,
                            velocitySegmentsPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                            dragSqueeze = if (reduceMotion || !effectiveGlass) 0f else dragSqueeze.value,
                            liftY = if (reduceMotion || !effectiveGlass) 0f else liftY.value,
                        )
                        if (useApi33Lens && frame.lensMix > 0.005f) {
                            contentLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(contentLayer)
                        } else {
                            drawContent()
                        }
                    }
                    .drawBehind {
                        val frame = computeSegmentPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            bleedPx = bleedDp.toPx(),
                            paddingPx = padDp.toPx(),
                            gapPx = gapDp.toPx(),
                            count = count,
                            position = if (reduceMotion) target.toFloat() else position.value,
                            velocitySegmentsPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                            dragSqueeze = if (reduceMotion || !effectiveGlass) 0f else dragSqueeze.value,
                            liftY = if (reduceMotion || !effectiveGlass) 0f else liftY.value,
                        )
                        val bleedPx = bleedDp.toPx()
                        val trackW = (size.width - bleedPx * 2f).coerceAtLeast(1f)
                        val trackH = (size.height - bleedPx * 2f).coerceAtLeast(1f)
                        val corner = CornerRadius(trackH * 0.5f)
                        if (useApi33Lens && frame.lensMix > 0.005f) {
                            // 仅在活跃折射帧垫一层实底让槽内采样 alpha=1：浅色用 pageBg 避免混出 #F7F7F8 与页面背景同色消失，深色用 cardBg 保持对比。
                            drawRoundRect(
                                color = if (isDark) p.cardBg else p.pageBg,
                                topLeft = Offset(bleedPx, bleedPx),
                                size = Size(trackW, trackH),
                                cornerRadius = corner,
                            )
                        }
                        drawRoundRect(
                            color = p.fill,
                            topLeft = Offset(bleedPx, bleedPx),
                            size = Size(trackW, trackH),
                            cornerRadius = corner,
                        )
                    },
            ) {
                // 静止滑块 / 兜底白囊
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val frame = computeSegmentPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            bleedPx = bleedDp.toPx(),
                            paddingPx = padDp.toPx(),
                            gapPx = gapDp.toPx(),
                            count = count,
                            position = if (reduceMotion) target.toFloat() else position.value,
                            velocitySegmentsPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                            dragSqueeze = if (reduceMotion || !effectiveGlass) 0f else dragSqueeze.value,
                            liftY = if (reduceMotion || !effectiveGlass) 0f else liftY.value,
                        )
                        val restingAlpha = if (useApi33Lens) {
                            1f - smoothstep01(frame.lensMix)
                        } else {
                            if (effectiveGlass) frame.fillAlpha else 1f
                        }
                        if (restingAlpha > 0.005f) {
                            val corner = CornerRadius(frame.height * 0.5f)
                            val topLeft = Offset(frame.left, frame.top)
                            val pillSize = Size(frame.width, frame.height)
                            if (frame.shadowAlpha > 0.005f && !reduceMotion && effectiveGlass) {
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = frame.shadowAlpha * restingAlpha),
                                    topLeft = Offset(frame.left, frame.top + 1.dp.toPx()),
                                    size = pillSize,
                                    cornerRadius = corner,
                                )
                            }
                            drawRoundRect(
                                color = p.cardBg.copy(alpha = restingAlpha),
                                topLeft = topLeft,
                                size = pillSize,
                                cornerRadius = corner,
                            )
                            if (effectiveGlass) {
                                val stroke = 0.75.dp.toPx()
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            p.glassStrokeTop.copy(alpha = p.glassStrokeTop.alpha * restingAlpha),
                                            p.glassStrokeBottom.copy(alpha = p.glassStrokeBottom.alpha * restingAlpha),
                                        ),
                                        startY = frame.top,
                                        endY = frame.top + frame.height,
                                    ),
                                    topLeft = Offset(frame.left + stroke * 0.5f, frame.top + stroke * 0.5f),
                                    size = Size((frame.width - stroke).coerceAtLeast(1f), (frame.height - stroke).coerceAtLeast(1f)),
                                    cornerRadius = corner,
                                    style = Stroke(stroke),
                                )
                            }
                            if (!useApi33Lens && effectiveGlass && frame.rimAlpha > 0.005f) {
                                val rimStroke = 0.9.dp.toPx()
                                drawRoundRect(
                                    color = Color.White.copy(alpha = frame.rimAlpha),
                                    topLeft = Offset(frame.left + rimStroke * 0.5f, frame.top + rimStroke * 0.5f),
                                    size = Size((frame.width - rimStroke).coerceAtLeast(1f), (frame.height - rimStroke).coerceAtLeast(1f)),
                                    cornerRadius = corner,
                                    style = Stroke(rimStroke),
                                )
                            }
                        }
                    },
            )

            // 未覆盖区文字层（ClipOp.Difference）
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val frame = computeSegmentPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            bleedPx = bleedDp.toPx(),
                            paddingPx = padDp.toPx(),
                            gapPx = gapDp.toPx(),
                            count = count,
                            position = if (reduceMotion) target.toFloat() else position.value,
                            velocitySegmentsPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                            dragSqueeze = if (reduceMotion || !effectiveGlass) 0f else dragSqueeze.value,
                            liftY = if (reduceMotion || !effectiveGlass) 0f else liftY.value,
                        )
                        outsideClipPath.rewind()
                        outsideClipPath.addRoundRect(
                            RoundRect(
                                left = frame.left,
                                top = frame.top,
                                right = frame.left + frame.width,
                                bottom = frame.top + frame.height,
                                cornerRadius = CornerRadius(frame.height * 0.5f),
                            ),
                        )
                        clipPath(outsideClipPath, clipOp = ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bleedDp + padDp)
                        .selectableGroup()
                        .pointerInput(count, enabled) {
                            if (!currentEnabled) return@pointerInput
                            val lastIndex = (count - 1).coerceAtLeast(0)
                            awaitEachGesture {
                                val rowW = size.width.toFloat().coerceAtLeast(1f)
                                val cellW = (rowW / count.coerceAtLeast(1)).coerceAtLeast(1f)
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downPos = down.position
                                val startTab = segmentIndexForX(downPos.x, rowW, count)
                                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, downPos) }
                                var dragging = false
                                var cancelled = false
                                var lastHovered = startTab
                                var lastPointer = downPos
                                var prevPointerPos = downPos
                                var prevTimeMillis = down.uptimeMillis
                                var dragStartPos = currentTarget.toFloat()
                                var dragAnchorX = downPos.x
                                var squeezeDecayJob: kotlinx.coroutines.Job? = null
                                val slop = viewConfiguration.touchSlop * 0.65f

                                fun settleVisualsToRest() {
                                    if (currentReduceMotion) {
                                        scope.launch {
                                            lift.snapTo(0f)
                                            liftY.snapTo(0f)
                                            lens.snapTo(0f)
                                            dragSqueeze.snapTo(0f)
                                        }
                                    } else {
                                        scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.60f, stiffness = 220f)) }
                                        scope.launch { liftY.animateTo(0f, spring(dampingRatio = 0.74f, stiffness = 270f)) }
                                        scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                                        scope.launch { dragSqueeze.animateTo(0f, spring(dampingRatio = 0.44f, stiffness = 220f)) }
                                    }
                                }

                                if (currentEffectiveGlass && !currentReduceMotion) {
                                    scope.launch { lift.animateTo(1f, spring(dampingRatio = 0.64f, stiffness = 440f)) }
                                    scope.launch { liftY.animateTo(1f, spring(dampingRatio = 0.76f, stiffness = 500f)) }
                                    scope.launch { lens.animateTo(1f, spring(dampingRatio = 0.92f, stiffness = 420f)) }
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    if (change == null) {
                                        cancelled = true
                                        break
                                    }
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    lastPointer = change.position
                                    if (!change.pressed) {
                                        if (dragging) change.consume()
                                        break
                                    }
                                    if (change.isConsumed) {
                                        cancelled = true
                                        break
                                    }
                                    val dx = change.position.x - downPos.x
                                    val dy = change.position.y - downPos.y
                                    if (!dragging && abs(dx) > slop && abs(dx) >= abs(dy) * 0.85f) {
                                        dragging = true
                                        isDragging[0] = true
                                        if (startTab == currentTarget) {
                                            dragAnchorX = downPos.x
                                            dragStartPos = position.value
                                        } else {
                                            dragAnchorX = change.position.x
                                            dragStartPos = ((change.position.x / cellW) - 0.5f).coerceIn(0f, lastIndex.toFloat())
                                        }
                                        lastHovered = dragStartPos.roundToInt().coerceIn(0, lastIndex)
                                        scrubbingIdx = lastHovered
                                        prevPointerPos = downPos
                                        prevTimeMillis = down.uptimeMillis
                                    } else if (!dragging && abs(dy) > slop && abs(dy) > abs(dx) * 1.1f) {
                                        cancelled = true
                                        break
                                    }
                                    if (dragging) {
                                        change.consume()
                                        val rawPos = dragStartPos + (change.position.x - dragAnchorX) / cellW
                                        val nextPos = rubberBandSegmentPosition(rawPos, lastIndex)
                                        val hovered = nextPos.roundToInt().coerceIn(0, lastIndex)
                                        if (hovered != lastHovered) {
                                            lastHovered = hovered
                                            scrubbingIdx = hovered
                                            haptic.tick()
                                        }
                                        if (currentReduceMotion) {
                                            scope.launch { position.snapTo(hovered.toFloat()) }
                                        } else {
                                            scope.launch {
                                                position.animateTo(
                                                    targetValue = nextPos,
                                                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 900f),
                                                )
                                            }
                                            if (currentEffectiveGlass) {
                                                val dtSec = ((change.uptimeMillis - prevTimeMillis).coerceAtLeast(4L)) / 1000f
                                                val instVelSeg = abs((change.position.x - prevPointerPos.x) / cellW) / dtSec
                                                val trackerVelSeg = abs(tracker.calculateVelocity().x / cellW)
                                                val activeSpeed = maxOf(trackerVelSeg, instVelSeg * 0.65f)
                                                val targetSqueeze = (activeSpeed / (activeSpeed + 3.2f)).coerceIn(0f, 1f)
                                                scope.launch {
                                                    dragSqueeze.animateTo(
                                                        targetValue = targetSqueeze,
                                                        animationSpec = spring(dampingRatio = 0.46f, stiffness = 340f),
                                                    )
                                                }
                                                squeezeDecayJob?.cancel()
                                                squeezeDecayJob = scope.launch {
                                                    delay(45)
                                                    dragSqueeze.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(dampingRatio = 0.42f, stiffness = 240f),
                                                    )
                                                }
                                            }
                                        }
                                        prevPointerPos = change.position
                                        prevTimeMillis = change.uptimeMillis
                                    }
                                }

                                squeezeDecayJob?.cancel()
                                scrubbingIdx = -1
                                isDragging[0] = false
                                if (cancelled) {
                                    if (currentReduceMotion) {
                                        scope.launch { position.snapTo(currentTarget.toFloat()) }
                                    } else {
                                        scope.launch {
                                            position.animateTo(currentTarget.toFloat(), spring(dampingRatio = 0.82f, stiffness = 520f))
                                        }
                                    }
                                    settleVisualsToRest()
                                } else if (dragging) {
                                    val velSeg = (tracker.calculateVelocity().x / cellW).coerceIn(-16f, 16f)
                                    val chosen = resolveSegmentTarget(position.value, velSeg, count)
                                    if (chosen != currentTarget) {
                                        if (lastHovered != chosen) haptic.tick()
                                        committedFromDrag[0] = true
                                        releaseVel[0] = velSeg
                                        currentOnSelect(chosen)
                                    } else {
                                        if (currentReduceMotion) {
                                            scope.launch { position.snapTo(chosen.toFloat()) }
                                        } else {
                                            scope.launch {
                                                position.animateTo(
                                                    targetValue = chosen.toFloat(),
                                                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                                                    initialVelocity = velSeg,
                                                )
                                            }
                                        }
                                        settleVisualsToRest()
                                    }
                                } else {
                                    val inBounds = lastPointer.y in (-size.height * 0.5f)..(size.height * 1.5f) &&
                                        lastPointer.x in (-cellW * 0.25f)..(rowW + cellW * 0.25f)
                                    if (inBounds) {
                                        val tapped = segmentIndexForX(lastPointer.x, rowW, count)
                                        if (tapped != currentTarget) {
                                            haptic.tick()
                                            currentOnSelect(tapped)
                                        } else {
                                            settleVisualsToRest()
                                        }
                                    } else {
                                        settleVisualsToRest()
                                    }
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(gapDp),
                ) {
                    options.forEachIndexed { index, label ->
                        val active = index == visualActive
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = minHeight)
                                .semantics {
                                    role = Role.Tab
                                    this.selected = active
                                    stateDescription = if (active) "已选中" else "未选中"
                                    onClick {
                                        if (enabled) {
                                            if (index != target) haptic.tick()
                                            onSelect(index)
                                            true
                                        } else false
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = AbType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = p.secondaryLabel,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 滑块覆盖区高亮文字层（ClipOp.Intersect）
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val frame = computeSegmentPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            bleedPx = bleedDp.toPx(),
                            paddingPx = padDp.toPx(),
                            gapPx = gapDp.toPx(),
                            count = count,
                            position = if (reduceMotion) target.toFloat() else position.value,
                            velocitySegmentsPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                            dragSqueeze = if (reduceMotion || !effectiveGlass) 0f else dragSqueeze.value,
                            liftY = if (reduceMotion || !effectiveGlass) 0f else liftY.value,
                        )
                        insideClipPath.rewind()
                        insideClipPath.addRoundRect(
                            RoundRect(
                                left = frame.left,
                                top = frame.top,
                                right = frame.left + frame.width,
                                bottom = frame.top + frame.height,
                                cornerRadius = CornerRadius(frame.height * 0.5f),
                            ),
                        )
                        clipPath(insideClipPath, clipOp = ClipOp.Intersect) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bleedDp + padDp)
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(gapDp),
                ) {
                    options.forEach { label ->
                        Box(
                            modifier = Modifier.weight(1f).heightIn(min = minHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = AbType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = p.label,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            }

            // Android 13+ 实时 AGSL 凸镜水滴叠加层（仅活跃态绘制；作为兄弟层放在被录制容器之外）
            if (useApi33Lens) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val frame = computeSegmentPillFrame(
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                bleedPx = bleedDp.toPx(),
                                paddingPx = padDp.toPx(),
                                gapPx = gapDp.toPx(),
                                count = count,
                                position = position.value,
                                velocitySegmentsPerSec = position.velocity,
                                lift = lift.value,
                                lens = lens.value,
                                isDark = isDark,
                                dragSqueeze = dragSqueeze.value,
                                liftY = liftY.value,
                            )
                            renderEffect = if (frame.lensMix > 0.005f && Build.VERSION.SDK_INT >= 33) {
                                ControlLensApi33.createEffect(
                                    canvasWidth = size.width,
                                    canvasHeight = size.height,
                                    frame = frame,
                                    density = density,
                                    isDark = isDark,
                                    refractionBandDp = if (compact) 9f else 12f,
                                    refractionAmountDp = if (compact) 11f else 15f,
                                    magnification = 0.14f,
                                    dispersion = 0.20f,
                                    verticalRefraction = 0.08f,
                                    verticalMagnify = 0.50f,
                                )
                            } else null
                        }
                        .drawBehind {
                            val frame = computeSegmentPillFrame(
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                bleedPx = bleedDp.toPx(),
                                paddingPx = padDp.toPx(),
                                gapPx = gapDp.toPx(),
                                count = count,
                                position = position.value,
                                velocitySegmentsPerSec = position.velocity,
                                lift = lift.value,
                                lens = lens.value,
                                isDark = isDark,
                                dragSqueeze = dragSqueeze.value,
                                liftY = liftY.value,
                            )
                            if (frame.lensMix > 0.005f) {
                                drawLayer(contentLayer)
                            }
                        },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 连续滑杆
// ---------------------------------------------------------------------------

/** 液态玻璃连续滑杆：静止零离屏录制，按住/拖动液化为 AGSL 凸镜水滴折射轨道。 */
@Composable
fun AbLiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val isDark = p.pageBg.luminance() < 0.1f
    val density = LocalDensity.current.density

    val clamped = value.coerceIn(0f, 1f)
    val position = remember { Animatable(clamped) }
    val lift = remember { Animatable(0f) }
    val lens = remember { Animatable(0f) }
    val isDragging = remember { booleanArrayOf(false) }
    val scope = rememberCoroutineScope()

    val currentValue by rememberUpdatedState(clamped)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentEffectiveGlass by rememberUpdatedState(effectiveGlass)

    LaunchedEffect(clamped, reduceMotion, effectiveGlass) {
        if (!isDragging[0]) {
            position.snapTo(clamped)
        }
        if (reduceMotion || !effectiveGlass) {
            lift.snapTo(0f)
            lens.snapTo(0f)
        }
    }

    val boxHeight = 48.dp
    val trackHeightDp = 6.dp
    val knobWidthDp = 28.dp
    val knobHeightDp = 20.dp
    val bleedDp = 8.dp
    val useApi33Lens = effectiveGlass && !reduceMotion && Build.VERSION.SDK_INT >= 33
    val trackLayer = rememberGraphicsLayer()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = boxHeight)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    val next = target.coerceIn(0f, 1f)
                    onValueChange(next)
                    true
                }
            }
            .pointerInput(enabled) {
                if (!currentEnabled) return@pointerInput
                val knobW = knobWidthDp.toPx()
                val travelPx = (size.width.toFloat() - knobW).coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, downPos) }
                    var dragging = false
                    var cancelled = false
                    var lastPointer = downPos
                    var lastClamped = currentValue
                    val slop = viewConfiguration.touchSlop * 0.55f

                    if (currentEffectiveGlass && !currentReduceMotion) {
                        scope.launch { lift.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 480f)) }
                        scope.launch { lens.animateTo(1f, spring(dampingRatio = 0.95f, stiffness = 420f)) }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        lastPointer = change.position
                        if (!change.pressed) {
                            if (dragging) change.consume()
                            break
                        }
                        if (change.isConsumed) {
                            cancelled = true
                            break
                        }
                        val dx = change.position.x - downPos.x
                        val dy = change.position.y - downPos.y
                        if (!dragging && abs(dx) > slop && abs(dx) >= abs(dy) * 0.85f) {
                            dragging = true
                            isDragging[0] = true
                        } else if (!dragging && abs(dy) > slop && abs(dy) > abs(dx) * 1.15f) {
                            cancelled = true
                            break
                        }
                        if (dragging) {
                            change.consume()
                            val raw = (change.position.x - knobW * 0.5f) / travelPx
                            val nextClamped = raw.coerceIn(0f, 1f)
                            val display = if (currentReduceMotion) nextClamped else rubberBandSliderValue(raw)
                            if ((nextClamped == 0f || nextClamped == 1f) && lastClamped != nextClamped) {
                                haptic.tick()
                            }
                            lastClamped = nextClamped
                            currentOnValueChange(nextClamped)
                            scope.launch { position.snapTo(display) }
                        }
                    }

                    isDragging[0] = false
                    if (cancelled) {
                        scope.launch { position.snapTo(currentValue) }
                        if (!currentReduceMotion) {
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                            scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                        }
                    } else if (dragging) {
                        val target = lastClamped
                        val velPerSec = (tracker.calculateVelocity().x / travelPx).coerceIn(-8f, 8f)
                        if (currentReduceMotion) {
                            scope.launch {
                                position.snapTo(target)
                                lift.snapTo(0f)
                                lens.snapTo(0f)
                            }
                        } else {
                            scope.launch {
                                position.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 620f), initialVelocity = velPerSec)
                            }
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                            scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                        }
                    } else {
                        val inBounds = lastPointer.x in (-8.dp.toPx())..(size.width + 8.dp.toPx()) &&
                            lastPointer.y in (-8.dp.toPx())..(size.height + 8.dp.toPx())
                        if (inBounds) {
                            val tapped = ((lastPointer.x - knobW * 0.5f) / travelPx).coerceIn(0f, 1f)
                            haptic.tick()
                            currentOnValueChange(tapped)
                            if (currentReduceMotion) {
                                scope.launch {
                                    position.snapTo(tapped)
                                    lift.snapTo(0f)
                                    lens.snapTo(0f)
                                }
                            } else {
                                scope.launch { position.animateTo(tapped, spring(dampingRatio = 0.84f, stiffness = 560f)) }
                                scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                                scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                            }
                        } else if (!currentReduceMotion) {
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 220f)) }
                            scope.launch { lens.animateTo(0f, spring(dampingRatio = 0.98f, stiffness = 145f)) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(boxHeight)
                .layout { measurable, constraints ->
                    val bleedPx = bleedDp.roundToPx()
                    val placeable = measurable.measure(
                        Constraints.fixed(constraints.maxWidth + bleedPx * 2, constraints.maxHeight + bleedPx * 2),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(-bleedPx, -bleedPx)
                    }
                },
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val frame = computeSliderPillFrame(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            bleedPx = bleedDp.toPx(),
                            knobWidth = knobWidthDp.toPx(),
                            knobHeight = knobHeightDp.toPx(),
                            value = if (reduceMotion) clamped else position.value,
                            velocityPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                            lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                            lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                            isDark = isDark,
                        )
                        if (useApi33Lens && frame.lensMix > 0.005f) {
                            trackLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(trackLayer)
                        } else {
                            drawContent()
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val bleedPx = bleedDp.toPx()
                    val trackW = (size.width - bleedPx * 2f).coerceAtLeast(1f)
                    val trackH = trackHeightDp.toPx()
                    val trackLeft = bleedPx
                    val trackTop = (size.height - trackH) * 0.5f
                    val corner = CornerRadius(trackH * 0.5f)
                    drawRoundRect(
                        color = p.barTrack,
                        topLeft = Offset(trackLeft, trackTop),
                        size = Size(trackW, trackH),
                        cornerRadius = corner,
                    )
                    val pos = (if (reduceMotion) clamped else position.value).coerceIn(0f, 1f)
                    val knobW = knobWidthDp.toPx()
                    val fillW = (knobW * 0.5f + (trackW - knobW) * pos).coerceIn(trackH, trackW)
                    drawRoundRect(
                        color = if (enabled) p.accent else p.secondaryLabel.copy(alpha = 0.45f),
                        topLeft = Offset(trackLeft, trackTop),
                        size = Size(fillW, trackH),
                        cornerRadius = corner,
                    )
                }
            }

            if (useApi33Lens) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val frame = computeSliderPillFrame(
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                bleedPx = bleedDp.toPx(),
                                knobWidth = knobWidthDp.toPx(),
                                knobHeight = knobHeightDp.toPx(),
                                value = position.value,
                                velocityPerSec = position.velocity,
                                lift = lift.value,
                                lens = lens.value,
                                isDark = isDark,
                            )
                            renderEffect = if (frame.lensMix > 0.005f && Build.VERSION.SDK_INT >= 33) {
                                ControlLensApi33.createEffect(
                                    canvasWidth = size.width,
                                    canvasHeight = size.height,
                                    frame = frame,
                                    density = density,
                                    isDark = isDark,
                                    refractionBandDp = 7f,
                                    refractionAmountDp = 9f,
                                )
                            } else null
                        }
                        .drawBehind {
                            val frame = computeSliderPillFrame(
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                bleedPx = bleedDp.toPx(),
                                knobWidth = knobWidthDp.toPx(),
                                knobHeight = knobHeightDp.toPx(),
                                value = position.value,
                                velocityPerSec = position.velocity,
                                lift = lift.value,
                                lens = lens.value,
                                isDark = isDark,
                            )
                            if (frame.lensMix > 0.005f) {
                                drawLayer(trackLayer)
                            }
                        },
                )
            }

            Canvas(Modifier.fillMaxSize()) {
                val frame = computeSliderPillFrame(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    bleedPx = bleedDp.toPx(),
                    knobWidth = knobWidthDp.toPx(),
                    knobHeight = knobHeightDp.toPx(),
                    value = if (reduceMotion) clamped else position.value,
                    velocityPerSec = if (reduceMotion || !effectiveGlass) 0f else position.velocity,
                    lift = if (reduceMotion || !effectiveGlass) 0f else lift.value,
                    lens = if (reduceMotion || !effectiveGlass) 0f else lens.value,
                    isDark = isDark,
                )
                val restingAlpha = if (useApi33Lens) {
                    1f - smoothstep01(frame.lensMix)
                } else {
                    if (effectiveGlass) frame.fillAlpha else 1f
                }
                if (restingAlpha > 0.005f) {
                    val corner = CornerRadius(frame.height * 0.5f)
                    val topLeft = Offset(frame.left, frame.top)
                    val pillSize = Size(frame.width, frame.height)
                    if (frame.shadowAlpha > 0.005f && !reduceMotion) {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = frame.shadowAlpha * restingAlpha),
                            topLeft = Offset(frame.left, frame.top + 1.dp.toPx()),
                            size = pillSize,
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(
                        color = Color.White.copy(alpha = restingAlpha),
                        topLeft = topLeft,
                        size = pillSize,
                        cornerRadius = corner,
                    )
                    val knobStroke = 0.5.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = (if (isDark) 0.16f else 0.08f) * restingAlpha),
                        topLeft = Offset(frame.left + knobStroke * 0.5f, frame.top + knobStroke * 0.5f),
                        size = Size((frame.width - knobStroke).coerceAtLeast(1f), (frame.height - knobStroke).coerceAtLeast(1f)),
                        cornerRadius = corner,
                        style = Stroke(knobStroke),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

private enum class ButtonVariant { PRIMARY, SECONDARY, DESTRUCTIVE }

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
    ActionButton(text, onClick, modifier.layoutId(SectionRole.ACTION), enabled, busy, busyLabel, ButtonVariant.PRIMARY)
}

/** 次要按钮：无硬框柔灰玻璃填充胶囊，共用按压、忙碌与读屏语义。 */
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
    ActionButton(text, onClick, modifier, enabled, busy, busyLabel, ButtonVariant.SECONDARY, tint = tint)
}

/** 危险操作：柔和警示红玻璃胶囊，提交、失败与撤销仍由调用方决定。 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "处理中…",
) {
    ActionButton(text, onClick, modifier, enabled, busy, busyLabel, ButtonVariant.DESTRUCTIVE)
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    busy: Boolean,
    busyLabel: String,
    variant: ButtonVariant,
    tint: Color? = null,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val canClick = enabled && !busy
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val inModal = LocalInModalSurface.current
    val isDark = p.pageBg.luminance() < 0.1f
    val animatedScale by animateFloatAsState(
        if (pressed && canClick) 0.965f else 1f,
        animationSpec = if (reduceMotion) tween(90, easing = AbEasing.EaseOut) else spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "button-press",
    )
    val pressHighlight by animateFloatAsState(
        if (pressed && canClick) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 80 else 90, easing = AbEasing.EaseOut),
        label = "button-highlight",
    )
    val shape = RoundedCornerShape(AbRadius.capsule)
    val bg = when (variant) {
        ButtonVariant.PRIMARY -> if (canClick || busy) p.accent else p.fill
        ButtonVariant.SECONDARY -> when {
            !canClick -> p.fill
            inModal -> if (isDark) Color(0x2AFFFFFF) else Color(0x14767680)
            else -> if (isDark) Color(0x28767680) else Color(0x12767680)
        }
        ButtonVariant.DESTRUCTIVE -> when {
            !canClick -> p.fill
            else -> p.red.copy(alpha = if (isDark) 0.24f else 0.14f)
        }
    }
    Box(
        modifier = modifier.heightIn(min = 50.dp)
            .scale(if (reduceMotion) 1f else animatedScale)
            .then(
                if (variant == ButtonVariant.PRIMARY && effectiveGlass && canClick) {
                    Modifier.shadow(
                        elevation = 6.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = p.accent.copy(alpha = 0.35f),
                        spotColor = p.accent.copy(alpha = 0.35f),
                    )
                } else Modifier,
            )
            .clip(shape)
            .background(bg)
            .drawWithContent {
                drawContent()
                if (effectiveGlass && (canClick || busy)) {
                    val h = size.height
                    when (variant) {
                        ButtonVariant.PRIMARY -> {
                            val stroke = 0.75.dp.toPx()
                            drawRoundRect(
                                brush = liquidSpecularRimBrush(isDark = false, alphaScale = 0.65f),
                                topLeft = Offset(stroke * 0.5f, stroke * 0.5f),
                                size = Size((size.width - stroke).coerceAtLeast(1f), (h - stroke).coerceAtLeast(1f)),
                                cornerRadius = CornerRadius(h * 0.5f),
                                style = Stroke(stroke),
                            )
                        }
                        ButtonVariant.SECONDARY -> {
                            val stroke = 0.65.dp.toPx()
                            drawRoundRect(
                                brush = liquidSpecularRimBrush(isDark = isDark, alphaScale = 0.75f),
                                topLeft = Offset(stroke * 0.5f, stroke * 0.5f),
                                size = Size((size.width - stroke).coerceAtLeast(1f), (h - stroke).coerceAtLeast(1f)),
                                cornerRadius = CornerRadius(h * 0.5f),
                                style = Stroke(stroke),
                            )
                        }
                        ButtonVariant.DESTRUCTIVE -> {
                            val stroke = 0.6.dp.toPx()
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    0.0f to Color.White.copy(alpha = if (isDark) 0.22f else 0.52f),
                                    0.5f to Color.Transparent,
                                    1.0f to Color.White.copy(alpha = if (isDark) 0.10f else 0.24f),
                                ),
                                topLeft = Offset(stroke * 0.5f, stroke * 0.5f),
                                size = Size((size.width - stroke).coerceAtLeast(1f), (h - stroke).coerceAtLeast(1f)),
                                cornerRadius = CornerRadius(h * 0.5f),
                                style = Stroke(stroke),
                            )
                        }
                    }
                }
                if (pressHighlight > 0.01f) {
                    drawRect(Color.White.copy(alpha = 0.14f * pressHighlight))
                }
            }
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
                variant == ButtonVariant.PRIMARY && (canClick || busy) -> p.onAccent
                !canClick -> p.secondaryLabel
                variant == ButtonVariant.DESTRUCTIVE -> p.red
                else -> tint ?: p.label
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 轻量无水波纹文字按钮：48dp 最小触控，按压仅做轻缩放与透明反馈。 */
@Composable
fun AbTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
    style: TextStyle = AbType.footnote.copy(fontWeight = FontWeight.SemiBold),
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduceMotion) 0.96f else 1f,
        animationSpec = if (reduceMotion) tween(90, easing = AbEasing.EaseOut) else spring(dampingRatio = 0.72f, stiffness = 540f),
        label = "text-btn-press",
    )
    val resolvedColor = (color ?: p.accent).let { if (enabled) it else p.secondaryLabel }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .scale(scale)
            .clip(RoundedCornerShape(AbRadius.capsule))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.select()
                onClick()
            }
            .padding(horizontal = AbSpacing.m, vertical = AbSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            color = if (pressed && enabled) resolvedColor.copy(alpha = 0.62f) else resolvedColor,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 图标徽章
// ---------------------------------------------------------------------------

/** 图标徽章：纯色圆形实底 + 白色前景。 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = AbCardDensity.settingsIcon,
) {
    val p = LocalAbPalette.current
    val dark = p.cardBg.luminance() < 0.5f
    val neutral = tint.alpha < 1f
    val bg = if (neutral) {
        if (dark) Color(0xFF48484A) else Color(0xFF8E8E93)
    } else {
        solidBadgeBackground(tint)
    }
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .badgeLiquidGlaze(effectiveGlass),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(size * 0.54f), tint = Color.White)
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
        modifier = modifier.fillMaxWidth().padding(horizontal = AbSpacing.xxl, vertical = AbSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(p.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(28.dp), tint = p.secondaryLabel)
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

@Composable
fun Modifier.abPressFeedback(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 1f,
    tintOverlay: Boolean = false,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val active = pressed && enabled
    val scale by animateFloatAsState(
        targetValue = if (active && !reduceMotion) pressedScale else 1f,
        animationSpec = if (reduceMotion) tween(90, easing = AbEasing.EaseOut) else spring(dampingRatio = 0.72f, stiffness = 560f),
        label = "ab-press-scale",
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 80 else 110, easing = AbEasing.EaseOut),
        label = "ab-press-progress",
    )
    val effectivePressedAlpha = if (tintOverlay && pressedAlpha == 1f) 0.78f else pressedAlpha
    val alphaDelta = (1f - effectivePressedAlpha).coerceIn(0f, 1f)
    return this.graphicsLayer {
        if (!reduceMotion && pressedScale != 1f) {
            scaleX = scale
            scaleY = scale
        }
        if (alphaDelta > 0f) {
            alpha = 1f - alphaDelta * pressProgress
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
    val isDark = p.pageBg.luminance() < 0.1f
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val c = tint ?: p.accent
    val targetBg = if (selected) c.copy(alpha = if (isDark) 0.30f else 0.16f) else p.fill
    val bg by animateColorAsState(
        targetBg,
        animationSpec = tween(if (reduceMotion) 120 else AbMotion.selection, easing = AbEasing.EaseOut),
        label = "chip-bg",
    )
    val fg = if (selected && tint == null) c else p.label
    val shape = RoundedCornerShape(AbRadius.capsule)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .abPressFeedback(interactionSource = interaction, pressedScale = 0.96f, pressedAlpha = 0.85f)
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { this.selected = selected; role = Role.Tab; stateDescription = if (selected) "已选中" else "未选中" }
            .padding(horizontal = 14.dp, vertical = AbSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = AbType.footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
        )
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
    val isDark = p.pageBg.luminance() < 0.1f
    val shape = RoundedCornerShape(AbRadius.capsule)
    Row(
        modifier = modifier.clip(shape)
            .background(if (emphasized) p.accent.copy(alpha = if (isDark) 0.24f else 0.14f) else p.fill)
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
    keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text,
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
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
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
    labelWidth: Dp = Dp.Unspecified,
    keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text,
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
            Text(
                label,
                style = AbType.body,
                color = p.label,
                modifier = if (labelWidth != Dp.Unspecified) Modifier.widthIn(min = labelWidth) else Modifier,
            )
            AbTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                keyboardType = keyboardType,
                modifier = Modifier.weight(1f),
                onDone = onDone,
            )
        }
    }
}
