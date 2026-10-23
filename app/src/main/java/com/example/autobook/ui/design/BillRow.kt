package com.example.autobook.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autobook.data.BillEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 金额文本：`¥1,234.56`。
 *
 * @param signed 是否带方向符号。支出 `-`、收入 `+`、转账不带符号。
 * @param type   `EXPENSE` / `INCOME` / `TRANSFER`
 */
fun formatAmount(cents: Long, signed: Boolean = false, type: String? = null): String {
    val body = "¥" + String.format(Locale.US, "%,.2f", abs(cents) / 100.0)
    if (!signed) return body
    return when (type) {
        "INCOME" -> "+$body"
        "TRANSFER" -> body
        "REFUND" -> "退款 $body"
        else -> "-$body"
    }
}

fun timeOfDay(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))

/** 分类 → 线性图标的稳定映射（按关键字命中，兜底用标签图标）。 */
fun iconForCategory(category: String?): ImageVector {
    val c = category.orEmpty()
    return when {
        listOf("餐饮", "零食", "咖啡", "外卖", "食").any { it in c } -> AbIcons.Store
        listOf("交通", "打车", "油", "停车", "地铁").any { it in c } -> AbIcons.Layers
        listOf("购物", "服饰", "数码", "日用").any { it in c } -> AbIcons.Tag
        listOf("工资", "收入", "奖金", "红包", "退款").any { it in c } -> AbIcons.ArrowDownLeft
        listOf("转账", "人情", "借").any { it in c } -> AbIcons.ArrowUpRight
        listOf("缴费", "水", "电", "话费", "房租", "物业").any { it in c } -> AbIcons.Note
        else -> AbIcons.Tag
    }
}

/**
 * 一行账单：左图标 / 中间商户 + 次级信息 / 右金额。
 *
 * 首页和明细页共用唯一的形态，避免两处排版漂移。
 */
@Composable
fun BillListRow(
    bill: BillEntity,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    onAmountBounds: ((Rect) -> Unit)? = null,
    hideAmount: Boolean = false,
) {
    val p = LocalAbPalette.current
    val accent = swatchFor(bill.category ?: "未分类")
    val isIncome = bill.type == "INCOME"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .padding(horizontal = AbSpacing.l, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        IconBadge(icon = iconForCategory(bill.category), tint = accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = bill.merchant ?: bill.note ?: bill.strategy.ifBlank { "未命名" },
                style = AbType.body,
                color = p.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    if (bill.type == "REFUND") "退款" else null,
                    bill.category?.let { cat ->
                        bill.subCategory?.let { "$cat · $it" } ?: cat
                    } ?: "未分类",
                    if (showTime) timeOfDay(bill.timeMillis) else null,
                ).joinToString("  ·  "),
                style = AbType.caption1,
                color = p.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth(0.45f).onGloballyPositioned { onAmountBounds?.invoke(it.boundsInRoot()) }) {
            AmountText(formatAmount(bill.priceCents, signed = true, type = bill.type), AbType.amountRow,
                if (hideAmount) Color.Transparent else if (isIncome) p.green else p.label)
        }
    }
}

/** 左滑露出的删除底衬（红底 + 白字）。 */
@Composable
fun SwipeDeleteBackground(modifier: Modifier = Modifier, progress: Float = 1f) {
    val p = LocalAbPalette.current
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(p.red.copy(alpha = progress.coerceIn(0f, 1f)))
            .padding(end = AbSpacing.xl),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("删除", style = AbType.body, color = Color.White.copy(alpha = progress.coerceIn(0f, 1f)))
    }
}

/** Only amounts fit their available width; ordinary content retains the user's full text scale. */
@Composable
fun AmountText(value: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val fitted = minOf(style.fontSize.value, maxWidth.value / (value.length.coerceAtLeast(1) * 0.66f * fontScale))
        Text(value, style = style.copy(fontSize = fitted.coerceAtLeast(10f).sp), color = color, maxLines = 1)
    }
}
