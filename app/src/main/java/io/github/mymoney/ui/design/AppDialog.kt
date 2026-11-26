package io.github.mymoney.ui.design

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 为 Dialog 窗口安全启用 API 31+ 背景景深模糊，并禁用系统默认弹窗淡入以免与 Compose 弹簧动效打架。 */
@Composable
internal fun ConfigureDialogWindowBlur(
    effectiveGlass: Boolean,
    isDark: Boolean,
    blurRadiusDp: Float = 16f,
) {
    val view = LocalView.current
    val density = LocalDensity.current.density
    DisposableEffect(view, effectiveGlass, isDark, density, blurRadiusDp) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            runCatching {
                window.setWindowAnimations(0)
                window.setDimAmount(0f)
                val wm = view.context.getSystemService(WindowManager::class.java)
                val canBlur = effectiveGlass && Build.VERSION.SDK_INT >= 31 && wm?.isCrossWindowBlurEnabled == true
                if (canBlur && Build.VERSION.SDK_INT >= 31) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = (blurRadiusDp * density).roundToInt()
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= 31) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                }
            }
        }
        onDispose { }
    }
}

internal val LocalDialogDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 共享底部 Sheet 顶部抓手与下拉关闭手势（支持松手速度接力）。 */
@Composable
internal fun SheetDragGrabber(
    enabled: Boolean,
    offsetY: Animatable<Float, *>,
    onDragOffsetChange: (Boolean, Float) -> Unit,
    onDismiss: () -> Unit,
    onDragDismiss: ((Float) -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val tracker = remember { VelocityTracker() }
    val currentDrag = remember { floatArrayOf(0f) }
    val rawDrag = remember { floatArrayOf(0f) }
    val maxUpOverdragPx = remember(density) { with(density) { 26.dp.toPx() } }

    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(reduceMotion, enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        currentDrag[0] = offsetY.value
                        rawDrag[0] = currentDrag[0]
                        onDragOffsetChange(true, currentDrag[0])
                        tracker.resetTracking()
                        scope.launch { offsetY.stop() }
                    },
                    onDragEnd = {
                        val released = currentDrag[0]
                        val velocity = tracker.calculateVelocity().y
                        val shouldClose = released > with(density) { 100.dp.toPx() } || velocity > 900f
                        scope.launch {
                            offsetY.snapTo(released)
                            onDragOffsetChange(false, 0f)
                            when {
                                shouldClose && enabled -> onDragDismiss?.invoke(velocity) ?: onDismiss()
                                reduceMotion -> offsetY.animateTo(0f, tween(110, easing = AbEasing.EaseOut))
                                else -> offsetY.animateTo(
                                    0f,
                                    AbSpring.sheetFloat(false),
                                    initialVelocity = velocity,
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        val cancelled = currentDrag[0]
                        scope.launch {
                            offsetY.snapTo(cancelled)
                            onDragOffsetChange(false, 0f)
                            if (reduceMotion) offsetY.animateTo(0f, tween(110, easing = AbEasing.EaseOut))
                            else offsetY.animateTo(0f, AbSpring.sheetFloat(false))
                        }
                    },
                ) { change, delta ->
                    change.consume()
                    rawDrag[0] += delta
                    currentDrag[0] = if (rawDrag[0] < 0f) {
                        val up = -rawDrag[0]
                        -maxUpOverdragPx * (up / (up + maxUpOverdragPx * 1.8f))
                    } else {
                        rawDrag[0]
                    }
                    tracker.addPosition(change.uptimeMillis, Offset(change.position.x, change.position.y + currentDrag[0]))
                    onDragOffsetChange(true, currentDrag[0])
                }
            }
            .semantics { contentDescription = "下滑关闭" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(p.tertiaryLabel.copy(alpha = 0.5f)),
        )
    }
}

/** 底部悬浮 Sheet 统一外边距：水平 8.dp，底部贴合导航手势条或软键盘留白 8.dp。 */
@Composable
internal fun floatingSheetViewportPadding(): PaddingValues {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottom = if (imeBottom > navBottom) imeBottom + 8.dp else maxOf(navBottom + 8.dp, 12.dp)
    return PaddingValues(start = 8.dp, end = 8.dp, bottom = bottom)
}

/**
 * 统一的底部悬浮液态玻璃托盘容器：32.dp 四角同心圆角、高漫射玻璃背板、顶缘镜面光泽与可选底层实时模糊采样。
 */
@Composable
internal fun GlassBottomSheetBox(
    modifier: Modifier = Modifier,
    backdrop: GraphicsLayer? = null,
    backdropOrigin: Offset = Offset.Zero,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val clarity = LocalLiquidGlassClaritySpec.current.sheet
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(AbRadius.sheetFloating)
    var sheetTopLeft by remember { mutableStateOf(Offset.Zero) }
    val useBackdrop = effectiveGlass && Build.VERSION.SDK_INT >= 31 && backdrop != null

    val panelColor = if (effectiveGlass) {
        (if (isDark) p.elevatedBg else p.cardBg).copy(
            alpha = glassSurfaceAlpha(
                baseLight = if (backdrop != null) 0.72f else 0.88f,
                baseDark = if (backdrop != null) 0.70f else 0.84f,
                isDark = isDark,
                clarity = clarity,
            ),
        )
    } else {
        p.cardBg
    }
    val borderModifier = if (effectiveGlass) {
        Modifier.border(0.75.dp, liquidSpecularRimBrush(isDark), shape)
    } else {
        Modifier.border(0.75.dp, p.separator, shape)
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                if (coords.isAttached) sheetTopLeft = coords.boundsInRoot().topLeft
            }
            .shadow(
                elevation = if (effectiveGlass) 24.dp else 8.dp,
                shape = shape,
                clip = false,
                ambientColor = p.glassShadow,
                spotColor = p.glassShadow,
            )
            .clip(shape),
    ) {
        if (useBackdrop) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val blurPx = glassBlurRadiusDp(clarity, baseDp = 16f, minDp = 8f, maxDp = 22f) * density
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    }
                    .drawWithContent {
                        translate(
                            left = -(sheetTopLeft.x - backdropOrigin.x),
                            top = -(sheetTopLeft.y - backdropOrigin.y),
                        ) {
                            drawLayer(backdrop!!)
                        }
                    },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(panelColor)
                .then(
                    if (!effectiveGlass) Modifier
                    else Modifier.drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    p.glassHighlight.copy(alpha = if (isDark) 0.14f else 0.24f),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = size.height * 0.35f,
                            ),
                        )
                    },
                )
                .then(borderModifier),
            content = content,
        )
    }
}

