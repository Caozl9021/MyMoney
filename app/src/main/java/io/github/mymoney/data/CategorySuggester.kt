package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType

/**
 * 「自动识别账单类型」：商户/备注 → 分类（含二级）；支付方式 → 账户。
 *
 * 纯函数，规则可单测。**只返回传入清单里真实存在的名字**，匹配不上返回 null，
 * 绝不硬塞清单外的名字（那正是之前导入后落到"未分类"的根因）。
 */
object CategorySuggester {

    data class Suggestion(
        val category: String? = null,
        val subCategory: String? = null,
        val account: String? = null,
        val categoryHit: RuleHit? = null,
        val accountHit: RuleHit? = null,
    )

    private data class Rule(val regex: Regex, val category: String, val sub: String? = null)

    private val expenseRules = listOf(
        Rule(Regex("早餐|午餐|晚餐|夜宵|沙县|兰州|米线|麻辣烫|肯德基|麦当劳|小吃|食堂|面馆|餐厅|饭店|火锅|烧烤|汉堡|便当|外卖|美团|饿了么"), "餐饮", "三餐"),
        Rule(Regex("咖啡|星巴克|瑞幸"), "咖啡茶饮", "咖啡"),
        Rule(Regex("奶茶|喜茶|蜜雪|茶饮|霸王茶姬|奈雪"), "咖啡茶饮", "奶茶"),
        Rule(Regex("饮料|酒水|啤酒|白酒"), "咖啡茶饮", "饮品"),
        Rule(Regex("咖啡|奶茶|星巴克|瑞幸|喜茶|蜜雪|茶饮"), "餐饮", "咖啡茶饮"),
        Rule(Regex("买菜|菜市场|水果|生鲜|蔬菜|叮咚|朴朴"), "餐饮", "买菜水果"),
        Rule(Regex("零食|便利店|超市|罗森|全家|7-?11"), "餐饮", "零食"),
        Rule(Regex("地铁|公交|单车|哈啰|青桔|乘车|公交卡"), "交通", "公交地铁"),
        Rule(Regex("打车|滴滴|出租|优步|曹操"), "交通", "打车"),
        Rule(Regex("高铁|火车|12306|动车"), "交通", "高铁火车"),
        Rule(Regex("加油|停车|高速|ETC|充电桩"), "交通", "加油停车"),
        Rule(Regex("洗车|保养|汽修"), "交通", "保养洗车"),
        Rule(Regex("快递|顺丰|菜鸟|寄件"), "购物", "快递"),
        Rule(Regex("超市|沃尔玛|永辉|盒马|山姆"), "购物", "超市"),
        Rule(Regex("京东|淘宝|天猫|拼多多|商场|购物|网店"), "购物", "日用品"),
        Rule(Regex("衣服|优衣库|ZARA|服饰|内衣|外套"), "服饰", "衣服"),
        Rule(Regex("鞋|球鞋|运动鞋"), "服饰", "鞋帽"),
        Rule(Regex("衣服|鞋|优衣库|无印|服饰|内衣"), "购物", "服饰"),
        Rule(Regex("护肤|美妆|口红|化妆品|面膜"), "美妆护肤", "护肤"),
        Rule(Regex("理发|美容|美发|造型"), "美妆护肤", "理发美容"),
        Rule(Regex("小米|苹果|手机"), "数码", "手机"),
        Rule(Regex("电脑|笔记本|键盘|鼠标"), "数码", "电脑"),
        Rule(Regex("数码|耳机|充电宝|家电|App|软件"), "数码", "配件"),
        Rule(Regex("小米|苹果|数码|电脑|手机|家电|耳机"), "购物", "数码"),
        Rule(Regex("房租|租房"), "居住", "房租"),
        Rule(Regex("房贷|按揭|月供"), "居住", "房贷"),
        Rule(Regex("水费|电费|燃气|取暖|水电"), "居住", "水电燃气"),
        Rule(Regex("物业"), "居住", "物业"),
        Rule(Regex("宽带|网费"), "居住", "宽带"),
        Rule(Regex("电影|影院|演出|剧本"), "娱乐", "电影"),
        Rule(Regex("游戏|steam|手游"), "娱乐", "游戏"),
        Rule(Regex("会员|视频会员|音乐|订阅"), "娱乐", "会员订阅"),
        Rule(Regex("会员|视频会员|音乐|订阅"), "娱乐", "会员"),
        Rule(Regex("健身|瑜伽|游泳|羽毛球|迪卡侬|体育"), "运动健身", "健身房"),
        Rule(Regex("医院|门诊|挂号|体检|牙科|诊所"), "医疗", "门诊"),
        Rule(Regex("药房|药店|药品|买药"), "医疗", "药品"),
        Rule(Regex("红包|礼金|份子|礼物|请客"), "人情", "红包"),
        Rule(Regex("机票|航司|携程|去哪儿|飞猪"), "旅行", "机票"),
        Rule(Regex("酒店|民宿|宾馆|门票|景区"), "旅行", "酒店"),
        Rule(Regex("猫粮|狗粮|宠物|猫砂|宠物医院"), "宠物", "粮食"),
        Rule(Regex("奶粉|尿布|纸尿裤|母婴|孩子|玩具"), "育儿", "奶粉"),
        Rule(Regex("课程|网课|培训|考试|学费"), "学习", "课程"),
        Rule(Regex("书店|书籍|文具|买书"), "学习", "书籍"),
        Rule(Regex("话费|流量|充值话费"), "通讯", "话费"),
        Rule(Regex("宽带|网费"), "通讯", "宽带"),
    )

