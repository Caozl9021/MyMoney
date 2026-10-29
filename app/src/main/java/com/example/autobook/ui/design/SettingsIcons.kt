package com.example.autobook.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed, opaque feature colors: independent of wallpaper accent and list order. */
internal object SettingsIconColors {
    val orange = Color(0xFFFF9C38)
    val blue = Color(0xFF4E9BFF)
    val yellow = Color(0xFFFFD24B)
    val pink = Color(0xFFFF6E98)
    val violet = Color(0xFFAC80F5)
    val teal = Color(0xFF30BFC7)
    val green = Color(0xFF39B97E)
    val indigo = Color(0xFF7B8DF1)
}

/** White symbols on strong colors; yellow uses dark ink for a clear silhouette. */
internal fun badgeForeground(background: Color): Color =
    if (1.05f / (background.luminance() + 0.05f) >= 3f) Color.White else Color(0xFF202024)

@Composable
internal fun SettingsIconBadge(title: String, fallback: ImageVector, size: Dp = AbCardDensity.settingsIcon) {
    val colors = SettingsIconColors
    val p = LocalAbPalette.current
    val color = when (title) {
        "支出分类" -> p.expenseIcon
        "收入分类" -> p.incomeIcon
        "分类", "餐段设置", "记账周期" -> colors.orange
        "资产", "识别通道", "导出到 iCost", "页面诊断" -> colors.blue
        "备注", "备注模板", "确认卡" -> colors.yellow
        "自动记账", "通知模板" -> colors.pink
        "识别历史", "学习记忆", "外观", "分类预设" -> colors.violet
        "备份", "完整备份与恢复", "权限与后台运行", "规则文件" -> colors.teal
        "导出", "导入导出", "钱迹账单", "应用与通道", "识别统计" -> colors.green
        else -> colors.indigo
    }
    val glyph = when (title) {
        "导入导出" -> "swap_horiz"
        "钱迹账单" -> "receipt_long"
        "分类", "支出分类", "收入分类", "分类预设" -> "category"
        "资产" -> "account_balance_wallet"
        "备注", "备注模板", "备注建议", "确认卡", "规则文件", "导入说明" -> "receipt_long"
        "自动记账" -> "bolt"
        "识别历史" -> "work_history"
        "备份", "完整备份与恢复", "权限与后台运行" -> "shield"
        "应用与通道", "识别通道", "页面诊断" -> "smartphone"
        "记账周期" -> "work_history"
        "餐段设置" -> "restaurant"
        "学习记忆" -> "school"
        "通知模板" -> "payments"
        else -> null
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
        // Decorative glyphs are paired with a readable row title; keep the bright tiles light.
        val foreground = if (color == colors.yellow) Color(0xFF5C4300) else Color.White
        if (glyph != null) Icon(painterResource(CategoryIcons.resourceFor(glyph)), null,
            Modifier.size(size * 0.58f), tint = foreground)
        else Icon(fallback, null, Modifier.size(size * 0.56f), tint = foreground)
    }
}
