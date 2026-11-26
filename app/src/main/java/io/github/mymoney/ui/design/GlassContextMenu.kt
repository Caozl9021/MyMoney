package io.github.mymoney.ui.design

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

internal data class GlassMenuRequest(
    val title: String?,
    val options: List<AppDialogChoice>,
    val selectedKey: String?,
    val anchor: Rect,
    val onSelect: (String) -> Unit,
)

@Stable
internal class GlassContextMenuState {
    var generation by mutableIntStateOf(0)
        private set
    var open by mutableStateOf(false)
        private set
    var request by mutableStateOf<GlassMenuRequest?>(null)
        private set

    fun show(
        title: String? = null,
        options: List<AppDialogChoice>,
        selectedKey: String? = null,
        anchor: Rect,
        onSelect: (String) -> Unit,
    ) {
        if (options.isEmpty()) return
        generation++
        request = GlassMenuRequest(title, options, selectedKey, anchor, onSelect)
        open = true
    }

    fun dismiss() {
        open = false
    }
}

internal val LocalGlassContextMenu = staticCompositionLocalOf<GlassContextMenuState?> { null }

/**
 * 同窗按需采样上下文液态玻璃菜单宿主：
 * 静止关闭时零 `GraphicsLayer` 录制开销；仅当菜单打开或收起过渡时采样宿主并施以 `BlurEffect`。
 */
