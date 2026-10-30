package com.example.mymoney.ui.design

import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 大标题栏，iOS large title 的行为：
 *
 * - 停在顶部时只有 34sp 页首标题；
 * - 滚动后标题淡出，玻璃背板淡入，出现 17sp 居中标题；
 * - 右侧动作按钮在两种状态下都在，位置一致。
 *
 * 进度用滚动偏移线性映射（不做弹簧），避免和 LazyColumn 的 fling 打架。
 *
 * @param scrollOffset 已滚动像素
 * @param collapseAt   完全收起所需的像素
 * @param compact      直接以收起态渲染（没有大标题块）。统计页用它腾出首屏高度。
 */
@Composable
fun LargeTitleBar(
    title: String,
    scrollOffset: Float,
    modifier: Modifier = Modifier,
    collapseAt: Float = 140f,
    subtitle: String? = null,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    val progress = if (compact) 1f else if (collapseAt <= 0f) 1f else (scrollOffset / collapseAt).coerceIn(0f, 1f)

    // 标题在中间区间完成交叉淡入淡出，避免两行字同时清晰
    val largeAlpha = ((0.62f - progress) / 0.47f).coerceIn(0f, 1f)
    val compactAlpha = ((progress - 0.30f) / 0.42f).coerceIn(0f, 1f)
    val barAlpha = ((progress - 0.55f) / 0.35f).coerceIn(0f, 1f)

    Box(modifier.fillMaxWidth()) {
        if (barAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (reduce) p.cardBg.copy(alpha = barAlpha)
                        else p.glassTint.copy(alpha = barAlpha),
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(p.separator.copy(alpha = barAlpha)),
                )
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AbSize.navBarHeight)
                    .padding(horizontal = AbSpacing.page),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Column(Modifier.alpha(compactAlpha)) {
                        Text(title, style = AbType.navTitle, color = p.label)
                        if (compact && subtitle != null) Text(subtitle, style = AbType.caption1, color = p.secondaryLabel)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(compactAlpha),
                    content = actions,
                )
            }

            // 大标题块的高度跟着进度一起收到 0，避免收起后留一大块空白
            val largeBlockHeight = (if (subtitle != null) 69.dp else 51.dp) * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(largeBlockHeight * largeAlpha)
                    .clipToBounds(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AbSpacing.page, end = AbSpacing.page, bottom = 8.dp)
                        .alpha(largeAlpha),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = AbType.largeTitle, color = p.label)
                        if (subtitle != null) {
                            Text(subtitle, style = AbType.footnote, color = p.secondaryLabel)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 栏
// ---------------------------------------------------------------------------

data class TabItem(
    val label: String,
    val icon: ImageVector,
    /** 有值就画会动的版本（选中时播一次画入动效）；否则退回 [icon] 静态图标。 */
    val glyph: TabGlyph? = null,
)

/**
 * 底部悬浮胶囊 Tab 栏（API < 33 的假玻璃兜底）：半透明玻璃、内容从它下面穿过。
 *
 * 宿主负责左右边距和 View 投影，此处只提供胶囊背景及四个入口。
 */
@Composable
fun LiquidTabBar(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dockHeight())
            .clip(RoundedCornerShape(AbRadius.capsule))
            .background(p.cardBg.copy(alpha = if (reduce) 1f else 0.72f)),
    ) {
        LiquidTabCells(items = items, selected = selected, onSelect = onSelect)
    }
}

/**
 * Tab 图标本身，不含玻璃背景。
 *
 * 真·液体玻璃（Android 13+）由 `LiquidGlassView` 这个真实 Android View 提供，
 * 它需要一个与被采样内容并列的 ViewGroup 才能做折射，所以图标要单独放进它的子
 * ComposeView 里（玻璃画完再画图标，不会被自己折射）。
 * API < 33 时退回 [LiquidTabBar] 的 Compose 假玻璃。
 */