    private val incomeRules = listOf(
        Rule(Regex("工资|薪资|代发|月薪"), "工资"),
        Rule(Regex("奖金|绩效|年终|提成"), "奖金"),
        Rule(Regex("兼职|副业|外快|稿费"), "兼职副业"),
        Rule(Regex("报销"), "报销"),
        Rule(Regex("退款|退货|退回|返现"), "退款"),
        Rule(Regex("利息|收益|理财|分红|余额宝"), "理财收益"),
        Rule(Regex("红包|礼金|收款|收到转账"), "红包"),
    )

    /** "支付方式"原始文案 → 账户候选（最终仍要能在账户清单里找到才采用） */
    private val accountHints: List<Pair<Regex, String>> = listOf(
        Regex("零钱通") to "微信零钱通",
        Regex("零钱|微信") to "微信钱包",
        Regex("余额宝") to "余额宝",
        Regex("花呗|借呗") to "花呗",
        Regex("白条") to "白条",
        Regex("云闪付") to "云闪付",
        Regex("数字人民币|dcep|数币") to "数字人民币",
        Regex("信用卡") to "信用卡",
        Regex("工商|工行") to "工商银行",
        Regex("农业|农行") to "农业银行",
        Regex("中国银行|中行") to "中国银行",
        Regex("建设|建行") to "建设银行",
        Regex("招商|招行") to "招商银行",
        Regex("交通|交行") to "交通银行",
        Regex("邮政|邮储") to "邮储银行",
        Regex("浦发") to "浦发银行",
        Regex("中信") to "中信银行",
        Regex("兴业") to "兴业银行",
        Regex("光大") to "光大银行",
        Regex("民生") to "民生银行",
        Regex("平安") to "平安银行",
        Regex("广发") to "广发银行",
        Regex("储蓄卡|借记卡|银行卡|工行|建行|招行|农行|中行|工商|建设|农业") to "储蓄卡",
        Regex("现金") to "现金",
        Regex("余额|支付宝|alipay") to "支付宝",
    )

    fun suggest(
        draft: BillDraft,
        expenseGroups: List<CategoryGroup> = LedgerConfig.expenseGroups,
        incomeGroups: List<CategoryGroup> = LedgerConfig.incomeGroups,
        accounts: List<String> = LedgerConfig.accounts,
        snapshot: LocalRuleSnapshot = LocalRuleStore.snapshot(),
        candidates: List<RuleCandidate> = emptyList(),
    ): Suggestion {
        val hits = suggestScored(draft, expenseGroups, incomeGroups, accounts, snapshot, candidates)
        var categoryHit = hits.firstOrNull { it.category != null }
        val accountHit = hits.firstOrNull { it.account != null }
        val groups = if (draft.type == BillType.INCOME) incomeGroups else expenseGroups
        val food = groups.firstOrNull { it.name == "餐饮" }
        // 只要归属「餐饮」，优先采用明确餐名，否则按账单时间默认填充餐段（空白时段才保留原匹配子类）。
        if (categoryHit?.category == "餐饮") {
            val text = listOfNotNull(draft.merchant, draft.productName, draft.note, draft.strategy).joinToString(" ")
            val mealNames = MealRules.windowNames(snapshot.mealWindows).sortedByDescending { it.length }
            val explicit = mealNames.firstOrNull { text.contains(it) }
            val meal = explicit ?: MealRules.match(draft.timeMillis, snapshot.mealWindows)
            val chosen = meal?.takeIf { it in food?.subs.orEmpty() }
            if (chosen != null) categoryHit = categoryHit.copy(subCategory = chosen,
                source = if (explicit != null) categoryHit.source else RuleSource.MEAL,
                explanation = categoryHit.explanation + if (explicit != null) "；明确餐名" else "；账单时间餐段")
        }
        return Suggestion(categoryHit?.category, categoryHit?.subCategory, accountHit?.account, categoryHit, accountHit)
    }