@Composable
internal fun GlassContextMenuHost(
    backEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = remember { GlassContextMenuState() }
    val backdrop = rememberGraphicsLayer()
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val clarity = LocalLiquidGlassClaritySpec.current.dialog
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(state.open, state.generation, reduceMotion) {
        if (state.open) {
            if (reduceMotion) {
                animProgress.snapTo(0.02f)
                animProgress.animateTo(1f, tween(140, easing = AbEasing.EaseOut))
            } else {
                animProgress.snapTo(0.02f)
                animProgress.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 560f))
            }
        } else {
            if (reduceMotion) {
                animProgress.animateTo(0f, tween(110, easing = AbEasing.EaseOut))
            } else {
                animProgress.animateTo(0f, androidx.compose.animation.core.tween(130, easing = AbEasing.EaseOut))
            }
        }
    }

    val visible = state.open || animProgress.value > 0.01f
    val sampleBackdrop = effectiveGlass && Build.VERSION.SDK_INT >= 31 && visible

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (sampleBackdrop) Modifier.drawWithContent {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    } else Modifier,
                ),
        ) {
            CompositionLocalProvider(LocalGlassContextMenu provides state) {
                content()
            }
        }

        BackHandler(enabled = state.open && backEnabled) {
            state.dismiss()
        }

        val activeRequest = state.request
        if (visible && activeRequest != null) {
            val p = LocalAbPalette.current
            val isDark = p.pageBg.luminance() < 0.1f
            val density = LocalDensity.current
            val haptic = rememberAbHaptic()

            BoxWithConstraints(Modifier.matchParentSize()) {
                val marginPx = with(density) { 12.dp.toPx() }
                val gapPx = with(density) { 6.dp.toPx() }
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
                val safeBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()

                val hasSubtitles = activeRequest.options.any { !it.subtitle.isNullOrBlank() }
                val menuWidthDp = (if (hasSubtitles) 252.dp else 224.dp)
                val menuWidthPx = with(density) { menuWidthDp.toPx() }
                    .coerceAtMost((maxW - marginPx * 2f).coerceAtLeast(1f))
                val estHeightPx = with(density) {
                    (activeRequest.options.size * (if (hasSubtitles) 60 else 48) +
                        (if (activeRequest.title != null) 34 else 0) + 16).dp.toPx()
                }.coerceAtMost(with(density) { 320.dp.toPx() })
                var measuredH by remember(state.generation) { mutableFloatStateOf(estHeightPx) }

                val relLeft = activeRequest.anchor.left - hostOrigin.x
                val relRight = activeRequest.anchor.right - hostOrigin.x
                val relTop = activeRequest.anchor.top - hostOrigin.y
                val relBottom = activeRequest.anchor.bottom - hostOrigin.y

                val preferX = if (activeRequest.anchor.width < menuWidthPx) {
                    relLeft
                } else {
                    (relLeft + relRight - menuWidthPx) * 0.5f
                }
                val x = preferX.coerceIn(marginPx, (maxW - marginPx - menuWidthPx).coerceAtLeast(marginPx))

                val usableBottom = (maxH - safeBottom - marginPx).coerceAtLeast(safeTop + 80f)
                val spaceBelow = usableBottom - relBottom - gapPx
                val spaceAbove = relTop - safeTop - gapPx
                val placeAbove = spaceBelow < measuredH && spaceAbove > spaceBelow
                val rawY = if (placeAbove) relTop - measuredH - gapPx else relBottom + gapPx
                val y = rawY.coerceIn(safeTop, (usableBottom - measuredH).coerceAtLeast(safeTop))

                val pivotX = (((relLeft + relRight) * 0.5f - x) / menuWidthPx).coerceIn(0.15f, 0.85f)
                val pivotY = if (placeAbove) 1f else 0f

                // 全屏透明拦截层，点击菜单外立即收起
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { state.dismiss() }
                        .semantics { contentDescription = "关闭菜单" },
                )

                val progress = animProgress.value.coerceIn(0f, 1f)
                val scale = if (reduceMotion) 1f else lerp(0.86f, 1f, progress)
                val shape = RoundedCornerShape(AbRadius.popover)
                val panelColor = if (effectiveGlass) {
                    (if (isDark) p.elevatedBg else p.cardBg).copy(
                        alpha = glassSurfaceAlpha(0.84f, 0.80f, isDark, clarity),
                    )
                } else {
                    p.elevatedBg
                }
                val borderBrush = liquidSpecularRimBrush(isDark)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .width(with(density) { menuWidthPx.toDp() })
                        .onSizeChanged { measuredH = it.height.toFloat() }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = progress
                            transformOrigin = TransformOrigin(pivotX, pivotY)
                        }
                        .shadow(
                            elevation = if (effectiveGlass) 20.dp else 10.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = p.glassShadow,
                            spotColor = p.glassShadow,
                        )
                        .clip(shape)
                        .then(
                            if (effectiveGlass) Modifier.border(0.75.dp, borderBrush, shape)
                            else Modifier.border(0.5.dp, p.separator, shape),
                        )
                        .semantics {
                            paneTitle = activeRequest.title ?: "操作菜单"
                        },
                ) {
                    if (sampleBackdrop) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    val blurPx = 14f * density.density
                                    renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                                }
                                .drawWithContent {
                                    translate(-x, -y) { drawLayer(backdrop) }
                                },
                        )
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(panelColor)
                            .then(
                                if (!effectiveGlass) Modifier
                                else Modifier.drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                p.glassHighlight.copy(alpha = if (isDark) 0.14f else 0.24f),
                                                Color.Transparent,
                                            ),
                                            startY = 0f,
                                            endY = size.height * 0.40f,
                                        ),
                                    )
                                },
                            ),
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        activeRequest.title?.let { header ->
                            Text(
                                text = header,
                                style = AbType.caption1,
                                color = p.secondaryLabel,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        activeRequest.options.forEach { option ->
                            val picked = option.key == activeRequest.selectedKey
                            val itemInteraction = remember(option.key) { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .abPressFeedback(itemInteraction, pressedScale = 0.985f, pressedAlpha = 0.74f)
                                    .clickable(
                                        interactionSource = itemInteraction,
                                        indication = null,
                                    ) {
                                        haptic.select()
                                        state.dismiss()
                                        activeRequest.onSelect(option.key)
                                    }
                                    .semantics {
                                        role = Role.Button
                                        this.selected = picked
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        text = option.title,
                                        style = AbType.body.copy(
                                            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = when {
                                            option.destructive -> p.red
                                            picked -> p.accent
                                            else -> p.label
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    option.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                                        Text(
                                            text = sub,
                                            style = AbType.caption1,
                                            color = p.secondaryLabel,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (picked) {
                                    Icon(
                                        imageVector = AbIcons.Check,
                                        contentDescription = "已选",
                                        modifier = Modifier.size(16.dp),
                                        tint = p.accent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
