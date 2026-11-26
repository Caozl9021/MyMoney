package io.github.mymoney.ui.design

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Only the page body is recorded: neither capsule can ever sample itself or its label. */
@Stable
internal class HeaderBackdrop(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
}

internal val LocalHeaderBackdrop = staticCompositionLocalOf<HeaderBackdrop?> { null }

@Composable
internal fun FloatingPageScaffold(
    title: String,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalAbPalette.current
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val capsuleHeight = 42.dp * density.fontScale.coerceAtLeast(1f)
    val headerHeight = maxOf(48.dp, capsuleHeight)
    val topPadding = statusTop + AbSpacing.xs + headerHeight + AbSpacing.s
    val source = rememberGraphicsLayer()
    val backdrop = remember(source) { HeaderBackdrop(source) }
    val sample = Build.VERSION.SDK_INT >= 31 && LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    Box(modifier.fillMaxSize()) {
        Box(Modifier.widthIn(max = 560.dp).fillMaxSize().align(Alignment.TopCenter)
            .onGloballyPositioned { backdrop.coordinates = it }) {
            Box(Modifier.fillMaxSize()
                .then(if (sample) Modifier.drawWithContent {
                    source.record { this@drawWithContent.drawContent() }
                    drawLayer(source)
                } else Modifier)
                .background(palette.pageBg)) {
                content(PaddingValues(top = topPadding, bottom = bottomPadding))
            }
            // Soft Scroll Edge veil beneath the status bar & floating capsules so scrolling cards dissolve smoothly.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusTop + 20.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to palette.pageBg,
                            0.55f to palette.pageBg.copy(alpha = 0.84f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            CompositionLocalProvider(LocalHeaderBackdrop provides if (sample) backdrop else null) {
                Row(Modifier.fillMaxWidth().padding(start = AbSpacing.page, end = AbSpacing.page,
                    top = statusTop + AbSpacing.xs).heightIn(min = headerHeight), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                    val titleInteraction = remember(onTitleClick != null) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(Modifier.heightIn(min = headerHeight)
                        .then(
                            if (onTitleClick == null) Modifier
                            else Modifier
                                .abPressFeedback(titleInteraction, pressedScale = 0.96f, pressedAlpha = 0.78f)
                                .clickable(
                                    interactionSource = titleInteraction,
                                    indication = null,
                                    onClickLabel = "查看自动记账模式",
                                    onClick = onTitleClick,
                                ),
                        )
                        .semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
                        HeaderGlassCapsule(Modifier.heightIn(min = capsuleHeight)) {
                            Text(title, style = AbType.title2.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                                color = palette.label, maxLines = 1,
                                modifier = Modifier.padding(horizontal = AbSpacing.l, vertical = AbSpacing.xs + AbSpacing.xxs)
                                    .semantics { heading() })

                        }
                    }
                    Spacer(Modifier.weight(1f))
                    actions()
                }
            }
        }
    }
}

/** Icon-only visual, with a full 48dp button and an explicit spoken action name. */
@Composable
fun FloatingHeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    // Offset +5dp so the 38dp visual capsule's right edge aligns with AbSpacing.page (16dp).
    GlassCircleIconButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        modifier = Modifier.offset(x = 5.dp),
    )
}

/** 圆形玻璃图标按钮：48dp 触控热区、无水波纹，支持在 [HeaderBackdrop] 下自动采样折射。 */
@Composable
fun GlassCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    visualSize: Dp = 38.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val haptic = rememberAbHaptic()
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduceMotion) 0.92f else 1f,
        AbSpring.bouncyFloat(reduceMotion),
        label = "headerPress",
    )
    val p = LocalAbPalette.current
    val resolvedTint = (tint ?: p.accent).let { if (enabled) it else it.copy(alpha = 0.45f) }
    val touchSize = visualSize.coerceAtLeast(48.dp)
    Box(
        modifier = modifier.size(touchSize),
        contentAlignment = Alignment.Center,
    ) {
        HeaderGlassCapsule(Modifier.size(visualSize)) { }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    haptic.select()
                    onClick()
                }
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = resolvedTint,
                modifier = Modifier.size(iconSize).graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }
    }
}

