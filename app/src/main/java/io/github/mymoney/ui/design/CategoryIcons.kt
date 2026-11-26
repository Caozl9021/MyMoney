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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mymoney.R

/**
 * 分类与功能徽章图标（Lucide Icons：24×24、2.0dp 圆头圆角描边）。
 *
 * 与顶部栏、底部悬浮 Dock（[TabGlyphIcon]）、通用操作（[AbIcons]）保持同一套几何语言，
 * 搭配纯色圆形实底与白色图标，全站保持统一质感。
 */
object CategoryIcons {

    /** 兜底：认不出来的分类。 */
    const val FALLBACK = "category"

    /**
     * 分类名 → 图标 key（即 `cat_<key>` 资源名）。
     *
     * 顺序有意义：「咖啡茶饮」要在「餐饮」之前命中，否则带「餐」字的饮品会被归到餐饮。
     */
    private val rules: List<Pair<String, List<String>>> = listOf(
        FALLBACK to listOf("其他", "其它"),
        "local_cafe" to listOf("咖啡", "奶茶", "茶饮", "饮料", "酒水"),
        "restaurant" to listOf("餐饮", "三餐", "早餐", "午餐", "晚餐", "夜宵", "外卖", "食", "零食", "饭"),
        "local_taxi" to listOf("打车", "出租车", "网约", "滴滴"),
        "local_gas_station" to listOf("加油", "停车", "油费", "充电桩"),
        "directions_bus" to listOf("交通", "公交", "地铁", "高铁", "火车", "通勤"),
        "account_balance_wallet" to listOf("现金", "钱包"),
        "school" to listOf("学习", "课程", "电子书", "书", "教育", "培训"),
        "checkroom" to listOf("服饰", "衣", "鞋", "包"),
        "devices" to listOf("数码", "电子", "手机", "电脑", "电器"),
        "shopping_cart" to listOf("购物", "日用", "超市", "百货"),
        "local_mall" to listOf("商场", "逛街"),
        "shopping_bag" to listOf("网购", "电商"),
        "apartment" to listOf("房租", "房贷", "物业"),
        "bolt" to listOf("水电", "电费", "燃气", "暖"),
        "home" to listOf("居住", "家", "装修"),
        "movie" to listOf("娱乐", "电影", "演出", "KTV"),
        "sports_esports" to listOf("游戏", "电竞"),
        "subscriptions" to listOf("会员", "订阅", "软件"),
        "fitness_center" to listOf("健身", "运动", "体育"),
        "spa" to listOf("美容", "护肤", "理发"),
        "medical_services" to listOf("医疗", "门诊", "药", "体检", "看病"),
        "child_care" to listOf("育儿", "孩子", "母婴", "奶粉"),
        "pets" to listOf("宠物", "猫", "狗"),
        "flight" to listOf("旅行", "机票", "酒店", "旅游"),
        "smartphone" to listOf("通讯", "话费", "宽带", "流量"),
        "card_giftcard" to listOf("人情", "礼物", "礼金"),
        "redeem" to listOf("红包"),
        "work" to listOf("工资", "薪", "兼职", "报酬"),
        "emoji_events" to listOf("奖金", "奖"),
        "savings" to listOf("理财", "基金", "股票", "利息", "收益"),
        "undo" to listOf("退款", "返现"),
        "swap_horiz" to listOf("转账"),
        "receipt_long" to listOf("缴费", "账单", "手续费"),
        "payments" to listOf("支付", "消费"),
    )

    /** 全部分类图标 key，供「预设分类」以外的自定义选择使用。 */
    val keys: List<String> = listOf(
        "restaurant", "local_cafe", "directions_bus", "local_taxi", "local_gas_station",
        "shopping_cart", "shopping_bag", "checkroom", "devices", "local_mall",
        "home", "apartment", "bolt", "movie", "sports_esports", "subscriptions",
        "fitness_center", "spa", "medical_services", "school", "child_care",
        "pets", "flight", "smartphone", "card_giftcard", "redeem",
        "work", "emoji_events", "savings", "undo", "swap_horiz",
        "receipt_long", "payments", "account_balance_wallet", "more_horiz", FALLBACK,
    )

    fun keyFor(category: String?): String {
        val name = category.orEmpty()
        if (name.isBlank()) return FALLBACK
        return rules.firstOrNull { (_, words) -> words.any { it in name } }?.first ?: FALLBACK
    }

    /** key → 官方 Lucide 线性矢量图标；与底部 Dock 与通用操作同源。 */
    private val vectorCache = mutableMapOf<String, ImageVector>()

