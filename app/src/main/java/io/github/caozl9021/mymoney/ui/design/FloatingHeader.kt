package io.github.caozl9021.mymoney.ui.design

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Only the page body is recorded: neither capsule can ever sample itself or its label. */
@Stable
internal class HeaderBackdrop(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
}

private val LocalHeaderBackdrop = staticCompositionLocalOf<HeaderBackdrop?> { null }

@Composable
internal fun FloatingPageScaffold(
    title: String,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    titleActive: Boolean = false,
    titleStateDescription: String? = null,
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
    val sample = Build.VERSION.SDK_INT >= 31 && !LocalReduceTransparency.current
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
            // Keep system icons over a quiet safe area while body content passes below it.
            Box(Modifier.fillMaxWidth().height(statusTop).background(palette.pageBg))
            CompositionLocalProvider(LocalHeaderBackdrop provides if (sample) backdrop else null) {
                Row(Modifier.fillMaxWidth().padding(start = AbSpacing.page, end = AbSpacing.page,
                    top = statusTop + AbSpacing.xs).heightIn(min = headerHeight), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                    Box(Modifier.heightIn(min = headerHeight)
                        .then(if (onTitleClick == null) Modifier else Modifier.clickable(
                            onClickLabel = "查看自动记账模式", onClick = onTitleClick))
                        .semantics(mergeDescendants = true) {
                            titleStateDescription?.let { stateDescription = it }
                        }, contentAlignment = Alignment.Center) {
                        HeaderGlassCapsule(Modifier.heightIn(min = capsuleHeight)) {
                            Text(title, style = AbType.title2.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                                color = palette.label, maxLines = 1,
                                modifier = Modifier.padding(horizontal = AbSpacing.l, vertical = AbSpacing.xs + AbSpacing.xxs)
                                    .semantics { heading() })
                            if (titleActive) Box(Modifier.matchParentSize().drawWithCache {
                                val inset = 3.dp.toPx()
                                val stroke = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                                onDrawBehind {
                                    drawRoundRect(palette.green, topLeft = Offset(inset, inset),
                                        size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                                        cornerRadius = CornerRadius((size.height - inset * 2) / 2), style = stroke)
                                }
                            })
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
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.95f else 1f,
        tween(if (reduceMotion) 0 else 120), label = "headerPress")
    val p = LocalAbPalette.current
    // Keep hit testing on a stable, standard foreground button. Sampling and press
    // transforms belong to separate children and cannot move its touch coordinates.
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        HeaderGlassCapsule(Modifier.size(38.dp)) { }
        IconButton(onClick = onClick, interactionSource = interactions,
            modifier = Modifier.matchParentSize().semantics { contentDescription = label }) {
            Icon(icon, contentDescription = null, tint = p.accent,
                modifier = Modifier.size(20.dp).graphicsLayer { scaleX = scale; scaleY = scale })
        }
    }
}

@Composable
private fun HeaderGlassCapsule(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalAbPalette.current
    val backdrop = LocalHeaderBackdrop.current
    val reduce = LocalReduceTransparency.current
    val density = LocalDensity.current.density
    val dark = p.pageBg.luminance() < 0.1f
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val effect = remember(measuredSize, density, backdrop) {
        if (backdrop == null || measuredSize == IntSize.Zero) null
        else if (Build.VERSION.SDK_INT >= 33) {
            runCatching { HeaderGlassApi33.effect(measuredSize, density) }
                .getOrElse {
                    android.util.Log.w("FloatingHeader", "Edge effect unavailable; using background blur", it)
                    BlurEffect(12f * density, 12f * density, TileMode.Clamp)
                }
        } else BlurEffect(12f * density, 12f * density, TileMode.Clamp)
    }
    val shape = RoundedCornerShape(AbRadius.capsule)
    Box(modifier.onSizeChanged { measuredSize = it }
        .onGloballyPositioned { placed ->
            val origin = backdrop?.coordinates
            if (origin != null && origin.isAttached && placed.isAttached)
                position = origin.localPositionOf(placed, Offset.Zero)
        }
        .shadow(10.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.80f else 0.45f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.80f else 0.45f))
        .clip(shape), contentAlignment = Alignment.Center) {
        if (backdrop != null) Box(Modifier.matchParentSize()
            .graphicsLayer { renderEffect = effect }
            .drawWithContent {
                translate(-position.x, -position.y) { drawLayer(backdrop.layer) }
            })
        Box(Modifier.matchParentSize().drawWithCache {
            val tint = if (reduce) p.cardBg else p.cardBg.copy(alpha = if (backdrop == null) 0.94f else if (dark) 0.68f else 0.52f)
            val sheen = Brush.verticalGradient(listOf(p.glassHighlight.copy(alpha = if (dark) 0.10f else 0.20f),
                p.glassHighlight.copy(alpha = 0f)))
            val rim = Brush.verticalGradient(listOf(p.glassStrokeTop, p.glassStrokeBottom))
            onDrawBehind {
                drawRect(tint)
                if (!reduce) {
                    drawRect(sheen)
                    val stroke = 1.dp.toPx()
                    drawRoundRect(rim, topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(size.height / 2), style = Stroke(stroke))
                }
            }
        })
        content()
    }
}

/** The shader only sees the blurred background. Foreground text/icons are never filtered. */
@RequiresApi(33)
private object HeaderGlassApi33 {
    private const val PROGRAM = """
        uniform shader body;
        uniform float2 dimensions;
        uniform float density;
        half4 main(float2 xy) {
            float radius = dimensions.y * 0.5;
            float2 axis = float2(clamp(xy.x, radius, max(radius, dimensions.x - radius)), radius);
            float2 delta = xy - axis;
            float distance = length(delta);
            float edge = smoothstep(max(0.0, radius - 7.0 * density), radius, distance);
            float2 normal = delta / max(distance, 0.001);
            return body.eval(xy - normal * edge * 1.8 * density);
        }
    """

    fun effect(size: IntSize, density: Float): RenderEffect {
        val shader = android.graphics.RuntimeShader(PROGRAM).apply {
            setFloatUniform("dimensions", size.width.toFloat(), size.height.toFloat())
            setFloatUniform("density", density)
        }
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "body")
        val blur = android.graphics.RenderEffect.createBlurEffect(12f * density, 12f * density,
            android.graphics.Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createChainEffect(lens, blur).asComposeRenderEffect()
    }
}
