package io.github.mymoney.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.mymoney.ui.design.AbEasing
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbRadius
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.Appearance
import io.github.mymoney.ui.design.BrandColors
import io.github.mymoney.ui.design.BrandMotion
import io.github.mymoney.ui.design.BrandTheme
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.LocalLiquidGlassEnabled
import io.github.mymoney.ui.design.LocalReduceMotion
import io.github.mymoney.ui.design.LocalReduceTransparency
import io.github.mymoney.ui.design.abPressFeedback
import io.github.mymoney.ui.design.appearanceRevision
import io.github.mymoney.ui.design.liquidSpecularRimBrush
import io.github.mymoney.ui.design.rememberAbHaptic
import kotlinx.coroutines.launch

@Composable
internal fun ThemeColorPicker() {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val revision = appearanceRevision()
    val prefs = remember(context, revision) { Appearance.prefs(context) }
    val selected = remember(prefs, revision) { Appearance.readBrandTheme(prefs) }
    val reduceMotion = LocalReduceMotion.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    val haptic = rememberAbHaptic()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textConstraints = with(density) { Constraints(maxWidth = 64.dp.roundToPx()) }
    val titleHeight = with(density) {
        BrandTheme.entries.maxOf {
            measurer.measure(it.title, style = AbType.caption1, constraints = textConstraints).size.height
        }.toDp()
    }

    GroupHeader("主题色")
    InsetGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = AbSpacing.s, vertical = AbSpacing.m),
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            BrandTheme.entries.forEach { theme ->
                val active = theme == selected
                val roles = BrandColors.roles(theme, isDark)
                val ringAlpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = if (reduceMotion) tween(100, easing = AbEasing.EaseOut)
                    else tween(BrandMotion.duration(false), easing = AbEasing.EaseOut),
                    label = "theme-ring-${theme.id}",
                )
                val swatchInteraction = remember(theme) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .abPressFeedback(swatchInteraction, pressedScale = 0.94f, pressedAlpha = 0.85f)
                        .clip(RoundedCornerShape(AbRadius.row))
                        .selectable(
                            selected = active,
                            interactionSource = swatchInteraction,
                            indication = null,
                            role = Role.RadioButton,
                        ) {
                            if (theme == selected) return@selectable
                            haptic.select()
                            Appearance.setBrandTheme(context, theme)
                        }
                        .semantics { stateDescription = if (active) "已选择" else "未选择" }
                        .padding(vertical = AbSpacing.s, horizontal = AbSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (ringAlpha > 0.01f) {
                            val ringScale = if (reduceMotion) 1f else (0.90f + 0.10f * ringAlpha)
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .graphicsLayer { scaleX = ringScale; scaleY = ringScale }
                                    .border(2.dp, roles.primary.copy(alpha = ringAlpha), CircleShape),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(roles.primary)
                                .then(
                                    if (effectiveGlass) Modifier.border(0.85.dp, liquidSpecularRimBrush(isDark, 0.7f), CircleShape)
                                    else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = active,
                                enter = if (reduceMotion) androidx.compose.animation.fadeIn(tween(110, easing = AbEasing.EaseOut))
                                else androidx.compose.animation.scaleIn(tween(150, easing = AbEasing.EaseOut), initialScale = 0.85f) +
                                    androidx.compose.animation.fadeIn(tween(120, easing = AbEasing.EaseOut)),
                                exit = if (reduceMotion) androidx.compose.animation.fadeOut(tween(90, easing = AbEasing.EaseOut))
                                else androidx.compose.animation.scaleOut(tween(100, easing = AbEasing.EaseOut), targetScale = 0.85f) +
                                    androidx.compose.animation.fadeOut(tween(90, easing = AbEasing.EaseOut)),
                            ) {
                                Icon(
                                    imageVector = AbIcons.Check,
                                    contentDescription = null,
                                    tint = roles.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.height(titleHeight), contentAlignment = Alignment.Center) {
                        Text(
                            text = theme.title,
                            style = AbType.caption1,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) p.label else p.secondaryLabel,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