@Composable
internal fun HeaderGlassCapsule(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalAbPalette.current
    val backdrop = LocalHeaderBackdrop.current
    val reduce = !LocalLiquidGlassEnabled.current || LocalReduceTransparency.current
    val clarity = LocalLiquidGlassClaritySpec.current.header
    val density = LocalDensity.current.density
    val dark = p.pageBg.luminance() < 0.1f
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurDp = remember(clarity) { glassBlurRadiusDp(clarity, baseDp = 4.5f, minDp = 1.8f, maxDp = 11.0f) }
    val lensHolder = remember(backdrop, density) {
        if (backdrop != null && Build.VERSION.SDK_INT >= 33) HeaderGlassApi33Holder(density, blurDp) else null
    }
    val effect = remember(measuredSize, density, backdrop, lensHolder, blurDp) {
        if (backdrop == null || measuredSize == IntSize.Zero) null
        else if (Build.VERSION.SDK_INT >= 33 && lensHolder != null) {
            runCatching { lensHolder.effect(measuredSize, blurDp) }
                .getOrElse {
                    android.util.Log.w("FloatingHeader", "Edge effect unavailable; using background blur", it)
                    BlurEffect(blurDp * density, blurDp * density, TileMode.Clamp)
                }
        } else BlurEffect(blurDp * density, blurDp * density, TileMode.Clamp)
    }
    val shape = RoundedCornerShape(AbRadius.capsule)
    val rim = remember(dark) { liquidSpecularRimBrush(dark) }
    val tintAlpha = when {
        reduce -> 1f
        backdrop == null -> glassSurfaceAlpha(0.84f, 0.80f, dark, clarity)
        else -> glassSurfaceAlpha(0.32f, 0.44f, dark, clarity)
    }
    val glassBaseColor = if (reduce) p.cardBg else if (dark) p.cardBg else Color.White
    val tintColor = glassBaseColor.copy(alpha = tintAlpha)
    Box(modifier.onSizeChanged { measuredSize = it }
        .onGloballyPositioned { placed ->
            val origin = backdrop?.coordinates
            if (origin != null && origin.isAttached && placed.isAttached)
                position = origin.localPositionOf(placed, Offset.Zero)
        }
        .shadow(
            elevation = if (reduce) 2.dp else 9.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.28f else 0.08f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.34f else 0.11f),
        )
        .clip(shape), contentAlignment = Alignment.Center) {
        if (backdrop != null && !reduce) Box(Modifier.matchParentSize()
            .graphicsLayer { renderEffect = effect }
            .drawWithContent {
                translate(-position.x, -position.y) { drawLayer(backdrop.layer) }
            })
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(tintColor)
            val radius = minOf(size.width, size.height) * 0.5f
            if (!reduce) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.10f else 0.22f),
                        0.48f to Color.Transparent,
                    ),
                )
                val outerGlassRim = 0.45.dp.toPx()
                drawRoundRect(
                    color = if (dark) Color(0x28000000) else Color(0x12000000),
                    topLeft = Offset(outerGlassRim * 0.5f, outerGlassRim * 0.5f),
                    size = Size((size.width - outerGlassRim).coerceAtLeast(1f), (size.height - outerGlassRim).coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(outerGlassRim),
                )
                val hairline = 0.9.dp.toPx()
                drawRoundRect(
                    brush = rim,
                    topLeft = Offset(outerGlassRim + hairline * 0.5f, outerGlassRim + hairline * 0.5f),
                    size = Size(
                        (size.width - (outerGlassRim * 2f + hairline)).coerceAtLeast(1f),
                        (size.height - (outerGlassRim * 2f + hairline)).coerceAtLeast(1f),
                    ),
                    cornerRadius = CornerRadius((radius - outerGlassRim).coerceAtLeast(1f)),
                    style = Stroke(hairline),
                )
            } else {
                val flatStroke = 0.65.dp.toPx()
                drawRoundRect(
                    color = p.separator.copy(alpha = 0.55f),
                    topLeft = Offset(flatStroke * 0.5f, flatStroke * 0.5f),
                    size = Size((size.width - flatStroke).coerceAtLeast(1f), (size.height - flatStroke).coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(flatStroke),
                )
            }
        })
        content()
    }
}

/** 缓存每个玻璃胶囊的 RuntimeShader 与高斯模糊实例，结合边缘凸镜折射与微色散呈现真实 iOS 27 玻璃边壁质感。 */
@RequiresApi(33)
private class HeaderGlassApi33Holder(private val density: Float, initialBlurDp: Float) {
    private val shader = android.graphics.RuntimeShader(PROGRAM).apply {
        setFloatUniform("density", density)
        setFloatUniform("refractionBand", 11.5f * density)
        setFloatUniform("refractionAmount", 5.5f * density)
        setFloatUniform("dispersion", 0.12f)
    }
    private var lastBlurDp = initialBlurDp
    private var blur = android.graphics.RenderEffect.createBlurEffect(
        initialBlurDp * density,
        initialBlurDp * density,
        android.graphics.Shader.TileMode.CLAMP,
    )

    fun effect(size: IntSize, blurDp: Float): RenderEffect {
        if (kotlin.math.abs(blurDp - lastBlurDp) > 0.25f) {
            lastBlurDp = blurDp
            blur = android.graphics.RenderEffect.createBlurEffect(
                blurDp * density,
                blurDp * density,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        shader.setFloatUniform("dimensions", size.width.toFloat(), size.height.toFloat())
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "body")
        return android.graphics.RenderEffect.createChainEffect(lens, blur).asComposeRenderEffect()
    }

    private companion object {
        private const val PROGRAM = """
            uniform shader body;
            uniform float2 dimensions;
            uniform float density;
            uniform float refractionBand;
            uniform float refractionAmount;
            uniform float dispersion;
            half4 main(float2 xy) {
                float radius = min(dimensions.x, dimensions.y) * 0.5;
                float2 axis = float2(clamp(xy.x, radius, max(radius, dimensions.x - radius)), dimensions.y * 0.5);
                float2 delta = xy - axis;
                float distance = length(delta);
                float startDist = max(0.0, radius - min(radius, refractionBand));
                float edge = smoothstep(startDist, radius, distance);
                float curve = edge * edge * (3.0 - 2.0 * edge);
                float2 normal = delta / max(distance, 0.001);
                float2 maxXY = max(float2(1.0), dimensions - float2(1.0));
                float2 refractOffset = normal * curve * refractionAmount;
                half4 center = body.eval(clamp(xy - refractOffset, float2(0.0), maxXY));
                if (dispersion <= 0.001 || curve <= 0.01) {
                    return center;
                }
                float2 dispOffset = normal * curve * (dispersion * 7.5 * density);
                half4 r = body.eval(clamp(xy - refractOffset - dispOffset, float2(0.0), maxXY));
                half4 b = body.eval(clamp(xy - refractOffset + dispOffset, float2(0.0), maxXY));
                return half4(r.r, center.g, b.b, center.a);
            }
        """
    }
}
