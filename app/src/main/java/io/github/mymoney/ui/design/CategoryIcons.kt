package io.github.mymoney.ui.design

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mymoney.R

/**
 * 分类图标：一套实心 Material Symbols 图标（`res/drawable/cat_*.xml`，Apache-2.0）。
 *
 * 为什么不用 linear 线框（[AbIcons]）：分类是「一眼扫过去分辨种类」的场景，实心图形在
 * 38dp 的小徽章里比 1.9dp 细线更容易认；dock 才用细线保持轻透。
 *
 * 图标按**关键字**匹配而不是按分类名精确相等：用户会自己加「早餐」「早餐摊」「夜宵外卖」，
 * 精确匹配会让绝大多数自定义分类掉到兜底图标上。关键字表按「市面上大多数记账 App 都有的
 * 分类」排的，命中顺序从具体到宽泛。
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
        "school" to listOf("学习", "课程", "书", "教育", "培训"),
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
        "account_balance_wallet" to listOf("现金", "钱包"),
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

    /** key → drawable；未知 key 退回兜底图标，不会因为数据脏了就崩。 */
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

/** 分类图标徽章：实色圆角底 + 对比前景，和账单行、分类管理页共用。 */
@Composable
fun CategoryBadge(
    category: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    foreground: Color? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size >= 40.dp) 12.dp else 9.dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(CategoryIcons.resourceFor(CategoryIcons.keyFor(category))),
            contentDescription = null,
            modifier = Modifier.size(size * 0.56f),
            tint = foreground ?: badgeForeground(tint),
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