/**
 * Application-owned dialog content. All page dialogs should use this family instead of
 * Material3 AlertDialog so width, typography, actions and busy semantics stay consistent.
 */
@Composable
internal fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val clarity = LocalLiquidGlassClaritySpec.current.dialog
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestDismissible by rememberUpdatedState(dismissible)
    var closing by remember { mutableStateOf(false) }

    val animScale = remember { Animatable(if (reduceMotion) 1f else 0.92f) }
    val animAlpha = remember { Animatable(0f) }

    fun dismissAnimated() {
        if (!latestDismissible || closing) return
        closing = true
        scope.launch {
            try {
                if (reduceMotion) {
                    animAlpha.animateTo(0f, tween(120, easing = AbEasing.EaseOut))
                } else {
                    val scaleJob = launch { animScale.animateTo(0.93f, tween(135, easing = AbEasing.EaseOut)) }
                    val alphaJob = launch { animAlpha.animateTo(0f, tween(130, easing = AbEasing.EaseOut)) }
                    scaleJob.join()
                    alphaJob.join()
                }
                latestOnDismiss()
            } finally {
                closing = false
            }
        }
    }

    Dialog(
        onDismissRequest = { dismissAnimated() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ConfigureDialogWindowBlur(effectiveGlass = effectiveGlass, isDark = isDark, blurRadiusDp = 14f)

        LaunchedEffect(reduceMotion) {
            if (reduceMotion) {
                animScale.snapTo(1f)
                animAlpha.animateTo(1f, tween(150, easing = AbEasing.EaseOut))
            } else {
                launch { animScale.animateTo(1f, AbSpring.modalFloat(false)) }
                launch { animAlpha.animateTo(1f, tween(160, easing = AbEasing.EaseOut)) }
            }
        }

        val shape = RoundedCornerShape(AbRadius.dialog)
        val panelColor = if (effectiveGlass) {
            (if (isDark) p.elevatedBg else p.cardBg).copy(
                alpha = glassSurfaceAlpha(0.88f, 0.84f, isDark, clarity),
            )
        } else {
            p.cardBg
        }
        val borderModifier = if (effectiveGlass) {
            Modifier.border(0.75.dp, liquidSpecularRimBrush(isDark), shape)
        } else {
            Modifier.border(0.75.dp, p.separator, shape)
        }
        val targetDim = if (isDark) 0.40f else 0.24f

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = targetDim * animAlpha.value)) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = latestDismissible && !closing,
                    onClick = { dismissAnimated() },
                )
                .statusBarsPadding()
                .imePadding()
                .padding(vertical = AbSpacing.m),
            contentAlignment = Alignment.Center,
        ) {
            val dialogMaxHeight = (maxHeight * 0.90f).coerceAtLeast(480.dp)
            Box(
                modifier = Modifier
                    .padding(horizontal = AbSpacing.xl)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = animScale.value
                        scaleY = animScale.value
                        alpha = animAlpha.value
                    }
                    .shadow(
                        elevation = if (effectiveGlass) 24.dp else 8.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = p.glassShadow,
                        spotColor = p.glassShadow,
                    )
                    .clip(shape)
                    .background(panelColor)
                    .then(borderModifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .then(
                        if (!effectiveGlass) Modifier
                        else Modifier.drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        p.glassHighlight.copy(alpha = if (isDark) 0.10f else 0.16f),
                                        Color.Transparent,
                                    ),
                                    startY = 0f,
                                    endY = size.height * 0.28f,
                                ),
                            )
                        },
                    ),
            ) {
                CompositionLocalProvider(
                    LocalInModalSurface provides true,
                    LocalDialogDismiss provides { dismissAnimated() },
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = dialogMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp)
                            .semantics { paneTitle = title },
                        verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
                    ) {
                        Text(title, style = AbType.navTitle, color = p.label)
                        content()
                    }
                }
            }
        }
    }
}