    fun vectorFor(key: String): ImageVector = vectorCache.getOrPut(key) {
        when (key) {
            "restaurant" -> lucideIcon(key,
                "M3 2v7c0 1.1 .9 2 2 2h4a2 2 0 0 0 2-2V2",
                "M7 2v20",
                "M21 15V2a5 5 0 0 0-5 5v6c0 1.1 .9 2 2 2h3Zm0 0v7",
            )
            "local_cafe" -> lucideIcon(key,
                "M10 2v2",
                "M14 2v2",
                "M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1",
                "M6 2v2",
            )
            "directions_bus" -> lucideIcon(key,
                "M4 6 2 7",
                "M10 6h4",
                "M22 7l-2-1",
                "M6 3h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
                "M4 11h16",
                "M8 15h.01",
                "M16 15h.01",
                "M6 19v2",
                "M18 21v-2",
            )
            "local_taxi" -> lucideIcon(key,
                "M10 2h4",
                "M21 8l-2 2-1.5-3.7A2 2 0 0 0 15.646 5H8.4a2 2 0 0 0-1.903 1.257L5 10 3 8",
                "M7 14h.01",
                "M17 14h.01",
                "M5 10h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
                "M5 18v2",
                "M19 18v2",
            )
            "local_gas_station" -> lucideIcon(key,
                "M3 22h12",
                "M4 9h10",
                "M14 22V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v18",
                "M14 13h2a2 2 0 0 1 2 2v2a2 2 0 0 0 2 2a2 2 0 0 0 2-2V9.83a2 2 0 0 0-.59-1.42L18 5",
            )
            "shopping_cart" -> lucideIcon(key,
                "M8 20a1 1 0 1 0 0 2 1 1 0 0 0 0-2z",
                "M19 20a1 1 0 1 0 0 2 1 1 0 0 0 0-2z",
                "M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12",
            )
            "shopping_bag" -> lucideIcon(key,
                "M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z",
                "M3 6h18",
                "M16 10a4 4 0 0 1-8 0",
            )
            "checkroom" -> lucideIcon(key,
                "M20.38 3.46 16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10c0 1.1 .9 2 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z",
            )
            "devices" -> lucideIcon(key,
                "M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8",
                "M10 19v-3.96 3.15",
                "M7 19h5",
                "M18 12h2a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
            )
            "local_mall" -> AbIcons.Store
            "home" -> AbIcons.Home
            "apartment" -> lucideIcon(key,
                "M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z",
                "M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2",
                "M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2",
                "M10 6h4",
                "M10 10h4",
                "M10 14h4",
                "M10 18h4",
            )
            "bolt" -> AbIcons.Accessibility
            "movie" -> lucideIcon(key,
                "M4 3h16a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
                "M7 3v18",
                "M17 3v18",
                "M2 7.5h5",
                "M2 12h20",
                "M2 16.5h5",
                "M17 7.5h5",
                "M17 16.5h5",
            )
            "sports_esports" -> lucideIcon(key,
                "M6 11h4",
                "M8 9v4",
                "M15 12h.01",
                "M18 10h.01",
                "M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.151A4 4 0 0 0 17.32 5z",
            )
            "subscriptions" -> lucideIcon(key,
                "M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.664a1 1 0 0 0 1.516-.294z",
                "M5 21h14",
            )
            "fitness_center" -> lucideIcon(key,
                "M14.4 14.4 9.6 9.6",
                "M18.657 21.485a2 2 0 1 1-2.829-2.828l-1.767 1.768a2 2 0 1 1-2.829-2.829l6.364-6.364a2 2 0 1 1 2.829 2.829l-1.768 1.767a2 2 0 1 1 2.828 2.829z",
                "M21.5 21.5l-1.4-1.4",
                "M3.9 3.9 2.5 2.5",
                "M6.404 12.768a2 2 0 1 1-2.829-2.829l1.768-1.767a2 2 0 1 1-2.828-2.829l2.828-2.828a2 2 0 1 1 2.829 2.828l1.767-1.768a2 2 0 1 1 2.829 2.829z",
            )
            "spa" -> lucideIcon(key,
                "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z",
                "M20 3v4",
                "M22 5h-4",
            )
            "medical_services" -> lucideIcon(key,
                "M11 2a2 2 0 0 0-2 2v5H4a2 2 0 0 0-2 2v2c0 1.1 .9 2 2 2h5v5c0 1.1 .9 2 2 2h2a2 2 0 0 0 2-2v-5h5a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-5V4a2 2 0 0 0-2-2h-2z",
            )
            "school" -> lucideIcon(key,
                "M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z",
                "M22 10v6",
                "M6 12.5V16a6 3 0 0 0 12 0v-3.5",
            )
            "child_care" -> lucideIcon(key,
                "M9 12h.01",
                "M15 12h.01",
                "M10 16c.5 .3 1.2 .5 2 .5s1.5-.2 2-.5",
                "M19 6.3a9 9 0 0 1 1.8 3.9 2 2 0 0 1 0 3.6 9 9 0 0 1-17.6 0 2 2 0 0 1 0-3.6A9 9 0 0 1 12 3c2 0 3.5 1.1 3.5 2.5s-.9 2.5-2 2.5c-.8 0-1.5-.4-1.5-1",
            )
            "pets" -> lucideIcon(key,
                "M11 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
                "M18 8a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
                "M4 8a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
                "M9 17c-2 0-3.5-1.5-3.5-3.2 0-1.4 1.2-2.5 2.4-3.6 .7-.7 1.4-1.2 2.1-1.2s1.4 .5 2.1 1.2c1.2 1.1 2.4 2.2 2.4 3.6 0 1.7-1.5 3.2-3.5 3.2Z",
            )
            "flight" -> lucideIcon(key,
                "M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9 .1-1.1 .5l-.3 .5c-.2 .5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3 .4 .8 .5 1.3 .3l.5-.2c.4-.3 .6-.7 .5-1.2z",
            )
            "smartphone" -> lucideIcon(key,
                "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
                "M12 18h.01",
            )
            "shield" -> lucideIcon(key,
                "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
                "M9 12l2 2 4-4",
            )
            "work_history" -> lucideIcon(key,
                "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
                "M3 3v5h5",
                "M12 7v5l4 2",
            )
            "calendar" -> lucideIcon(key,
                "M8 2v4",
                "M16 2v4",
                "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
                "M3 10h18",
                "M8 14h.01",
                "M12 14h.01",
                "M16 14h.01",
            )
            "card_giftcard", "redeem" -> lucideIcon(key,
                "M3 8h18v4H3z",
                "M12 8v13",
                "M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7",
                "M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5",
            )
            "work" -> lucideIcon(key,
                "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16",
                "M4 6h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z",
            )
            "emoji_events" -> lucideIcon(key,
                "M6 9H4.5a2.5 2.5 0 0 1 0-5H6",
                "M18 9h1.5a2.5 2.5 0 0 0 0-5H18",
                "M4 22h16",
                "M10 14.66V17c0 .55-.47 .98-.97 1.21C7.85 18.75 7 20.24 7 22",
                "M14 14.66V17c0 .55 .47 .98 .97 1.21C16.15 18.75 17 20.24 17 22",
                "M18 2H6v7a6 6 0 0 0 12 0V2Z",
            )
            "savings" -> lucideIcon(key, "M22 7l-8.5 8.5-5-5L2 17", "M16 7h6v6")
            "undo" -> lucideIcon(key, "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
            "swap_horiz" -> lucideIcon(key, "M8 3 4 7l4 4", "M4 7h16", "M16 21l4-4-4-4", "M20 17H4")
            "receipt_long" -> AbIcons.Receipt
            "payments" -> lucideIcon(key, "M2 6h20v12H2z", "M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", "M6 12h.01", "M18 12h.01")
            "account_balance_wallet" -> AbIcons.Wallet
            "more_horiz" -> lucideIcon(key, "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z", "M17 12h.01", "M12 12h.01", "M7 12h.01")
            else -> lucideIcon(FALLBACK,
                "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
                "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
                "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
                "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
            )
        }
    }

    /** key → drawable；保留兼容旧调用。 */
    fun resourceFor(key: String): Int = when (key) {
        "restaurant" -> R.drawable.cat_restaurant
        "local_cafe" -> R.drawable.cat_local_cafe
        "directions_bus" -> R.drawable.cat_directions_bus
        "local_taxi" -> R.drawable.cat_local_taxi
        "local_gas_station" -> R.drawable.cat_local_gas_station
        "shopping_cart" -> R.drawable.cat_shopping_cart
        "shopping_bag" -> R.drawable.cat_shopping_bag
        "checkroom" -> R.drawable.cat_checkroom
        "devices" -> R.drawable.cat_devices
        "local_mall" -> R.drawable.cat_local_mall
        "home" -> R.drawable.cat_home
        "apartment" -> R.drawable.cat_apartment
        "bolt" -> R.drawable.cat_bolt
        "movie" -> R.drawable.cat_movie
        "sports_esports" -> R.drawable.cat_sports_esports
        "subscriptions" -> R.drawable.cat_subscriptions
        "fitness_center" -> R.drawable.cat_fitness_center
        "spa" -> R.drawable.cat_spa
        "medical_services" -> R.drawable.cat_medical_services
        "school" -> R.drawable.cat_school
        "child_care" -> R.drawable.cat_child_care
        "pets" -> R.drawable.cat_pets
        "flight" -> R.drawable.cat_flight
        "smartphone" -> R.drawable.cat_smartphone
        "shield" -> R.drawable.cat_shield
        "work_history" -> R.drawable.cat_work_history
        "card_giftcard" -> R.drawable.cat_card_giftcard
        "redeem" -> R.drawable.cat_redeem
        "work" -> R.drawable.cat_work
        "emoji_events" -> R.drawable.cat_emoji_events
        "savings" -> R.drawable.cat_savings
        "undo" -> R.drawable.cat_undo
        "swap_horiz" -> R.drawable.cat_swap_horiz
        "receipt_long" -> R.drawable.cat_receipt_long
        "payments" -> R.drawable.cat_payments
        "account_balance_wallet" -> R.drawable.cat_account_balance_wallet
        "more_horiz" -> R.drawable.cat_more_horiz
        else -> R.drawable.cat_category
    }
}

/** 分类图标徽章：纯色圆形实底 + 白色 Lucide 线性图标，和账单行、分类管理页共用。 */
@Composable
fun CategoryBadge(
    category: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    foreground: Color? = null,
) {
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
            imageVector = CategoryIcons.vectorFor(CategoryIcons.keyFor(category)),
            contentDescription = null,
            modifier = Modifier.size(size * 0.54f),
            tint = foreground ?: Color.White,
        )
    }
}

