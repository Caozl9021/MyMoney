package io.github.mymoney.ui.design

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mymoney.R
import io.github.mymoney.data.AssetAccount

/**
 * 账户 logo：品牌标识放在 `res/drawable-nodpi/`，原图透明底 PNG，不重绘、不改色。
 *
 * 为什么要按**名字**匹配而不是给每张账户存一个图标字段：账户名本来就是唯一来源
 * （识别匹配、iCost 导出都读它），从名字推导 logo 不需要动 Room 表结构，也不用为
 * 老数据补迁移；认不出来的（用户自己起的「日常花销」）退回首字圆形头像，
 * 和新加账户时的表现一致。
 *
 * 匹配用「包含」而不是相等：用户会写「微信钱包」「微信零钱通」「支付宝余额」。
 * 「钱包」兜底账户故意不配品牌图，走首字头像。
 */
object AccountLogos {

    // Cache normalized local resources; retain original PNGs byte-for-byte.
    private val normalized = java.util.concurrent.ConcurrentHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>()

    fun cachedBitmap(res: Int): androidx.compose.ui.graphics.ImageBitmap? = normalized[res]

    fun normalizedBitmap(resources: Resources, res: Int) = normalized.getOrPut(res) {
        val original = BitmapFactory.decodeResource(resources, res)
        val pixels = IntArray(original.width * original.height)
        original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
        var left = original.width
        var top = original.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, pixel ->
            if ((pixel ushr 24) != 0) {
                val x = index % original.width
                val y = index / original.width
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (right < left) return@getOrPut original.asImageBitmap()
        val width = right - left + 1
        val height = bottom - top + 1
        val side = maxOf(width, height)
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(square).drawBitmap(
            original,
            android.graphics.Rect(left, top, right + 1, bottom + 1),
            android.graphics.RectF((side - width) / 2f, (side - height) / 2f,
                (side + width) / 2f, (side + height) / 2f),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        square.asImageBitmap()
    }

    private val rules: List<Pair<String, List<String>>> = listOf(
        "wallet_huabei" to listOf("花呗"),
        "wallet_jd" to listOf("白条", "京东"),
        "wallet_ecny" to listOf("数字人民币", "数币"),
        "wallet_alipay" to listOf("支付宝"),
        "wallet_wechat" to listOf("微信"),
        "bank_cmb" to listOf("招商", "招行"),
        "bank_icbc" to listOf("工商", "工行", "牡丹"),
        "bank_abc" to listOf("农业", "农行"),
        "bank_boc" to listOf("中国银行", "中行"),
        "bank_ccb" to listOf("建设", "建行", "龙卡"),
    )

    fun keyFor(name: String?): String? {
        val n = name.orEmpty().trim()
        if (n.isBlank()) return null
        if (n == "钱包") return "wallet_generic"
        // 余额宝、零钱通走独立的矢量徽章与语义色，不和主钱包图标混用
        if ("余额宝" in n || "零钱通" in n) return null
        return rules.firstOrNull { (_, words) -> words.any { it in n } }?.first
    }

    val iconGroups: List<CategoryIcons.CategoryIconGroup> = listOf(
        CategoryIcons.CategoryIconGroup("支付与钱包", listOf("account_balance_wallet", "qr_code", "nfc", "smartphone", "payments", "coins", "swap_horiz", "globe")),
        CategoryIcons.CategoryIconGroup("银行卡与信用", listOf("landmark", "credit_card", "vault", "lock", "badge_percent", "receipt_long", "shield", "scale")),
        CategoryIcons.CategoryIconGroup("理财、储蓄与工作", listOf("piggy_bank", "savings", "trending_up", "bar_chart", "candlestick", "circle_dollar", "hand_coins", "emoji_events", "briefcase", "work")),
        CategoryIcons.CategoryIconGroup("房产、车辆与专项", listOf("home", "apartment", "key_round", "local_taxi", "directions_bus", "parking", "flight", "card_giftcard", "heart", "more_horiz", CategoryIcons.FALLBACK)),
    )

    fun defaultIconKey(kind: String, name: String): String {
        val preset = AccountPresets.all.firstOrNull { it.name == name }
        if (preset?.iconKey != null) return preset.iconKey
        val n = name.trim()
        return when {
            "余额宝" in n || "零钱通" in n || "定期" in n || "理财" in n -> "savings"
            "基金" in n || "股票" in n || "证券" in n -> "trending_up"
            "公积金" in n -> "apartment"
            "医保" in n || "保险" in n -> "shield"
            "房贷" in n -> "home"
            "车贷" in n -> "local_taxi"
            "公交" in n || "校园" in n || "交通" in n -> "directions_bus"
            "现金" in n || kind == "CASH" -> "payments"
            "信用" in n || "月付" in n || kind == "CREDIT" -> "credit_card"
            kind == "SAVINGS" || "银行" in n -> "landmark"
            kind == "OTHER_ASSET" -> "trending_up"
            kind == "OTHER_DEBT" -> "receipt_long"
            else -> "account_balance_wallet"
        }
    }

    fun defaultColor(kind: String, name: String): Color {
        val presetColor = AccountPresets.all.firstOrNull { it.name == name }?.colorHex?.let(::parseColorHex)
        if (presetColor != null) return presetColor
        val n = name.trim()
        return when {
            "交通" in n || "交行" in n -> Color(0xFF1D4ED8)
            "邮储" in n || "邮政" in n -> Color(0xFF047857)
            "浦发" in n -> Color(0xFF1E40AF)
            "中信" in n -> Color(0xFFDC2626)
            "兴业" in n || "上海银行" in n -> Color(0xFF2563EB)
            "光大" in n -> Color(0xFF7C3AED)
            "民生" in n -> Color(0xFF0D9488)
            "平安" in n -> Color(0xFFEA580C)
            "广发" in n || "北京银行" in n -> Color(0xFFE11D48)
            "华夏" in n || "云闪付" in n -> Color(0xFFDC2626)
            "余额宝" in n || "美团" in n -> Color(0xFFD97706)
            "零钱通" in n || "现金" in n -> Color(0xFF10B981)
            "公积金" in n || "江苏银行" in n -> Color(0xFF0EA5E9)
            "医保" in n -> Color(0xFF10B981)
            kind == "CREDIT" -> Color(0xFF3B82F6)
            kind == "OTHER_DEBT" -> Color(0xFFE04F5F)
            kind == "OTHER_ASSET" -> Color(0xFF0D9488)
            else -> swatchFor(n)
        }
    }

    fun resourceFor(key: String): Int? = when (key) {
        "wallet_generic" -> R.drawable.wallet_generic
        "wallet_alipay" -> R.drawable.wallet_alipay
        "wallet_wechat" -> R.drawable.wallet_wechat
        "wallet_huabei" -> R.drawable.wallet_huabei
        "wallet_jd" -> R.drawable.wallet_jd
        "wallet_ecny" -> R.drawable.wallet_ecny
        "bank_cmb" -> R.drawable.bank_cmb
        "bank_icbc" -> R.drawable.bank_icbc
        "bank_abc" -> R.drawable.bank_abc
        "bank_boc" -> R.drawable.bank_boc
        "bank_ccb" -> R.drawable.bank_ccb
        else -> null
    }
}

/**
 * 账户头像：优先显示用户指定的图标与配色；其次命中品牌 PNG logo；
 * 其余账户按类型与机构语义显示矢量图标 + 专属品牌色。
 */
@Composable
fun AccountAvatar(account: AssetAccount, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    AccountAvatar(
        name = account.name,
        modifier = modifier,
        size = size,
        kind = account.kind,
        iconKey = account.iconKey,
        colorHex = account.colorHex,
    )
}

@Composable
fun AccountAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    kind: String = "PAYMENT",
    iconKey: String? = null,
    colorHex: String? = null,
) {
    val res = if (iconKey == null && colorHex == null) {
        AccountLogos.keyFor(name)?.let(AccountLogos::resourceFor)
    } else null
    if (res != null) {
        val resources = LocalContext.current.resources
        val bitmap by androidx.compose.runtime.produceState(
            initialValue = AccountLogos.cachedBitmap(res),
            key1 = res,
            key2 = resources,
        ) {
            if (value == null) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    AccountLogos.normalizedBitmap(resources, res)
                }
            }
        }
        val ready = bitmap
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = null,
                modifier = modifier.size(size).clip(CircleShape),
            )
            return
        }
    }
    val resolvedKey = iconKey ?: AccountLogos.defaultIconKey(kind, name)
    val tint = parseColorHex(colorHex) ?: AccountLogos.defaultColor(kind, name)
    val effectiveGlass = LocalLiquidGlassEnabled.current && !LocalReduceTransparency.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(solidBadgeBackground(tint))
            .badgeLiquidGlaze(effectiveGlass),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.vectorFor(resolvedKey),
            contentDescription = null,
            modifier = Modifier.size(size * 0.54f),
            tint = Color.White,
        )
    }
}