/** Shared dialog action row: cancel on the left, confirm on the right, equal width. */
@Composable
internal fun AppDialogActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = "取消",
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    busyLabel: String = "处理中…",
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        val animatedDismiss = LocalDialogDismiss.current
        if (dismissLabel != null && onDismiss != null) {
            SecondaryButton(
                dismissLabel,
                onClick = { animatedDismiss?.invoke() ?: onDismiss() },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
        if (destructive) {
            DestructiveButton(
                confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                busy = busy,
                busyLabel = busyLabel,
            )
        } else {
            PrimaryButton(
                confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                busy = busy,
                busyLabel = busyLabel,
            )
        }
    }
}

/** Plain confirmation/info dialog with a message body. */
@Composable
internal fun AppAlertDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit,
    dismissLabel: String? = "取消",
    destructive: Boolean = false,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    busyLabel: String = "处理中…",
) {
    AppDialog(title, onDismiss = { if (!busy) onDismiss() }, dismissible = !busy) {
        Text(message, style = AbType.body, color = LocalAbPalette.current.secondaryLabel)
        AppDialogActions(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            dismissLabel = dismissLabel,
            onDismiss = onDismiss,
            destructive = destructive,
            busy = busy,
            confirmEnabled = confirmEnabled,
            busyLabel = busyLabel,
        )
    }
}

/** A labelled option used by [AppChoiceDialog] and [GlassContextMenuHost]. */
internal data class AppDialogChoice(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val destructive: Boolean = false,
)

/** Single-choice dialog rendered as iOS-style list rows with a trailing check mark. */
@Composable
internal fun AppChoiceDialog(
    title: String,
    options: List<AppDialogChoice>,
    selectedKey: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalAbPalette.current
    AppDialog(title, onDismiss = onDismiss) {
        InsetGroup {
            options.forEachIndexed { index, option ->
                ListRow(
                    title = option.title,
                    subtitle = option.subtitle,
                    dividerAbove = index > 0,
                    trailing = {
                        if (option.key == selectedKey) {
                            Icon(AbIcons.Check, contentDescription = "已选", modifier = Modifier.size(16.dp), tint = palette.accent)
                        }
                    },
                    onClick = { onChoose(option.key) },
                )
            }
        }
        val animatedDismiss = LocalDialogDismiss.current
        SecondaryButton("取消", onClick = { animatedDismiss?.invoke() ?: onDismiss() }, modifier = Modifier.fillMaxWidth())
    }
}

