package io.github.mymoney.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed, opaque feature colors: independent of wallpaper accent and list order. */
internal object SettingsIconColors {
    val orange = Color(0xFFE67E22)
    val blue = Color(0xFF3B82F6)
    val yellow = Color(0xFFD97706)
    val pink = Color(0xFFE04F5F)
    val violet = Color(0xFF8B5CF6)
    val teal = Color(0xFF0D9488)
    val green = Color(0xFF10B981)
    val indigo = Color(0xFF6366F1)
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
        "记录历史", "学习记忆", "外观", "分类预设" -> colors.violet
        "备份", "完整备份与恢复", "权限与后台运行", "规则文件" -> colors.teal
        "导出", "导入导出", "迁移与备份", "钱迹账单", "应用与通道", "识别统计" -> colors.green
        else -> colors.indigo
    }
    val glyph = when (title) {
        "导入导出", "迁移与备份" -> "swap_horiz"
        "钱迹账单" -> "receipt_long"
        "分类", "支出分类", "收入分类", "分类预设" -> "category"
        "资产" -> "account_balance_wallet"
        "备注", "备注模板", "备注建议" -> null
        "确认卡", "超级岛确认", "识别后弹出确认卡", "规则文件", "导入说明" -> "receipt_long"
        "自动记账", "启用自动记账" -> "bolt"
        "记录历史" -> "work_history"
        "备份", "完整备份与恢复", "权限与后台运行", "权限" -> "shield"
        "应用与通道", "识别通道", "应用范围" -> "smartphone"
        "记账周期" -> "calendar"
        "餐段设置" -> "restaurant"
        "学习记忆" -> "school"
        "通知模板" -> "payments"
        else -> null
    }
    val dark = p.cardBg.luminance() < 0.5f
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (dark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = glyph?.let(CategoryIcons::vectorFor) ?: fallback,
            contentDescription = null,
            modifier = Modifier.size(size * 0.52f),
            tint = color,
        )
    }
}
