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
import androidx.compose.ui.graphics.lerp
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

/** 纯色圆形徽章底色：保持不透明饱和色，过亮的浅色（如深色模式文字色）适当压深以确保白色图标清晰。 */
internal fun solidBadgeBackground(tint: Color): Color {
    val opaque = tint.copy(alpha = 1f)
    return if (opaque.luminance() > 0.38f) lerp(opaque, Color.Black, 0.28f) else opaque
}

@Composable
internal fun SettingsIconBadge(title: String, fallback: ImageVector, size: Dp = AbCardDensity.settingsIcon) {
    val colors = SettingsIconColors
    val color = when (title) {
        "支出分类" -> colors.pink
        "收入分类" -> colors.green
        "分类", "餐段设置", "记账周期" -> colors.orange
        "资产", "导出到 iCost", "页面诊断", "无障碍服务" -> colors.blue
        "备注", "备注模板", "商品说明优先", "商家备注规则", "弹出确认卡", "超级岛提醒", "确认卡" -> colors.yellow
        "自动记账", "启用自动记账" -> colors.pink
        "记录历史", "学习记忆", "外观", "分类预设", "悬浮窗权限" -> colors.violet
        "后台保活", "云备份", "本地备份文件", "备份" -> colors.teal
        "迁移与备份", "钱迹账单", "监控应用" -> colors.green
        "页面 OCR 补全", "关于" -> colors.indigo
        else -> colors.indigo
    }
    val glyph = when (title) {
        "迁移与备份", "导出到 iCost" -> "swap_horiz"
        "钱迹账单", "弹出确认卡", "超级岛提醒", "确认卡", "导入说明" -> "receipt_long"
        "分类", "支出分类", "收入分类", "分类预设" -> "category"
        "资产" -> "account_balance_wallet"
        "备注", "备注模板", "商品说明优先", "商家备注规则" -> null
        "自动记账", "启用自动记账" -> "bolt"
        "记录历史" -> "work_history"
        "后台保活", "本地备份文件", "备份" -> "shield"
        "监控应用" -> "smartphone"
        "记账周期" -> "calendar"
        "餐段设置" -> "restaurant"
        "学习记忆" -> "school"
        else -> null
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(solidBadgeBackground(color)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = glyph?.let(CategoryIcons::vectorFor) ?: fallback,
            contentDescription = null,
            modifier = Modifier.size(size * 0.54f),
            tint = Color.White,
        )
    }
}
