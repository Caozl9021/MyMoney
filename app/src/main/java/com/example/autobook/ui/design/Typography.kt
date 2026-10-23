package com.example.autobook.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * iOS 的字号阶梯。系统字体在 Android 上就是 Roboto/思源黑体，
 * 只对齐「字号 + 字重 + 行高」，不强行塞 Inter/SF。
 *
 * [tnum] 打开等宽数字：金额滚动/变化时不抖动，这是 iOS 记账类 App 的硬要求。
 */
private const val TNUM = "tnum"

private fun TextStyle.tabular() = copy(
    fontFeatureSettings = TNUM,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

object AbType {

    /** 大标题 34 Bold */
    val largeTitle = TextStyle(
        fontSize = 34.sp,
        lineHeight = 41.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.37.sp,
    )

    /** 导航栏收缩后的标题 17 Semibold */
    val navTitle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 卡片大标题 22 Bold */
    val title2 = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.26).sp,
    )

    /** 组内主行 / 按钮 17 Semibold */
    val headline = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.41).sp,
    )

    /** 正文 17 Regular */
    val body = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.41).sp,
    )

    /** 次要正文 15 */
    val subhead = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.24).sp,
    )

    /** 分组标题 / 辅助 13 */
    val footnote = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.08).sp,
    )

    /** 时间戳 12 */
    val caption1 = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 脚注 11 */
    val caption2 = TextStyle(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.07.sp,
    )

    // ---- 等宽数字（金额专用） ----

    /** 首页月度支出主数字 34 Bold */
    val amountHero = largeTitle.tabular()

    /** 统计卡里的中等金额 22 Bold */
    val amountTitle = title2.tabular()

    /** 列表行金额 17 Semibold */
    val amountRow = headline.tabular()

    /** 确认页可编辑金额 44 Bold */
    val amountEntry = TextStyle(
        fontSize = 44.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.0).sp,
    ).tabular()

    /** 等宽诊断文本 */
    val mono = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** 分组标题用的小号全大写风格标题 */
val AbType.sectionHeader: TextStyle
    get() = footnote.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