/** Controlled single-field input dialog; the caller owns validation and the value. */
@Composable
internal fun AppInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "保存",
    placeholder: String = "",
    error: String? = null,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    AppDialog(title, onDismiss = { if (!busy) onDismiss() }, dismissible = !busy) {
        InsetGroup {
            InputRow(label = label, value = value, onValueChange = onValueChange, placeholder = placeholder)
        }
        error?.let { InlineMessage(it, isError = true, announce = true) }
        AppDialogActions(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            dismissLabel = "取消",
            onDismiss = onDismiss,
            busy = busy,
            confirmEnabled = confirmEnabled,
        )
    }
}

/** One action in [AppActionSheet]. */
internal data class AppAction(
    val label: String,
    val subtitle: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Bottom action sheet used instead of Material3 DropdownMenu. */
@Composable
internal fun AppActionSheet(
    actions: List<AppAction>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val slideStartPx = minOf(screenHeightPx * 0.45f, with(density) { 360.dp.toPx() })
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    val offsetY = remember { Animatable(if (reduceMotion) 0f else slideStartPx) }
    val animAlpha = remember { Animatable(if (reduceMotion) 0f else 1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }

    fun dismissAnimated(velocity: Float? = null) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                if (reduceMotion) {
                    animAlpha.animateTo(0f, tween(120, easing = AbEasing.EaseOut))
                } else if (velocity != null) {
                    offsetY.animateTo(slideStartPx, AbSpring.sheetFloat(false), initialVelocity = velocity)
                } else {
                    offsetY.animateTo(slideStartPx, tween(170, easing = AbEasing.EaseOut))
                }
                latestOnDismiss()
            } finally {
                closing = false
            }
        }
    }

    Dialog(
        onDismissRequest = { dismissAnimated() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ConfigureDialogWindowBlur(effectiveGlass = effectiveGlass, isDark = isDark, blurRadiusDp = 18f)
        LaunchedEffect(reduceMotion) {
            if (reduceMotion) {
                offsetY.snapTo(0f)
                animAlpha.animateTo(1f, tween(150, easing = AbEasing.EaseOut))
            } else {
                animAlpha.snapTo(1f)
                offsetY.animateTo(0f, AbSpring.sheetFloat(false))
            }
        }
        val maxDim = if (isDark) 0.40f else 0.24f
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val y = if (dragging) dragOffset else offsetY.value
                    val progress = (1f - (y / slideStartPx.coerceAtLeast(1f))).coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = maxDim * progress * animAlpha.value))
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !closing,
                    onClick = { dismissAnimated() },
                )
                .statusBarsPadding()
                .padding(floatingSheetViewportPadding()),
            contentAlignment = Alignment.BottomCenter,
        ) {
            GlassBottomSheetBox(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else offsetY.value
                        alpha = animAlpha.value
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                CompositionLocalProvider(LocalInModalSurface provides true) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = AbSpacing.m, end = AbSpacing.m, top = AbSpacing.xxs, bottom = AbSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
                    ) {
                        SheetDragGrabber(
                            enabled = !closing,
                            offsetY = offsetY,
                            onDragOffsetChange = { isDrag, nextOffset ->
                                dragging = isDrag
                                dragOffset = nextOffset
                            },
                            onDismiss = { dismissAnimated() },
                            onDragDismiss = { v -> dismissAnimated(v) },
                        )
                        if (title != null) {
                            Text(
                                title,
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                                modifier = Modifier.padding(horizontal = AbSpacing.m, vertical = AbSpacing.xs),
                            )
                        }
                        InsetGroup {
                            actions.forEachIndexed { index, action ->
                                ListRow(
                                    title = action.label,
                                    subtitle = action.subtitle,
                                    dividerAbove = index > 0,
                                    destructive = action.destructive,
                                    onClick = {
                                        dismissAnimated()
                                        action.onClick()
                                    },
                                )
                            }
                        }
                        SecondaryButton("取消", onClick = { dismissAnimated() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
