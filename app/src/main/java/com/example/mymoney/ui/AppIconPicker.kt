package com.example.mymoney.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mymoney.R
import com.example.mymoney.systems.LauncherIconChoice
import com.example.mymoney.systems.LauncherIcons
import com.example.mymoney.ui.design.BrandMotion
import com.example.mymoney.ui.design.LocalReduceMotion
import com.example.mymoney.ui.design.AbType
import com.example.mymoney.ui.design.AbSpacing
import com.example.mymoney.ui.design.SectionRole
import com.example.mymoney.ui.design.GroupFooter
import com.example.mymoney.ui.design.GroupHeader
import com.example.mymoney.ui.design.LocalAbPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppIconPicker() {
    val context = LocalContext.current.applicationContext
    val p = LocalAbPalette.current
    val selected by LauncherIcons.selection.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    var pressing by remember { mutableStateOf<LauncherIconChoice?>(null) }
    val bounds = remember { mutableStateMapOf<LauncherIconChoice, Rect>() }
    var flowSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textConstraints = with(density) { Constraints(maxWidth = 80.dp.roundToPx()) }
    // Reserve the same measured label/status space for every card, including large system fonts.
    val titleHeight = with(density) {
        listOf("青绿（默认）", "蓝白", "红金").maxOf {
            measurer.measure(it, style = AbType.footnote, constraints = textConstraints).size.height
        }.toDp()
    }
    val statusHeight = with(density) {
        measurer.measure("✓ 已选择", style = AbType.caption1, constraints = textConstraints).size.height.toDp()
    }
    GroupHeader("应用图标")
    Box(Modifier.fillMaxWidth()) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().onSizeChanged { flowSize = it },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LauncherIconChoice.entries.forEach { choice ->
            val active = choice == selected
            val shape = RoundedCornerShape(18.dp)
            Column(
                modifier = Modifier.width(104.dp).clip(shape)
                    .background(p.cardBg)
                    .border(1.dp, p.separator, shape)
                    .onGloballyPositioned { bounds[choice] = it.boundsInParent() }
                    .selectable(selected = active, enabled = !busy, role = Role.RadioButton) {
                        if (choice == selected) return@selectable
                        busy = true
                        feedback = null
                        scope.launch {
                            try {
                                if (!reduceMotion) {
                                    pressing = choice
                                    scale.animateTo(BrandMotion.PRESSED_SCALE, tween(BrandMotion.PRESS_DOWN_MS))
                                    scale.animateTo(1f, tween(BrandMotion.PRESS_UP_MS))
                                }
                                val result = withContext(Dispatchers.IO) { LauncherIcons.select(context, choice) }
                                feedback = if (result.success) "桌面图标可能稍后刷新" else "图标切换失败，请重试"
                            } catch (_: Exception) {
                                feedback = "图标切换失败，请重试"
                            } finally { pressing = null; busy = false }
                        }
                    }.semantics { stateDescription = if (active) "已选择" else "未选择" }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val preview = when (choice) {
                    LauncherIconChoice.GREEN -> R.drawable.ic_brand_color
                    LauncherIconChoice.BLUE -> R.drawable.ic_brand_color_logo2
                    LauncherIconChoice.RED -> R.drawable.ic_brand_color_logo3
                }
                Image(painterResource(preview), contentDescription = null, modifier = Modifier.size(64.dp).graphicsLayer {
                    scaleX = if (pressing == choice) scale.value else 1f
                    scaleY = scaleX
                })
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(titleHeight), contentAlignment = Alignment.Center) {
                    Text(if (choice == LauncherIconChoice.GREEN) "青绿（默认）" else choice.title,
                        style = AbType.footnote, color = p.label, textAlign = TextAlign.Center)
                }
                Box(Modifier.height(statusHeight), contentAlignment = Alignment.Center) {
                    Text(if (active) "✓ 已选择" else " ", style = AbType.caption1, color = p.accent)
                }
            }
        }
    }
    selected?.let { choice -> bounds[choice]?.let { target ->
        key(flowSize, density.density, density.fontScale) {
            IconSelectionHighlight(target, reduceMotion)
        }
    } }
    }
    val hint = "图标与主题色同步；切换时可能返回桌面。通知和组件保持默认图标"
    BoxWithConstraints(Modifier.fillMaxWidth().layoutId(SectionRole.FOOTER)) {
        val footerHeight = with(density) {
            val width = (maxWidth - AbSpacing.xs * 2).roundToPx().coerceAtLeast(1)
            listOf(hint, "正在切换…", "桌面图标可能稍后刷新", "图标切换失败，请重试").maxOf {
                measurer.measure(it, style = AbType.caption1, constraints = Constraints(maxWidth = width)).size.height
            }.toDp()
        }
        GroupFooter(if (busy) "正在切换…" else feedback ?: hint, Modifier.heightIn(min = footerHeight))
    }
}

/** The overlay is non-interactive and is born at the measured target: no first-layout flight. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.IconSelectionHighlight(target: Rect, reduceMotion: Boolean) {
    val p = LocalAbPalette.current
    val position by animateIntOffsetAsState(
        IntOffset(target.left.roundToInt(), target.top.roundToInt()),
        animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = 1f, stiffness = 500f),
        label = "icon-selection-position")
    val size by animateIntSizeAsState(
        IntSize(target.width.roundToInt(), target.height.roundToInt()),
        animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = 1f, stiffness = 500f),
        label = "icon-selection-size")
    Box(Modifier.matchParentSize().drawBehind {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        if (size.width > stroke && size.height > stroke) drawRoundRect(
            color = p.accent,
            topLeft = Offset(position.x + inset, position.y + inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(18.dp.toPx() - inset), style = Stroke(stroke),
        )
    })
}