/**
 * 常用分类预设：市面上大多数记账 App 都会有的那一批。
 *
 * 只放「名字 + 二级 + 图标」，添加后就是普通的用户分类，之后可以改名、改二级、删除 ——
 * 预设只是替用户把名字打好，不是不可变的内置数据。
 */
data class CategoryPreset(
    val name: String,
    val iconKey: String,
    val subs: List<String> = emptyList(),
)

object CategoryPresets {

    val expense: List<CategoryPreset> = listOf(
        CategoryPreset("餐饮", "restaurant", listOf("三餐", "早餐", "午餐", "晚餐", "夜宵", "外卖")),
        CategoryPreset("咖啡茶饮", "local_cafe", listOf("咖啡", "奶茶", "其他饮品")),
        CategoryPreset("交通", "directions_bus", listOf("公交地铁", "打车", "加油停车")),
        CategoryPreset("购物", "shopping_cart", listOf("日用品", "超市", "网购")),
        CategoryPreset("服饰", "checkroom", listOf("衣服", "鞋", "包")),
        CategoryPreset("数码", "devices", listOf("手机", "电脑", "配件")),
        CategoryPreset("居住", "home", listOf("房租", "水电燃气", "物业")),
        CategoryPreset("娱乐", "movie", listOf("电影", "游戏", "会员")),
        CategoryPreset("医疗", "medical_services", listOf("门诊", "药品", "体检")),
        CategoryPreset("学习", "school", listOf("课程", "书籍")),
        CategoryPreset("运动健身", "fitness_center", listOf("健身房", "装备")),
        CategoryPreset("美容", "spa", listOf("护肤", "理发")),
        CategoryPreset("通讯", "smartphone", listOf("话费", "宽带")),
        CategoryPreset("人情", "card_giftcard", listOf("红包", "礼物")),
        CategoryPreset("旅行", "flight", listOf("机票", "酒店", "门票")),
        CategoryPreset("宠物", "pets", listOf("粮食", "医疗")),
        CategoryPreset("育儿", "child_care", listOf("奶粉", "尿布", "玩具")),
        CategoryPreset("其他", "more_horiz"),
    )

    val income: List<CategoryPreset> = listOf(
        CategoryPreset("工资", "work"),
        CategoryPreset("奖金", "emoji_events"),
        CategoryPreset("红包", "redeem"),
        CategoryPreset("理财收益", "savings"),
        CategoryPreset("退款", "undo"),
        CategoryPreset("报销", "receipt_long"),
        CategoryPreset("其他", "more_horiz"),
    )

    fun forType(isIncome: Boolean): List<CategoryPreset> = if (isIncome) income else expense
}