    fun suggestScored(
        draft: BillDraft,
        expenseGroups: List<CategoryGroup> = LedgerConfig.expenseGroups,
        incomeGroups: List<CategoryGroup> = LedgerConfig.incomeGroups,
        accounts: List<String> = LedgerConfig.accounts,
        snapshot: LocalRuleSnapshot = LocalRuleStore.snapshot(),
        candidates: List<RuleCandidate> = emptyList(),
    ): List<RuleHit> {
        val groups = if (draft.type == BillType.INCOME) incomeGroups else expenseGroups
        val text = listOfNotNull(draft.merchant, draft.productName, draft.note, draft.strategy).joinToString(" ")
        val hits = mutableListOf<RuleHit>()
        fun add(category: String?, sub: String?, account: String?, source: RuleSource, length: Int, reason: String) {
            val group = groups.firstOrNull { it.name == category }
            val validAccount = AssetMath.canonicalName(account, accounts, AssetStore.accounts.value)
            // A stale subcategory invalidates that rule rather than pinning a misleading parent.
            if (sub != null && group != null && sub !in group.subs && source in listOf(RuleSource.MANUAL, RuleSource.LEARNED)) {
                if (validAccount != null) hits += RuleHit(account = validAccount, score = source.weight * 1000 + length, source = source, explanation = reason)
                return
            }
            if (group != null || validAccount != null) hits += RuleHit(group?.name,
                sub?.takeIf { it in group?.subs.orEmpty() }, validAccount,
                source.weight * 1000 + length.coerceAtMost(999), source, reason)
        }
        if (snapshot.learningEnabled) snapshot.learned.forEach { rule ->
            if (rule.type == draft.type && rule.merchant == RuleLearning.normalizeMerchant(draft.merchant) &&
                (rule.account == null || rule.method == RuleLearning.methodKey(draft.method)))
                add(rule.category, rule.subCategory, rule.account, rule.source, rule.merchant.length, "${rule.source.label}：${rule.merchant}")
        }
        candidates.filter { it.type == draft.type }.forEach { rule ->
            val target = if (rule.matchMethod) draft.method.orEmpty() else text
            val length = if (rule.regex) runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE).find(target)?.value?.length }.getOrNull()
                else rule.pattern.length.takeIf { rule.pattern.isNotEmpty() && target.contains(rule.pattern, true) }
            if (length != null) add(rule.category, rule.subCategory, rule.account, rule.source, length, "${rule.source.label}：${rule.pattern}")
        }
        val rules = if (draft.type == BillType.INCOME) incomeRules else expenseRules
        rules.forEach { rule -> rule.regex.findAll(text).maxByOrNull { it.value.length }?.let { add(rule.category, rule.sub, null, RuleSource.BUILTIN, it.value.length, "内置关键词：${it.value}") } }
        groups.forEach { group -> if (text.contains(group.name)) add(group.name, null, null, RuleSource.BUILTIN, group.name.length, "清单分类名：${group.name}") }
        if (draft.type == BillType.TRANSFER) add("其他", null, null, RuleSource.BUILTIN, 0, "转账默认分类")
        val account = matchAccount(draft.method, accounts) ?: matchAccount(draft.merchant, accounts)
        add(null, null, account, RuleSource.BUILTIN, 0, "支付方式匹配")
        return hits.sortedByDescending { it.score }
    }

    /** 从"支付方式"文本里找出清单中的账户名；找不到返回 null */
    fun matchAccount(raw: String?, accounts: List<String>): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val activeAccounts = AssetStore.accounts.value.filter { !it.archived && it.name in accounts }
        val tail = AccountNormalizer.tailDigits(text)
        if (tail.isNotEmpty()) {
            activeAccounts.filter { it.cardTail == tail && AccountNormalizer.sameAccount(text, it.name) }
                .singleOrNull()?.let { return it.name }
        }

        accounts.sortedByDescending { it.length }
            .firstOrNull { text.contains(it, ignoreCase = true) }
            ?.let { return it }

        accountHints.firstOrNull { it.first.containsMatchIn(text.lowercase()) }?.second
            ?.let { hint -> AssetMath.canonicalName(hint, accounts, AssetStore.accounts.value)?.let { return it } }

        return activeAccounts.asSequence()
            .flatMap { account -> AssetMath.aliases(account).asSequence().map { it to account.name } }
            .sortedByDescending { it.first.length }.firstOrNull { text.contains(it.first, ignoreCase = true) }?.second
    }
}
