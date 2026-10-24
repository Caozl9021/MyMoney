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
import androidx.compose.ui.text.style.TextAlign
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

/** 缺失或全空白都算没有：不把 `""` 当成一个可显示的值。 */
internal fun displayOrNull(value: String?): String? = value?.takeIf { it.isNotBlank() }

/**
 * 行标题：merchant → note → 「未命名账单」。
 *
 * 第三条**不能**回退到 `strategy`：那是识别管线的技术串（`wechat-minimal` 之类），
 * 露在账本里既看不懂又是内部实现细节。
 */
internal fun billRowTitle(merchant: String?, note: String?): String =
    displayOrNull(merchant) ?: displayOrNull(note) ?: "未命名账单"

/** 行副标题：分类（含二级）与时间，缺的分量整个省掉，不留孤立分隔符。 */
internal fun billRowSubtitle(category: String?, subCategory: String?, time: String?, refund: Boolean): String =
    listOfNotNull(
        if (refund) "退款" else null,
        when (val cat = displayOrNull(category)) {
            null -> "未分类"
            else -> displayOrNull(subCategory)?.let { "$cat · $it" } ?: cat
        },
        time,
    ).joinToString(" · ")

/**
 * 一行账单：左图标 / 中间商户 + 次级信息 / 右金额与账户。
 *
 * 首页和明细页共用唯一的形态，避免两处排版漂移。右侧不是固定 45% 的窄列：
 * 金额按内容取宽、余量让给标题，账户独立成第二行右对齐；账户缺失就整行不画，
 * 不从 method 之类的字段猜一个「微信零钱」出来。
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
    val time = if (showTime) timeOfDay(bill.timeMillis) else null
    val account = displayOrNull(bill.account)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .padding(horizontal = AbSpacing.l, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        IconBadge(icon = iconForCategory(bill.category), tint = accent, size = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = billRowTitle(bill.merchant, bill.note),
                style = AbType.body,
                color = p.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = billRowSubtitle(bill.category, bill.subCategory, time, bill.type == "REFUND"),
                style = AbType.caption1,
                color = p.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 右列按内容取宽：金额短时收窄把余量让给标题，长金额最多占 168dp（随字号放大），
        // 不做「无论金额多短都固定 45%」的分栏。
        Column(
            modifier = Modifier
                .widthIn(min = (76f * fontScale).dp, max = (168f * fontScale).dp)
                .onGloballyPositioned { onAmountBounds?.invoke(it.boundsInRoot()) },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AmountText(
                formatAmount(bill.priceCents, signed = true, type = bill.type),
                AbType.amountRow.copy(textAlign = TextAlign.End),
                if (hideAmount) Color.Transparent else amountTint(p, bill.type),
                minFontSize = 14f,
            )
            if (account != null) Text(
                text = account,
                style = AbType.caption2,
                color = p.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** 金额语义色：支出珊瑚、收入绿、退款青、转账中性；不做「只有颜色」的类型提示。 */
internal fun amountTint(p: AbPalette, type: String): Color = when (type) {
    "INCOME" -> p.income
    "REFUND" -> p.refund
    "TRANSFER" -> p.label
    else -> p.expense
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
fun AmountText(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minFontSize: Float = 10f,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val fitted = minOf(style.fontSize.value, maxWidth.value / (value.length.coerceAtLeast(1) * 0.66f * fontScale))
        // 不 fillMaxWidth：文本按自身宽度落位，右列的「靠右」交给外层 Column 的
        // horizontalAlignment 完成。否则金额会把右列撑到最大宽度，左侧副信息被压窄。
        Text(value,
            style = style.copy(fontSize = fitted.coerceAtLeast(minFontSize).sp), color = color, maxLines = 1)
    }
}
