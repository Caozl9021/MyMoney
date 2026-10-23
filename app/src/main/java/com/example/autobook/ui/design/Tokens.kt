package com.example.autobook.ui.design

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计 Token。取值参照 iOS 26/27 的语义色与 8pt 网格。
 *
 * 约定：
 * - 颜色只从这里取，页面里不允许再出现硬编码 `Color(0xFF...)`。
 * - 圆角用 [AbRadius]，间距用 [AbSpacing]，不要写魔法数字。
 */
@Immutable
data class AbPalette(
    // 文字
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    // 背景
    val pageBg: Color,
    val cardBg: Color,
    val elevatedBg: Color,
    val fill: Color,
    val separator: Color,
    // 玻璃（拟态）：底色 + 上亮下暗的描边
    val glassTint: Color,
    val glassHighlight: Color,
    val glassStrokeTop: Color,
    val glassStrokeBottom: Color,
    val glassShadow: Color,
    /** Tab 选中块的灰色（比 fill 深一档，压在半透明玻璃上还能看见） */
    val tabIndicator: Color,
    // 品牌 & 语义
    val accent: Color,
    val onAccent: Color,
    val red: Color,
    val green: Color,
    val barTrack: Color,
)

/** 浅色：纯白页面 + #F2F2F7 卡片以外的浅灰分组底（iOS systemGroupedBackground 反过来用）。 */
val LightPalette = AbPalette(
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    quaternaryLabel = Color(0x2E3C3C43),
    pageBg = Color(0xFFF7F7F7),
    cardBg = Color(0xFFFFFFFF),
    elevatedBg = Color(0xFFFFFFFF),
    fill = Color(0x0F767680),
    separator = Color(0x2E3C3C43),
    glassTint = Color(0xB8FFFFFF),
    glassHighlight = Color(0xCCFFFFFF),
    glassStrokeTop = Color(0xE6FFFFFF),
    glassStrokeBottom = Color(0x33FFFFFF),
    glassShadow = Color(0x14000000),
    tabIndicator = Color(0x1F767680),
    // 品牌薄荷绿压暗到能过白底对比度
    accent = Color(0xFF0F9E85),
    onAccent = Color(0xFFFFFFFF),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    barTrack = Color(0x1F767680),
)

/** 深色：真黑页面（iOS 深色是 #000，不是 #121212）。 */
val DarkPalette = AbPalette(
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    quaternaryLabel = Color(0x2EEBEBF5),
    pageBg = Color(0xFF0B0B0C),
    cardBg = Color(0xFF1C1C1E),
    elevatedBg = Color(0xFF2C2C2E),
    fill = Color(0x1F767680),
    separator = Color(0x4D545458),
    glassTint = Color(0xCC1C1C1E),
    glassHighlight = Color(0x26FFFFFF),
    glassStrokeTop = Color(0x3DFFFFFF),
    glassStrokeBottom = Color(0x0DFFFFFF),
    glassShadow = Color(0x66000000),
    tabIndicator = Color(0x2E767680),
    accent = Color(0xFF3FD0B4),
    onAccent = Color(0xFF00382C),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    barTrack = Color(0x2E767680),
)

object AbSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    /** 页面左右留白（inset grouped 的外边距） */
    val page = 16.dp
}

object AbRadius {
    val chip = 10.dp
    val row = 12.dp
    val group = 20.dp
    val card = 22.dp
    val sheet = 28.dp
    val capsule = 100.dp
}

object AbSize {
    /** iOS 最小触控 44pt；Android 上 48dp 更稳妥 */
    val minTouch = 48.dp
    val rowHeight = 56.dp
    val tabBarHeight = 60.dp
    val tabBarSideMargin = 36.dp
    val navBarHeight = 52.dp
    /** 列表分隔线左侧缩进（对齐文字，不切图标） */
    val separatorInset = 16.dp
    val iconSm = 18.dp
    val iconMd = 22.dp
    val iconLg = 28.dp
}

object AbMotion {
    const val fast = 120
    const val normal = 220
    const val slow = 320
}

/** 分类色板（iOS system colors），分类图标/统计条用。 */
val AbAccentSwatches = listOf(
    Color(0xFFFF9500), // orange
    Color(0xFF007AFF), // blue
    Color(0xFFAF52DE), // purple
    Color(0xFF34C759), // green
    Color(0xFFFF2D55), // pink
    Color(0xFF30B0C7), // teal
    Color(0xFF5856D6), // indigo
    Color(0xFFFFCC00), // yellow
    Color(0xFFA2845E), // brown
    Color(0xFF8E8E93), // gray
)

/** 按名字稳定取色：同一个分类每次颜色一致，不会因为顺序变化而跳。 */
fun swatchFor(name: String, fallbackIndex: Int = 0): Color {
    if (name.isEmpty()) return AbAccentSwatches[fallbackIndex % AbAccentSwatches.size]
    var h = 0
    for (c in name) h = h * 31 + c.code
    return AbAccentSwatches[((h % AbAccentSwatches.size) + AbAccentSwatches.size) % AbAccentSwatches.size]
}

val LocalAbPalette = compositionLocalOf { LightPalette }

val LocalReduceTransparency = compositionLocalOf { false }

/** Preserve the normal dock size while making room for enlarged labels. */
fun dockHeightForFontScale(fontScale: Float) =
    AbSize.tabBarHeight + ((fontScale - 1.3f).coerceAtLeast(0f) * 28f).dp

@androidx.compose.runtime.Composable
fun dockHeight() = dockHeightForFontScale(androidx.compose.ui.platform.LocalDensity.current.fontScale)
