package com.example.mymoney.ui.design

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
    /**
     * 支出金额的饱和暖红，略偏橙。只用于金额与图表，不替换 [red]：
     * 删除、错误、失败提示仍走 [red]，两者语义不同，不能合并。
     */
    val expense: Color,
    /** 收入金额（比 [green] 压暗一档，浅色小字号也过 4.5:1）。 */
    val income: Color,
    /** 退款金额与退款图表，独立于动态 accent，避免随壁纸变色。 */
    val refund: Color,
    /** 转账使用中性灰，避免与正文黑白或收支混淆。 */
    val transfer: Color,
    /** 账单类型图标的明亮实底，与文字同色相。 */
    val expenseIcon: Color,
    val incomeIcon: Color,
    /** 首页本月支出主卡：上浅下深的两档珊瑚底，避免整块强色。 */
    val heroSurfaceTop: Color,
    val heroSurfaceBottom: Color,
    /** 主卡上的主数字：比正文更深一档的同色相墨色，不是纯黑。 */
    val heroAmount: Color,
    /** 主卡上的次级文字（收入/结余标签、今日辅助）。 */
    val heroSecondaryLabel: Color,
    /** 主卡描边：极淡的同色相，给浅底一点边缘。 */
    val heroStroke: Color,
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
    // 更偏红的暖红色，金额在浅色页面上保持可读。
    expense = Color(0xFFD93632),
    // 清亮的正绿色相，文字保持可读；图标另用更浅亮的实底。
    income = Color(0xFF078642),
    // 青蓝：白底 5.36:1，与收入和支出都能区分，且不随系统强调色漂移。
    refund = Color(0xFF0E7490),
    transfer = Color(0xFF62676D),
    expenseIcon = Color(0xFFFA5B50),
    incomeIcon = Color(0xFF39B97E),
    heroSurfaceTop = Color(0xFFFFF3EC),
    heroSurfaceBottom = Color(0xFFFFE7E9),
    heroAmount = Color(0xFF5C2A2E),
    heroSecondaryLabel = Color(0xFF7A3B40),
    heroStroke = Color(0x1FC63A47),
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
    // 深色同步使用偏红暖红与柔和绿。
    expense = Color(0xFFFF7166),
    income = Color(0xFF6AE1A9),
    refund = Color(0xFF4FC3E8),
    transfer = Color(0xFFA7ABB2),
    expenseIcon = Color(0xFFFF7166),
    incomeIcon = Color(0xFF6AE1A9),
    heroSurfaceTop = Color(0xFF2B161A),
    heroSurfaceBottom = Color(0xFF241014),
    heroAmount = Color(0xFFFFD9D6),
    heroSecondaryLabel = Color(0xFFE8B9B6),
    heroStroke = Color(0x33FF8A8F),
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

/** Shared compact density; heights remain content driven and touch targets remain 48dp. */
object AbCardDensity {
    val content = 12.dp
    val gap = 10.dp
    val rowVertical = 8.dp
    val billRowMin = 64.dp
    val icon = 34.dp
    val settingsIcon = 36.dp
    val leadingInset = AbSpacing.l + settingsIcon + AbSpacing.m
    val headerVertical = 8.dp
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
    /** 按压与选中反馈保持短促；布局展开更从容。减少动效时调用方使用0ms。 */
    const val press = 120
    const val selection = 150
    const val detail = 200
    const val pressedScale = 0.98f
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

/** Semantic vertical rhythm. Container owns outer gaps; rows own their inner padding. */
object AbLayout {
    val pageTop = 12.dp
    val pageBottom = 16.dp
    val cardGap = AbCardDensity.gap
    val groupGap = 16.dp
    val headingGap = 8.dp
    val fieldGap = 8.dp
    val captionGap = 4.dp
    val actionGap = 16.dp
}
