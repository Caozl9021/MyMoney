package io.github.mymoney.ui.design

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mymoney.R
import io.github.mymoney.ui.design.ColorContrast.contrastingForeground

/**
 * 账户 logo：用户发来的六个真实标识（支付宝 / 微信 / 工行 / 农行 / 中行 / 建行），
 * 资源在 `res/drawable-nodpi/`，原图透明底 PNG，不重绘、不改色。
 *
 * 为什么要按**名字**匹配而不是给每张账户存一个图标字段：账户名本来就是唯一来源
 * （识别匹配、iCost 导出都读它），从名字推导 logo 不需要动 Room 表结构，也不用为
 * 老数据补迁移；认不出来的（用户自己起的「招商储蓄卡」）退回首字圆形头像，
 * 和新加账户时的表现一致。
 *
 * 匹配用「包含」而不是相等：用户会写「微信钱包」「微信零钱通」「支付宝余额」。
 */
object AccountLogos {

    // Cache six normalized local resources; retain original PNGs byte-for-byte.
    private val normalized = mutableMapOf<Int, androidx.compose.ui.graphics.ImageBitmap>()

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
        "wallet_alipay" to listOf("支付宝", "余额宝"),
        "wallet_wechat" to listOf("微信", "零钱"),
        "bank_icbc" to listOf("工商", "工行", "牡丹"),
        "bank_abc" to listOf("农业", "农行"),
        "bank_boc" to listOf("中国银行", "中行"),
        "bank_ccb" to listOf("建设", "建行", "龙卡"),
    )

    fun keyFor(name: String?): String? {
        val n = name.orEmpty()
        if (n.isBlank()) return null
        return rules.firstOrNull { (_, words) -> words.any { it in n } }?.first
    }

    fun resourceFor(key: String): Int? = when (key) {
        "wallet_alipay" -> R.drawable.wallet_alipay
        "wallet_wechat" -> R.drawable.wallet_wechat
        "bank_icbc" -> R.drawable.bank_icbc
        "bank_abc" -> R.drawable.bank_abc
        "bank_boc" -> R.drawable.bank_boc
        "bank_ccb" -> R.drawable.bank_ccb
        else -> null
    }
}

/**
 * 账户头像：有品牌 logo 就贴 logo（去透明留白后等比居中于 [size] 的圆），否则用名字首字
 * + 名字哈希出的底色圆形。两种形态尺寸一致，列表里不会跳。
 */
@Composable
fun AccountAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val key = AccountLogos.keyFor(name)
    val res = key?.let(AccountLogos::resourceFor)
    if (res != null) {
        val resources = LocalContext.current.resources
        val bitmap = remember(res, resources) { AccountLogos.normalizedBitmap(resources, res) }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape),
        )
        return
    }
    val swatch = swatchFor(name)
    val ink = Color(contrastingForeground(swatch.toArgb()))
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(swatch),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).ifBlank { "账" }, style = AbType.headline, color = ink, maxLines = 1)
    }
}

/**
 * 添加账户时的预设：先给一份「市面上大多数人都有的账户」，名字、类型、logo 一次填好。
 *
 * 用户原来要手打「招商储蓄卡」这种名字，还得自己判断该选哪个类型；冷门的仍然可以
 * 自定义填写（表单下面保留了手输入口）。
 */
data class AccountPreset(
    val name: String,
    val kind: String,
    val aliases: String = "",
)

object AccountPresets {

    val all: List<AccountPreset> = listOf(
        AccountPreset("支付宝", "PAYMENT", "支付宝余额"),
        AccountPreset("微信钱包", "PAYMENT", "微信,零钱"),
        AccountPreset("工商银行", "SAVINGS", "工行"),
        AccountPreset("农业银行", "SAVINGS", "农行"),
        AccountPreset("中国银行", "SAVINGS", "中行"),
        AccountPreset("建设银行", "SAVINGS", "建行"),
        AccountPreset("招商银行", "SAVINGS", "招行"),
        AccountPreset("信用卡", "CREDIT", ""),
        AccountPreset("花呗", "CREDIT", ""),
        AccountPreset("现金", "CASH", ""),
        AccountPreset("储蓄卡", "SAVINGS", ""),
        AccountPreset("数字人民币", "PAYMENT", ""),
    )
}
