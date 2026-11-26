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
        "local_cafe" to listOf("咖啡", "茶饮"),
        "cup_soda" to listOf("奶茶", "饮品", "饮料", "果汁"),
        "pizza" to listOf("快餐", "汉堡", "披萨", "西餐", "简餐"),
        "beer" to listOf("啤酒", "酒吧", "宵夜"),
        "wine" to listOf("酒", "聚餐", "烧烤", "火锅"),
        "soup" to listOf("面食", "粉面", "麻辣烫", "汤", "煲仔"),
        "cookie" to listOf("零食", "饼干", "小吃", "坚果"),
        "ice_cream" to listOf("冰淇淋", "雪糕", "冷饮"),
        "apple" to listOf("买菜", "水果", "生鲜", "蔬菜"),
        "cake" to listOf("甜品", "蛋糕", "烘焙"),
        "restaurant" to listOf("餐饮", "三餐", "早餐", "午餐", "晚餐", "夜宵", "外卖", "食", "饭"),
        "parking" to listOf("停车", "车位"),
        "local_taxi" to listOf("打车", "出租车", "网约", "滴滴"),
        "train" to listOf("高铁", "火车", "动车"),
        "ship" to listOf("轮渡", "邮轮", "船票"),
        "compass" to listOf("导航", "高速", "过路"),
        "bike" to listOf("单车", "骑行", "电动车"),
        "wrench" to listOf("保养", "洗车", "汽修", "维修"),
        "local_gas_station" to listOf("加油", "油费", "充电桩", "ETC"),
        "directions_bus" to listOf("交通", "公交", "地铁", "通勤"),
        "piggy_bank" to listOf("存钱", "储蓄罐", "零钱罐", "定期"),
        "coins" to listOf("零钱", "硬币", "零花"),
        "account_balance_wallet" to listOf("现金", "钱包"),
        "notebook_pen" to listOf("文具", "笔记", "练字", "考研", "考证"),
        "school" to listOf("学习", "课程", "教育", "培训", "考试"),
        "book" to listOf("书籍", "书", "电子书"),
        "gem" to listOf("首饰", "珠宝", "黄金", "钻石", "奢侈"),
        "watch" to listOf("手表", "腕表", "表带"),
        "glasses" to listOf("眼镜", "配镜", "隐形"),
        "scissors" to listOf("理发", "剪发", "美发", "裁缝"),
        "checkroom" to listOf("服饰", "衣", "鞋", "包", "帽"),
        "devices" to listOf("数码", "电子", "电脑", "电器"),
        "package_box" to listOf("快递", "包裹", "邮寄"),
        "sofa" to listOf("家居", "家具", "软装"),
        "bed" to listOf("酒店", "住宿", "民宿", "床品"),
        "droplets" to listOf("水费", "自来水", "净水"),
        "flame" to listOf("燃气", "煤气", "天然气", "供暖"),
        "lightbulb" to listOf("电费", "照明", "灯泡"),
        "key_round" to listOf("房租", "租金", "门禁", "开锁"),
        "shopping_cart" to listOf("购物", "日用", "超市", "百货"),
        "local_mall" to listOf("商场", "逛街"),
        "shopping_bag" to listOf("网购", "电商"),
        "apartment" to listOf("房贷", "物业", "按揭"),
        "bolt" to listOf("水电", "充电"),
        "wifi" to listOf("宽带", "网费"),
        "home" to listOf("居住", "家", "装修"),
        "ticket" to listOf("门票", "演出", "话剧", "景点"),
        "tv" to listOf("电视", "视频", "追剧", "影院"),
        "headphones" to listOf("耳机", "音频", "播客"),
        "palette" to listOf("绘画", "画画", "手作", "兴趣"),
        "tent" to listOf("露营", "野餐", "户外"),
        "movie" to listOf("娱乐", "电影"),
        "mic" to listOf("KTV", "唱歌"),
        "users" to listOf("聚会", "社交", "团建", "家庭"),
        "sports_esports" to listOf("游戏", "电竞"),
        "music" to listOf("音乐", "演唱会"),
        "camera" to listOf("摄影", "相机"),
        "subscriptions" to listOf("会员", "订阅", "软件"),
        "fitness_center" to listOf("健身", "运动", "体育", "瑜伽", "球"),
        "spa" to listOf("美妆", "美容", "护肤", "彩妆"),
        "stethoscope" to listOf("门诊", "挂号", "就医", "看病"),
        "heart_pulse" to listOf("体检", "健康", "复查"),
        "medical_services" to listOf("医疗", "齿科"),
        "pill" to listOf("药", "保健"),
        "shield" to listOf("保险", "社保", "公积金", "医保"),
        "child_care" to listOf("育儿", "孩子", "母婴", "奶粉", "尿布", "早教"),
        "pets" to listOf("宠物", "猫", "狗", "粮"),
        "flight" to listOf("旅行", "机票", "旅游", "度假"),
        "smartphone" to listOf("通讯", "话费", "流量", "手机"),
        "flower" to listOf("鲜花", "花束", "绿植", "花"),
        "card_giftcard" to listOf("人情", "礼物", "礼金", "请客"),
        "heart" to listOf("恋爱", "约会", "公益", "捐赠"),
        "redeem" to listOf("红包"),
        "briefcase" to listOf("兼职", "副业", "外快", "稿费"),
        "work" to listOf("工资", "薪", "报酬"),
        "emoji_events" to listOf("奖金", "奖", "提成"),
        "candlestick" to listOf("股票", "证券", "期货"),
        "bar_chart" to listOf("基金", "指数"),
        "trending_up" to listOf("投资", "理财"),
        "savings" to listOf("利息", "收益", "分红", "储蓄"),
        "badge_percent" to listOf("手续费", "折扣", "利息费", "税"),
        "hand_coins" to listOf("借出", "应收", "报销"),
        "scale" to listOf("借入", "欠款", "还款"),
        "undo" to listOf("退款", "返现"),
        "swap_horiz" to listOf("转账"),
        "credit_card" to listOf("信用", "银行卡", "储蓄卡", "花呗", "白条"),
        "landmark" to listOf("银行", "工行", "农行", "中行", "建行", "招行"),
        "receipt_long" to listOf("缴费", "账单", "罚款"),
        "payments" to listOf("支付", "消费"),
    )

    data class CategoryIconGroup(val title: String, val keys: List<String>)

    val groups: List<CategoryIconGroup> = listOf(
        CategoryIconGroup("餐饮美食", listOf("restaurant", "soup", "local_cafe", "cup_soda", "wine", "beer", "pizza", "apple", "cake", "ice_cream", "cookie")),
        CategoryIconGroup("交通出行", listOf("directions_bus", "local_taxi", "train", "flight", "ship", "bike", "local_gas_station", "parking", "compass", "wrench")),
        CategoryIconGroup("购物消费", listOf("shopping_cart", "shopping_bag", "local_mall", "checkroom", "spa", "gem", "watch", "glasses", "scissors", "devices", "package_box", "sofa")),
        CategoryIconGroup("居家生活", listOf("home", "apartment", "key_round", "bed", "bolt", "lightbulb", "droplets", "flame", "wifi", "smartphone", "shield")),
        CategoryIconGroup("娱乐爱好", listOf("movie", "ticket", "tv", "sports_esports", "music", "headphones", "mic", "camera", "palette", "tent", "subscriptions")),
        CategoryIconGroup("健康与学习", listOf("medical_services", "stethoscope", "heart_pulse", "pill", "fitness_center", "school", "book", "notebook_pen")),
        CategoryIconGroup("家庭与人情", listOf("child_care", "pets", "users", "card_giftcard", "redeem", "flower", "heart")),
        CategoryIconGroup("工作与金融", listOf("work", "briefcase", "emoji_events", "piggy_bank", "savings", "trending_up", "bar_chart", "candlestick", "circle_dollar", "coins", "credit_card", "landmark", "vault", "lock", "account_balance_wallet", "payments", "qr_code", "nfc", "globe", "hand_coins", "scale", "badge_percent", "receipt_long", "calendar", "work_history", "undo", "swap_horiz", "more_horiz", FALLBACK)),
    )

    /** 全部分类图标 key，供自定义选择使用。 */
    val keys: List<String> = groups.flatMap { it.keys }.distinct()

    fun suggestedKeyFor(category: String?): String {
        val name = category.orEmpty()
        if (name.isBlank()) return FALLBACK
        return rules.firstOrNull { (_, words) -> words.any { it in name } }?.first ?: FALLBACK
    }

    fun keyFor(category: String?): String {
        val name = category.orEmpty()
        if (name.isBlank()) return FALLBACK
        io.github.mymoney.data.LedgerConfig.customIconKey(name)?.let { return it }
        return suggestedKeyFor(name)
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
            "credit_card" -> AbIcons.Card
            "landmark" -> AbIcons.Bank
            "cup_soda" -> lucideIcon(key, "M6 8l1.75 12.28a2 2 0 0 0 2 1.72h4.54a2 2 0 0 0 2-1.72L18 8", "M5 8h14", "M7 15a6.47 6.47 0 0 1 5 0 6.47 6.47 0 0 0 5 0", "M12 8l1-6h2")
            "wine" -> lucideIcon(key, "M8 22h8", "M7 10h10", "M12 15v7", "M12 15a5 5 0 0 0 5-5c0-2-.5-4-2-8H9c-1.5 4-2 6-2 8a5 5 0 0 0 5 5Z")
            "apple" -> lucideIcon(key, "M12 20.94c1.5 0 2.75 1.06 4 1.06 3 0 6-8 6-12.22A4.91 4.91 0 0 0 17 5c-2.22 0-4 1.44-5 2-1-.56-2.78-2-5-2a4.9 4.9 0 0 0-5 4.78C2 14 5 22 8 22c1.25 0 2.5-1.06 4-1.06Z", "M10 2c1 .5 2 2 2 5")
            "cake" -> lucideIcon(key, "M20 21v-8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8", "M4 16s.5-1 2-1 2.5 2 4 2 2.5-2 4-2 2.5 2 4 2 2-1 2-1", "M2 21h20", "M7 8v3", "M12 8v3", "M17 8v3")
            "train" -> lucideIcon(key, "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M4 11h16", "M12 3v8", "M8 19l-2 3", "M18 22l-2-3", "M8 15h.01", "M16 15h.01")
            "bike" -> lucideIcon(key, "M18.5 17.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M5.5 17.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M15 6a1 1 0 1 0 0-2 1 1 0 0 0 0 2z", "M12 17.5V14l-3-3 4-3 2 3h2")
            "wrench" -> lucideIcon(key, "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
            "package_box" -> lucideIcon(key, "M16.5 9.4l-9-5.19", "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z", "M3.29 7L12 12l8.71-5", "M12 22V12")
            "sofa" -> lucideIcon(key, "M20 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v3", "M2 11v5a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5a2 2 0 0 0-4 0v2H6v-2a2 2 0 0 0-4 0Z", "M4 18v2", "M20 18v2")
            "wifi" -> lucideIcon(key, "M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0")
            "mic" -> lucideIcon(key, "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")
            "music" -> lucideIcon(key, "M9 18V5l12-2v13", "M6 18a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M18 16a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
            "camera" -> lucideIcon(key, "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
            "pill" -> lucideIcon(key, "M10.5 20.5 20.5 10.5a4.95 4.95 0 1 0-7-7l-10 10a4.95 4.95 0 1 0 7 7Z", "M8.5 8.5l7 7")
            "book" -> lucideIcon(key, "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20")
            "briefcase" -> lucideIcon(key, "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16", "M2 6h20v14H2z")
            "heart" -> lucideIcon(key, "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z")
            "trending_up" -> lucideIcon(key, "M22 7l-8.5 8.5-5-5L2 17", "M16 7h6v6")
            "more_horiz" -> lucideIcon(key, "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z", "M17 12h.01", "M12 12h.01", "M7 12h.01")
            "soup" -> lucideIcon(key,
                "M12 21a9 9 0 0 0 9-9H3a9 9 0 0 0 9 9Z",
                "M7 21h10",
                "M19.5 12 22 6",
                "M16.25 3c.27.1 .8 .53 .75 1.36-.06 .83-.93 1.2-1 2.02-.05 .78 .34 1.24 .73 1.62",
                "M11.25 3c.27 .1 .8 .53 .74 1.36-.05 .83-.93 1.2-.98 2.02-.06 .78 .33 1.24 .72 1.62",
                "M6.25 3c.27 .1 .8 .53 .75 1.36-.06 .83-.93 1.2-1 2.02-.05 .78 .34 1.24 .74 1.62",
            )
            "beer" -> lucideIcon(key,
                "M17 11h1a3 3 0 0 1 0 6h-1",
                "M9 12v6",
                "M13 12v6",
                "M14 7.5c-1 0-1.44 .5-3 .5s-2-.5-3-.5-1.72 .5-2.5 .5a2.5 2.5 0 0 1 0-5c.78 0 1.57 .5 2.5 .5S9.44 2 11 2s2 1.5 3 1.5 1.72-.5 2.5-.5a2.5 2.5 0 0 1 0 5c-.78 0-1.5-.5-2.5-.5Z",
                "M5 8v12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V8",
            )
            "pizza" -> lucideIcon(key,
                "M15 11h.01",
                "M11 15h.01",
                "M16 16h.01",
                "m2 16 20 6-6-20A20 20 0 0 0 2 16",
                "M5.71 17.11a17.04 17.04 0 0 1 11.4-11.4",
            )
            "ice_cream" -> lucideIcon(key,
                "m7 11 4.08 10.35a1 1 0 0 0 1.84 0L17 11",
                "M17 7A5 5 0 0 0 7 7",
                "M17 7a2 2 0 0 1 0 4H7a2 2 0 0 1 0-4",
            )
            "cookie" -> lucideIcon(key,
                "M12 2a10 10 0 1 0 10 10 4 4 0 0 1-5-5 4 4 0 0 1-5-5",
                "M8.5 8.5v.01",
                "M16 15.5v.01",
                "M12 12v.01",
                "M11 17v.01",
                "M7 14v.01",
            )
            "ship" -> lucideIcon(key,
                "M12 10.189V14",
                "M12 2v3",
                "M19 13V7a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v6",
                "M19.38 20A11.6 11.6 0 0 0 21 14l-8.188-3.639a2 2 0 0 0-1.624 0L3 14a11.6 11.6 0 0 0 2.81 7.76",
                "M2 21c.6 .5 1.2 1 2.5 1 2.5 0 2.5-2 5-2 1.3 0 1.9 .5 2.5 1s1.2 1 2.5 1c2.5 0 2.5-2 5-2 1.3 0 1.9 .5 2.5 1",
            )
            "parking" -> lucideIcon(key,
                "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
                "M9 17V7h4a3 3 0 0 1 0 6H9",
            )
            "compass" -> lucideIcon(key,
                "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
                "m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z",
            )
            "gem" -> lucideIcon(key,
                "M6 3h12l4 6-10 13L2 9Z",
                "M11 3 8 9l4 13 4-13-3-6",
                "M2 9h20",
            )
            "watch" -> lucideIcon(key,
                "M18 12a6 6 0 1 0-12 0 6 6 0 0 0 12 0z",
                "M12 10v2l1 1",
                "m16.13 7.66-.81-4.05a2 2 0 0 0-2-1.61h-2.68a2 2 0 0 0-2 1.61l-.78 4.05",
                "m7.88 16.36 .8 4a2 2 0 0 0 2 1.61h2.72a2 2 0 0 0 2-1.61l.81-4.05",
            )
            "glasses" -> lucideIcon(key,
                "M10 15a4 4 0 1 0-8 0 4 4 0 0 0 8 0z",
                "M22 15a4 4 0 1 0-8 0 4 4 0 0 0 8 0z",
                "M14 15a2 2 0 0 0-2-2 2 2 0 0 0-2 2",
                "M2.5 13 5 7c.7-1.3 1.4-2 3-2",
                "M21.5 13 19 7c-.7-1.3-1.5-2-3-2",
            )
            "scissors" -> lucideIcon(key,
                "M9 6a3 3 0 1 0-6 0 3 3 0 0 0 6 0z",
                "M8.12 8.12 12 12",
                "M20 4 8.12 15.88",
                "M9 18a3 3 0 1 0-6 0 3 3 0 0 0 6 0z",
                "M14.8 14.8 20 20",
            )
            "key_round" -> lucideIcon(key,
                "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
                "M16.5 7.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
            )
            "bed" -> lucideIcon(key,
                "M2 20v-8a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v8",
                "M4 10V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v4",
                "M12 4v6",
                "M2 18h20",
            )
            "lightbulb" -> lucideIcon(key,
                "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5 .7 .7 1.3 1.5 1.5 2.5",
                "M9 18h6",
                "M10 22h4",
            )
            "droplets" -> lucideIcon(key,
                "M7 16.3c2.2 0 4-1.83 4-4.05 0-1.16-.57-2.26-1.71-3.19S7.29 6.75 7 5.3c-.29 1.45-1.14 2.84-2.29 3.76S3 11.1 3 12.25c0 2.22 1.8 4.05 4 4.05z",
                "M12.56 6.6A10.97 10.97 0 0 0 14 3.02c.5 2.5 2 4.9 4 6.5s3 3.5 3 5.5a6.98 6.98 0 0 1-11.91 4.97",
            )
            "flame" -> lucideIcon(key,
                "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153 .433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z",
            )
            "ticket" -> lucideIcon(key,
                "M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z",
                "M13 5v2",
                "M13 17v2",
                "M13 11v2",
            )
            "tv" -> lucideIcon(key,
                "m17 2-5 5-5-5",
                "M4 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z",
            )
            "headphones" -> lucideIcon(key,
                "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3",
            )
            "palette" -> lucideIcon(key,
                "M13.5 6.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
                "M17.5 10.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
                "M8.5 7.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
                "M6.5 12.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
                "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z",
            )
            "tent" -> lucideIcon(key,
                "M3.5 21 14 3",
                "M20.5 21 10 3",
                "M15.5 21 12 15l-3.5 6",
                "M2 21h20",
            )
            "stethoscope" -> lucideIcon(key,
                "M11 2v2",
                "M5 2v2",
                "M5 3H4a2 2 0 0 0-2 2v4a6 6 0 0 0 12 0V5a2 2 0 0 0-2-2h-1",
                "M8 15a6 6 0 0 0 12 0v-3",
                "M22 10a2 2 0 1 0-4 0 2 2 0 0 0 4 0z",
            )
            "heart_pulse" -> lucideIcon(key,
                "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z",
                "M3.22 12H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27",
            )
            "notebook_pen" -> lucideIcon(key,
                "M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4",
                "M2 6h4",
                "M2 10h4",
                "M2 14h4",
                "M2 18h4",
                "M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506 .854l-.837 2.87a.5.5 0 0 0 .62 .62l2.87-.837a2 2 0 0 0 .854-.506z",
            )
            "users" -> lucideIcon(key,
                "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
                "M13 7a4 4 0 1 0-8 0 4 4 0 0 0 8 0z",
                "M22 21v-2a4 4 0 0 0-3-3.87",
                "M16 3.13a4 4 0 0 1 0 7.75",
            )
            "flower" -> lucideIcon(key,
                "M12 5a3 3 0 1 1 3 3m-3-3a3 3 0 1 0-3 3m3-3v1M9 8a3 3 0 1 0 3 3M9 8h1m5 0a3 3 0 1 1-3 3m3-3h-1m-2 3v-1",
                "M13 8a1 1 0 1 0-2 0 1 1 0 0 0 2 0z",
                "M12 10v12",
                "M12 22c4.2 0 7-1.667 7-5-4.2 0-7 1.667-7 5Z",
                "M12 22c-4.2 0-7-1.667-7-5 4.2 0 7 1.667 7 5Z",
            )
            "piggy_bank" -> lucideIcon(key,
                "M19 5c-1.5 0-2.8 1.4-3 2-3.5-1.5-11-.3-11 5 0 1.8 0 3 2 4.5V20h4v-2h3v2h4v-4c1-.5 1.7-1 2-2h2v-4h-2c0-1-.5-1.5-1-2V5z",
                "M2 9v1c0 1.1 .9 2 2 2h1",
                "M16 11h.01",
            )
            "coins" -> lucideIcon(key,
                "M14 8A6 6 0 1 0 2 8a6 6 0 0 0 12 0Z",
                "M18.09 10.37A6 6 0 1 1 10.34 18",
                "M7 6h1v4",
                "m16.71 13.88 .7 .71-2.82 2.82",
            )
            "badge_percent" -> lucideIcon(key,
                "M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z",
                "m15 9-6 6",
                "M9 9h.01",
                "M15 15h.01",
            )
            "circle_dollar" -> lucideIcon(key,
                "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
                "M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8",
                "M12 18V6",
            )
            "bar_chart" -> lucideIcon(key,
                "M3 3v16a2 2 0 0 0 2 2h16",
                "M18 17V9",
                "M13 17V5",
                "M8 17v-3",
            )
            "candlestick" -> lucideIcon(key,
                "M9 5v4",
                "M7 9h4v6H7z",
                "M9 15v2",
                "M17 3v2",
                "M15 5h4v8h-4z",
                "M17 13v3",
                "M3 3v16a2 2 0 0 0 2 2h16",
            )
            "vault" -> lucideIcon(key,
                "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
                "M7.5 7.5h.01",
                "m10 10-2.5-2.5",
                "M16.5 7.5h.01",
                "m16.5 7.5-2.5 2.5",
                "M7.5 16.5h.01",
                "m7.5 16.5 2.5-2.5",
                "M16.5 16.5h.01",
                "m16.5 16.5-2.5-2.5",
                "M14 12a2 2 0 1 0-4 0 2 2 0 0 0 4 0z",
            )
            "lock" -> lucideIcon(key,
                "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
                "M7 11V7a5 5 0 0 1 10 0v4",
            )
            "qr_code" -> lucideIcon(key,
                "M3 3h5v5H3z",
                "M16 3h5v5h-5z",
                "M3 16h5v5H3z",
                "M21 16h-3a2 2 0 0 0-2 2v3",
                "M21 21v.01",
                "M12 7v3a2 2 0 0 1-2 2H7",
                "M3 12h.01",
                "M12 3h.01",
                "M12 16v.01",
                "M16 12h1",
                "M21 12v.01",
                "M12 21v-1",
            )
            "nfc" -> lucideIcon(key,
                "M6 8.32a7.43 7.43 0 0 1 0 7.36",
                "M9.46 6.21a11.76 11.76 0 0 1 0 11.58",
                "M12.91 4.1a15.91 15.91 0 0 1 .01 15.8",
                "M16.37 2a20.16 20.16 0 0 1 0 20",
            )
            "globe" -> lucideIcon(key,
                "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
                "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
                "M2 12h20",
            )
            "hand_coins" -> lucideIcon(key,
                "M11 15h2a2 2 0 1 0 0-4h-3c-.6 0-1.1 .2-1.4 .6L3 17",
                "m7 21 1.6-1.4c.3-.4 .8-.6 1.4-.6h4c1.1 0 2.1-.4 2.8-1.2l4.6-4.4a2 2 0 0 0-2.75-2.91l-4.2 3.9",
                "m2 16 6 6",
                "M18.9 9a2.9 2.9 0 1 0-5.8 0 2.9 2.9 0 0 0 5.8 0z",
                "M9 5a3 3 0 1 0-6 0 3 3 0 0 0 6 0z",
            )
            "scale" -> lucideIcon(key,
                "m16 16 3-8 3 8c-.87 .65-1.92 1-3 1s-2.13-.35-3-1Z",
                "m2 16 3-8 3 8c-.87 .65-1.92 1-3 1s-2.13-.35-3-1Z",
                "M7 21h10",
                "M12 3v18",
                "M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2",
            )
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
    val colorHex: String? = null,
)

data class CategoryPresetPack(
    val id: String,
    val title: String,
    val subtitle: String,
    val isIncome: Boolean,
    val presets: List<CategoryPreset>,
)

object CategoryPresets {

    val expense: List<CategoryPreset> = listOf(
        CategoryPreset("餐饮", "restaurant", listOf("三餐", "早餐", "午餐", "晚餐", "夜宵", "外卖", "零食", "买菜水果")),
        CategoryPreset("咖啡茶饮", "local_cafe", listOf("咖啡", "奶茶", "饮品", "酒水"), "#8D6E63"),
        CategoryPreset("交通", "directions_bus", listOf("公交地铁", "打车", "高铁火车", "加油停车", "保养洗车")),
        CategoryPreset("购物", "shopping_cart", listOf("日用品", "超市", "网购", "快递", "家居")),
        CategoryPreset("服饰", "checkroom", listOf("衣服", "鞋帽", "箱包配饰")),
        CategoryPreset("美妆护肤", "spa", listOf("护肤", "彩妆", "理发美容"), "#EC4899"),
        CategoryPreset("数码", "devices", listOf("手机", "电脑", "配件", "软件应用")),
        CategoryPreset("居住", "home", listOf("房租", "房贷", "水电燃气", "物业", "宽带")),
        CategoryPreset("娱乐", "movie", listOf("电影", "游戏", "会员订阅", "聚会")),
        CategoryPreset("运动健身", "fitness_center", listOf("健身房", "运动装备", "球类游泳")),
        CategoryPreset("医疗", "medical_services", listOf("门诊", "药品", "体检", "齿科")),
        CategoryPreset("学习", "school", listOf("课程", "书籍", "考试培训")),
        CategoryPreset("通讯", "smartphone", listOf("话费", "流量")),
        CategoryPreset("旅行", "flight", listOf("机票", "酒店", "门票"), "#0EA5E9"),
        CategoryPreset("人情", "card_giftcard", listOf("红包", "礼物", "请客")),
        CategoryPreset("宠物", "pets", listOf("粮食", "用品", "医疗")),
        CategoryPreset("育儿", "child_care", listOf("奶粉", "尿布", "玩具", "早教")),
        CategoryPreset("汽车养车", "wrench", listOf("加油充电", "停车高速", "洗车保养", "车险违章"), "#3B82F6"),
        CategoryPreset("保险税费", "shield", listOf("商业保险", "社保个税", "滞纳罚款"), "#0D9488"),
        CategoryPreset("恋爱社交", "heart", listOf("约会", "礼物", "聚餐"), "#EC4899"),
        CategoryPreset("其他", "more_horiz", listOf("公益", "罚款", "手续费")),
    )

    val income: List<CategoryPreset> = listOf(
        CategoryPreset("工资", "work", listOf("基本工资", "绩效", "补贴")),
        CategoryPreset("奖金", "emoji_events", listOf("年终奖", "提成")),
        CategoryPreset("兼职副业", "briefcase", listOf("外快", "稿费", "接单"), "#D97706"),
        CategoryPreset("理财收益", "savings", listOf("利息", "基金", "股票", "分红")),
        CategoryPreset("红包", "redeem", listOf("微信红包", "礼金")),
        CategoryPreset("报销", "receipt_long", listOf("差旅报销", "医疗报销")),
        CategoryPreset("退款", "undo", listOf("退货退款", "返现")),
        CategoryPreset("房租分红", "apartment", listOf("收租", "分红"), "#0D9488"),
        CategoryPreset("其他", "more_horiz"),
    )

    val packs: List<CategoryPresetPack> = listOf(
        CategoryPresetPack(
            id = "daily_plus",
            title = "精致日常包",
            subtitle = "咖啡茶饮 · 服饰 · 美妆护肤 · 数码 · 运动健身",
            isIncome = false,
            presets = expense.filter { it.name in setOf("咖啡茶饮", "服饰", "美妆护肤", "数码", "运动健身") },
        ),
        CategoryPresetPack(
            id = "auto_owner",
            title = "有车出行包",
            subtitle = "汽车养车（加油/停车/保养/车险）· 旅行",
            isIncome = false,
            presets = expense.filter { it.name in setOf("汽车养车", "旅行") },
        ),
        CategoryPresetPack(
            id = "family_pet",
            title = "家庭与萌宠包",
            subtitle = "育儿 · 宠物 · 恋爱社交 · 保险税费",
            isIncome = false,
            presets = expense.filter { it.name in setOf("育儿", "宠物", "恋爱社交", "保险税费") },
        ),
        CategoryPresetPack(
            id = "income_plus",
            title = "多元收入包",
            subtitle = "兼职副业 · 理财收益 · 报销 · 房租分红",
            isIncome = true,
            presets = income.filter { it.name in setOf("兼职副业", "理财收益", "报销", "房租分红") },
        ),
    )

    fun forType(isIncome: Boolean): List<CategoryPreset> = if (isIncome) income else expense
}
