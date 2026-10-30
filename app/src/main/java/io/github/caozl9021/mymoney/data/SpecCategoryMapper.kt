package io.github.caozl9021.mymoney.data

/**
 * 规格分类名（`js_category.js` 产出）→ 用户清单里的「一级/二级」。
 *
 * <h3>为什么必须有一层映射</h3>
 * 两套分类体系是各自独立长出来的：
 * - 规格那套来自 `categories.json`（64 条，按 iCost 的账本结构）：三餐/超市/衣服/交通/
 *   话费网费/电器数码/发红包/请客送礼… 其中「三餐」本身就是一级，早餐/午餐/晚餐是它的二级。
 * - 本项目这套来自 [LedgerConfig.DEFAULT_EXPENSE]（10 组）：餐饮[三餐,早餐,午餐…]、
 *   交通[公交地铁,打车,加油停车]…「餐饮」是一级，「三餐」是二级。
 *
 * 直接把「三餐」当一级塞进草稿，会命中不到任何分组而被丢弃（[CategorySuggester] 的
 * `add()` 明确只接受清单里存在的名字）。所以这里做一次语义翻译。
 *
 * <h3>为什么按清单校验而不是硬写死结果</h3>
 * 用户可以在设置里改名、删组、加二级。映射表只表达"规格的 A 大致等于项目的 B"，
 * 最终能不能用由传入的 `groups` 决定：一级不存在就放弃（返回 null，草稿留空让用户点），
 * 二级不存在就只保留一级。绝不硬塞清单外的名字。
 */
object SpecCategoryMapper {

    /** 规格分类名 → (项目一级, 项目二级)。二级为 null 表示只给一级。 */
    private val EXPENSE: Map<String, Pair<String, String?>> = mapOf(
        // 三餐一族：规格里「三餐」是一级、餐次是二级；项目里「餐饮」是一级
        "三餐" to ("餐饮" to "三餐"),
        "早餐" to ("餐饮" to "早餐"),
        "午餐" to ("餐饮" to "午餐"),
        "晚餐" to ("餐饮" to "晚餐"),
        "夜宵" to ("餐饮" to "夜宵"),
        "早茶" to ("餐饮" to "咖啡茶饮"),
        "下午茶" to ("餐饮" to "咖啡茶饮"),
        "饮料" to ("餐饮" to "咖啡茶饮"),
        "奶茶" to ("餐饮" to "咖啡茶饮"),
        "零食" to ("餐饮" to "零食"),
        "水果" to ("餐饮" to "零食"),
        "蔬菜" to ("餐饮" to "零食"),
        "外卖" to ("餐饮" to "外卖"),
        "超市" to ("购物" to "日用品"),
        "柴米油盐" to ("购物" to "日用品"),
        "购物" to ("购物" to null),
        "网购代付" to ("购物" to "日用品"),
        "衣服" to ("购物" to "服饰"),
        "穿戴" to ("购物" to "服饰"),
        "电器数码" to ("购物" to "数码"),
        "电脑" to ("购物" to "数码"),
        "手机" to ("购物" to "数码"),
        "耳机" to ("购物" to "数码"),
        "键盘" to ("购物" to "数码"),
        "充电宝" to ("购物" to "数码"),
        "交通" to ("交通" to null),
        "打车" to ("交通" to "打车"),
        "公交" to ("交通" to "公交地铁"),
        "地铁" to ("交通" to "公交地铁"),
        "共享单车" to ("交通" to "公交地铁"),
        "汽车加油" to ("交通" to "加油停车"),
        "火车" to ("交通" to null),
        "飞机" to ("交通" to null),
        "旅行" to ("交通" to null),
        "景区门票" to ("娱乐" to null),
        "住房" to ("居住" to null),
        "房租" to ("居住" to "房租"),
        "水电煤" to ("居住" to "水电燃气"),
        "娱乐" to ("娱乐" to null),
        "电影" to ("娱乐" to "电影"),
        "游戏" to ("娱乐" to "游戏"),
        "会员" to ("娱乐" to "会员"),
        "摄影" to ("娱乐" to null),
        "聚会" to ("娱乐" to null),
        "扑克牌" to ("娱乐" to null),
        "麻将" to ("娱乐" to null),
        "医疗" to ("医疗" to null),
        "保险" to ("医疗" to null),
        "发红包" to ("人情" to "红包"),
        "请客送礼" to ("人情" to "礼物"),
        "学习" to ("学习" to null),
        "复印" to ("学习" to null),
        "话费网费" to ("通讯" to "话费"),
        "话费" to ("通讯" to "话费"),
        "网费" to ("通讯" to "宽带"),
        "烟酒" to ("其他" to null),
        "美发" to ("其他" to null),
        "运动" to ("其他" to null),
        "育儿" to ("其他" to null),
        "搬家" to ("其他" to null),
        "理发" to ("其他" to null),
        "护肤美妆" to ("其他" to null),
        "党费会费" to ("其他" to null),
        "罚款" to ("其他" to null),
        "捐赠" to ("其他" to null),
        "打赏" to ("其他" to null),
        "收藏" to ("其他" to null),
        "购买App" to ("其他" to null),
        "小说漫画" to ("其他" to null),
        "付费会员" to ("娱乐" to "会员"),
        "快递" to ("其他" to null),
        "其他" to ("其他" to null),
        "其它" to ("其他" to null),
    )

    private val INCOME: Map<String, Pair<String, String?>> = mapOf(
        "工资" to ("工资" to null),
        "收红包" to ("红包" to null),
        "退款" to ("退款" to null),
        "公积金" to ("其他" to null),
        "外快" to ("奖金" to null),
        "理财" to ("理财收益" to null),
        "理财产品" to ("理财收益" to null),
        "快赎理财" to ("理财收益" to null),
        "基金" to ("理财收益" to null),
        "股票基金" to ("理财收益" to null),
        "黄金" to ("理财收益" to null),
        "债券" to ("理财收益" to null),
        "外汇" to ("理财收益" to null),
        "利息" to ("理财收益" to null),
        "生活费" to ("其他" to null),
        "报销" to ("其他" to null),
        "其他" to ("其他" to null),
        "其它" to ("其他" to null),
    )

    /**
     * @return 一级/二级；规格名无法映射到用户清单时返回 null（草稿保持未分类）
     */
    fun resolve(
        specCategory: String,
        type: com.example.billkit.BillType,
        groups: List<CategoryGroup>,
    ): Pair<String, String?>? {
        val table = if (type == com.example.billkit.BillType.INCOME) INCOME else EXPENSE
        // 先看用户清单里是否已有同名一级：用户照 iCost 建过同名分类时，那个名字才是他要的，
        // 不该被我们的语义映射改写。
        groups.firstOrNull { it.name == specCategory }?.let { return it.name to null }
        val mapped = table[specCategory]
        if (mapped == null) {
            // 不是规格表里的名字：也可能是用户清单里的某个二级。
            groups.firstOrNull { specCategory in it.subs }?.let { return it.name to specCategory }
            return null
        }
        val (parent, sub) = mapped
        val group = groups.firstOrNull { it.name == parent }
        if (group != null) {
            val validSub = sub?.takeIf { it in group.subs }
            return group.name to validSub
        }
        // 项目里没有这个一级（用户改过名）：若规格名恰是某个二级，就挂到它的一级上。
        groups.firstOrNull { specCategory in it.subs }?.let { return it.name to specCategory }
        // 最后再试映射目标名本身是否存在于清单（用户可能只删了一级、留了二级）。
        groups.firstOrNull { g -> g.subs.any { it == sub } }?.let { return it.name to sub }
        return null
    }
}