@Composable
fun LiquidTabCells(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAbPalette.current
    if (items.isEmpty()) return
    val haptic = rememberAbHaptic()

    val reduceMotion = LocalReduceMotion.current
    // Keep the animation state unread until drawing: the dock grid never needs
    // a new composition/measurement for an indicator animation frame.
    val position = animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.82f, stiffness = 450f),
        label = "tabIndicatorPosition",
    )
    val pulse = remember { Animatable(1f) }
    val previousSelection = remember { intArrayOf(selected) }
    LaunchedEffect(selected, reduceMotion) {
        val changed = selected != previousSelection[0]
        previousSelection[0] = selected
        if (reduceMotion) pulse.snapTo(1f)
        else if (changed) pulse.animateTo(1f, keyframes {
            durationMillis = 330
            pulse.value at 0 using androidx.compose.animation.core.FastOutSlowInEasing
            1.04f at 110 using androidx.compose.animation.core.FastOutSlowInEasing
            1f at 330 using androidx.compose.animation.core.FastOutSlowInEasing
        })
    }
    val inset = 6.dp
    Box(modifier.fillMaxSize().selectableGroup().drawBehind {
        val stroke = 0.65.dp.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.04f),
                Color.Black.copy(alpha = 0.07f),
            )),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(size.height / 2),
            style = Stroke(stroke),
        )
        val cellW = ((size.width - inset.toPx() * 2) / items.size).coerceAtLeast(1.dp.toPx())
        val cellH = (size.height - inset.toPx() * 2).coerceAtLeast(1.dp.toPx())
        val pos = if (reduceMotion) selected.toFloat() else position.value
        // Pulse scales only the selection surface, never the hit grid, icons or layout.
        val scale = if (reduceMotion) 1f else pulse.value
        val indicatorW = ((cellW + 6.dp.toPx()) * scale).coerceAtMost(size.width - 2.dp.toPx())
        val indicatorH = ((cellH + 6.dp.toPx()) * scale).coerceAtMost(size.height - 2.dp.toPx())
        val indicatorX = (inset.toPx() + cellW * (pos + 0.5f) - indicatorW / 2)
            .coerceIn(1.dp.toPx(), (size.width - 1.dp.toPx() - indicatorW).coerceAtLeast(1.dp.toPx()))
        drawRoundRect(
            color = p.tabIndicator,
            topLeft = Offset(indicatorX, (size.height - indicatorH) / 2),
            size = Size(indicatorW, indicatorH),
            cornerRadius = CornerRadius(indicatorH / 2),
        )
    }) {
        Row(Modifier.fillMaxSize().padding(horizontal = inset)) {
            items.forEachIndexed { index, item ->
                TabCell(
                    item = item,
                    active = index == selected,
                    inactive = p.secondaryLabel,
                    selectedColor = p.label,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onSelect = {
                        if (index != selected) {
                            haptic.tick()
                            onSelect(index)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TabCell(
    item: TabItem,
    active: Boolean,
    inactive: Color,
    selectedColor: Color,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    // 轻微放大，不改变格子和标签的布局。
    val reduceMotion = LocalReduceMotion.current
    val targetScale = if (active) 1f else 21f / 23f
    val iconScale = animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 500f),
        label = "tabIconSize",
    )
    val fg by animateColorAsState(
        targetValue = if (active) selectedColor else inactive,
        animationSpec = tween(if (LocalReduceMotion.current) 0 else AbMotion.fast),
        label = "tabFg",
    )
    Column(
        modifier = modifier
            .selectable(
                selected = active,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val glyphModifier = Modifier.size(23.dp).graphicsLayer {
            val scale = if (reduceMotion) targetScale else iconScale.value
            scaleX = scale
            scaleY = scale
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            val glyph = item.glyph
            if (glyph != null) {
                // 动态图标自己管动效（内部也读 减少动效），外面只负责选中缩放。
                TabGlyphIcon(glyph = glyph, active = active, tint = fg, modifier = glyphModifier)
            } else {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = glyphModifier,
                    tint = fg,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = AbType.caption1.copy(fontSize = 11.sp),
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * 页面骨架：大标题在上、内容在下，胶囊 Tab 栏由宿主（MainActivity）浮动在内容之上。
 *
 * 大标题块的高度跟着滚动进度收缩（见 LargeTitleBar），所以内容不会被
 * 常驻的大标题顶下去，滚到底也不会出现「标题已收起但内容还空一截」的错位。
 *
 * 这里**不再**画 Tab 栏、也不画任何底部遮罩：Tab 栏是真正悬浮的，内容从它
 * 下面穿过去（iOS 的观感）。以前这里叠过一条 `BottomFade(solidBelow=true)`，
 * 底端是不透明的 pageBg，结果在胶囊下面压出一条实色横带，看着像旧版
 * NavigationBar 挡内容 —— 已删除。
 */
@Composable
fun PageScaffold(
    title: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
    floatingHeader: Boolean = false,
    titleActive: Boolean = false,
    titleStateDescription: String? = null,
    onTitleClick: (() -> Unit)? = null,
    /**
     * 传非 null 时改用固定子页导航栏（居中标题、右动作；系统返回），
     * 大标题的滚动收缩随之关闭。
     *
     * 明细这种**压在其他 Tab 之上的整页**必须走这条路：大标题栏只在滚动时
     * 才铺底色，静止时标题区是透明的，一旦离开会被下层页面「透」出来。
     */
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    // Tab 栏离屏幕底边的距离：导航条 inset + 视觉留白（别贴着右下角）。
    // 内容底部再额外留一档，滚到最后一行不会被胶囊压住。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (floatingHeader && onBack == null) {
        FloatingPageScaffold(title = title, bottomPadding = dockHeight() + navBottom + AbLayout.pageBottom,
            modifier = modifier, titleActive = titleActive, titleStateDescription = titleStateDescription,
            onTitleClick = onTitleClick, actions = actions, content = content)
        return
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().align(Alignment.TopCenter)) {
            if (onBack != null) {
                SubPageBar(title = title, onBack = onBack, actions = actions)
            } else {
                ScrollingTitleBar(
                    title = title,
                    listState = listState,
                    subtitle = subtitle,
                    compact = compact,
                    actions = actions,
                )
            }
            content(
                androidx.compose.foundation.layout.PaddingValues(
                    // 明细页把胶囊 Tab 栏留在原位（可以在明细里直接切页），所以照样留白。
                    top = if (onBack != null) AbLayout.pageTop else 0.dp,
                    bottom = dockHeight() + navBottom + AbLayout.pageBottom,
                ),
            )
        }
    }
}

/** Read scroll state in the title scope, leaving the page/list content untouched. */
@Composable
private fun ScrollingTitleBar(
    title: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    subtitle: String?,
    compact: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    val offset by remember(listState) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (listState.firstVisibleItemIndex > 0) 140f
            else listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(140f)
        }
    }
    LargeTitleBar(title = title, scrollOffset = offset, subtitle = subtitle, compact = compact, actions = actions)
}

/**
 * 子页导航栏：独立居中标题与右侧动作，系统负责返回。
 *
 * 用于设置里的二级页 —— iOS 里子页标题是居中的 17sp，不是大标题。
 */
@Composable
fun SubPageBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalAbPalette.current
    val reduce = LocalReduceTransparency.current
    // The title owns the geometric centre, independently of trailing actions.
    androidx.compose.ui.layout.SubcomposeLayout(modifier.fillMaxWidth().background(if (reduce) p.cardBg else p.glassTint).statusBarsPadding()) { constraints ->
        val inset = AbSpacing.page.roundToPx()
        val available = (constraints.maxWidth - inset * 2).coerceAtLeast(0)
        val action = subcompose("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s), verticalAlignment = Alignment.CenterVertically, content = actions)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = available / 3))
        val titleWidth = (available - action.width * 2).coerceAtLeast(0)
        val heading = subcompose("title") {
            Text(title, style = AbType.navTitle, color = p.label, textAlign = TextAlign.Center,
                maxLines = 2, modifier = Modifier.fillMaxWidth().semantics { heading() })
        }.first().measure(constraints.copy(minWidth = titleWidth, maxWidth = titleWidth, minHeight = 0))
        val height = maxOf(AbSize.navBarHeight.roundToPx(), heading.height + 8.dp.roundToPx(), action.height)
        layout(constraints.maxWidth, height) {
            heading.placeRelative((constraints.maxWidth - heading.width) / 2, (height - heading.height) / 2)
            action.placeRelative(constraints.maxWidth - inset - action.width, (height - action.height) / 2)
        }
    }
}

/**
 * 子页骨架：固定导航栏 + 可滚动内容。
 *
 * 底部留白和 [PageScaffold] 保持一致：子页上胶囊 Tab 栏照样浮在最上层
 * （由 MainActivity 统一绘制），内容必须给它让出导航条 inset + 胶囊高度。
 */
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 底部是否给悬浮胶囊 Tab 栏留白。
     *
     * 二级页上胶囊照样浮着，所以默认要留；但整页式页面（明细、账单操作）
     * 会先隐藏胶囊，再留这 100dp 就只是列表末尾的一块死白，故传 false。
     */
    reserveDock: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(LocalAbPalette.current.pageBg), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
        SubPageBar(title = title, onBack = onBack, actions = actions)
        content(
            androidx.compose.foundation.layout.PaddingValues(
                top = AbLayout.pageTop,
                bottom = if (reserveDock) dockHeight() + navBottom + AbLayout.pageBottom else navBottom + AbLayout.pageBottom,
            ),
        )
    }
    }
}
