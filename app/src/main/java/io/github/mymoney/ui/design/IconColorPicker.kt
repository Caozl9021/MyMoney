package io.github.mymoney.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 16 色圆形色板选择器：用于分类与资产账户的图标底色自选。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorSwatchStrip(
    selectedColorHex: String?,
    fallbackColor: Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAbPalette.current
    val haptic = rememberAbHaptic()
    val effectiveHex = selectedColorHex ?: fallbackColor.toHexRgb()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AbAccentSwatches.forEachIndexed { index, color ->
            val hex = color.toHexRgb()
            val isSelected = effectiveHex.equals(hex, ignoreCase = true)
            val interaction = remember(hex) { MutableInteractionSource() }
            Box(
                Modifier
                    .size(36.dp)
                    .abPressFeedback(interaction, pressedScale = 0.90f)
                    .clip(CircleShape)
                    .background(solidBadgeBackground(color))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, p.label, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(interactionSource = interaction, indication = null) {
                        haptic.tick()
                        onSelect(hex)
                    }
                    .semantics {
                        role = Role.RadioButton
                        selected = isSelected
                        contentDescription = "配色 ${index + 1}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = AbIcons.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 分组线性图标网格（Lucide Icons）：用于分类与资产账户图标自选。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupedIconGrid(
    selectedKey: String,
    accentColor: Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    iconGroups: List<CategoryIcons.CategoryIconGroup> = CategoryIcons.groups,
) {
    val p = LocalAbPalette.current
    val isDark = p.pageBg.luminance() < 0.1f
    val haptic = rememberAbHaptic()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        iconGroups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    group.title,
                    style = AbType.caption1,
                    color = p.secondaryLabel,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.keys.forEach { key ->
                        val active = key == selectedKey
                        val interaction = remember(key) { MutableInteractionSource() }
                        val bg = if (active) {
                            accentColor.copy(alpha = if (isDark) 0.34f else 0.18f)
                        } else {
                            p.fill
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .abPressFeedback(interaction, pressedScale = 0.90f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg)
                                .then(
                                    if (active) {
                                        Modifier.border(1.25.dp, accentColor, RoundedCornerShape(12.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(interactionSource = interaction, indication = null) {
                                    haptic.tick()
                                    onSelect(key)
                                }
                                .semantics {
                                    role = Role.RadioButton
                                    selected = active
                                    contentDescription = "图标 $key"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = CategoryIcons.vectorFor(key),
                                contentDescription = null,
                                tint = if (active) accentColor else p.label,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分类 / 账户的「图标与配色」自选弹窗。
 */
@Composable
internal fun IconColorStyleDialog(
    title: String,
    previewName: String,
    initialIconKey: String?,
    initialColorHex: String?,
    defaultIconKey: String,
    defaultColor: Color,
    iconGroups: List<CategoryIcons.CategoryIconGroup> = CategoryIcons.groups,
    allowResetToDefault: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (iconKey: String?, colorHex: String?) -> Unit,
) {
    val p = LocalAbPalette.current
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    var pickedIcon by remember(initialIconKey) { mutableStateOf(initialIconKey) }
    var pickedColor by remember(initialColorHex) { mutableStateOf(initialColorHex) }
    val previewColor = parseColorHex(pickedColor) ?: defaultColor
    val previewIcon = pickedIcon ?: defaultIconKey

    AppDialog(title = title, onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(solidBadgeBackground(previewColor))
                    .badgeLiquidGlaze(effectiveGlass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CategoryIcons.vectorFor(previewIcon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(previewName.ifBlank { "未命名" }, style = AbType.headline, color = p.label)
                Text(
                    if (pickedIcon == null && pickedColor == null) "当前使用自动匹配样式" else "已自定义图标与配色",
                    style = AbType.caption1,
                    color = p.secondaryLabel,
                )
            }
            if (allowResetToDefault && (pickedIcon != null || pickedColor != null)) {
                AbTextButton("恢复默认", onClick = {
                    pickedIcon = null
                    pickedColor = null
                })
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Text("配色", style = AbType.footnote, color = p.secondaryLabel)
                ColorSwatchStrip(
                    selectedColorHex = pickedColor,
                    fallbackColor = defaultColor,
                    onSelect = { hex -> pickedColor = hex },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Text("图标", style = AbType.footnote, color = p.secondaryLabel)
                GroupedIconGrid(
                    selectedKey = previewIcon,
                    accentColor = previewColor,
                    onSelect = { key -> pickedIcon = key },
                    iconGroups = iconGroups,
                )
            }
        }

        AppDialogActions(
            confirmLabel = "确定",
            onConfirm = { onConfirm(pickedIcon, pickedColor) },
            onDismiss = onDismiss,
        )
    }
}
