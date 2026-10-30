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
        Rule(Regex("咖啡|奶茶|星巴克|瑞幸|喜茶|蜜雪|茶饮"), "餐饮", "咖啡茶饮"),
        Rule(Regex("零食|水果|便利店|超市|罗森|全家|7-?11"), "餐饮", "零食"),
        Rule(Regex("地铁|公交|单车|哈啰|青桔|乘车|公交卡"), "交通", "公交地铁"),
        Rule(Regex("打车|滴滴|出租|优步|曹操"), "交通", "打车"),
        Rule(Regex("加油|停车|高速|洗车|ETC"), "交通", "加油停车"),
        Rule(Regex("京东|淘宝|天猫|拼多多|商场|购物|沃尔玛|永辉|盒马|网店"), "购物", "日用品"),
        Rule(Regex("衣服|鞋|优衣库|无印|服饰|内衣"), "购物", "服饰"),
        Rule(Regex("小米|苹果|数码|电脑|手机|家电|耳机"), "购物", "数码"),
        Rule(Regex("房租|租房"), "居住", "房租"),
        Rule(Regex("水费|电费|燃气|取暖|水电"), "居住", "水电燃气"),
        Rule(Regex("物业"), "居住", "物业"),
        Rule(Regex("电影|影院|演出|门票|剧本"), "娱乐", "电影"),
        Rule(Regex("游戏|steam|手游"), "娱乐", "游戏"),
        Rule(Regex("会员|视频会员|音乐|订阅"), "娱乐", "会员"),
        Rule(Regex("医院|门诊|挂号|体检|牙科|诊所"), "医疗", "门诊"),
        Rule(Regex("药房|药店|药品|买药"), "医疗", "药品"),
        Rule(Regex("红包|礼金|份子|礼物|请客"), "人情", "红包"),
        Rule(Regex("课程|网课|培训|考试|学费"), "学习", "课程"),
        Rule(Regex("书店|书籍|文具|买书"), "学习", "书籍"),
        Rule(Regex("话费|流量|充值话费"), "通讯", "话费"),
        Rule(Regex("宽带|网费"), "通讯", "宽带"),
    )

    private val incomeRules = listOf(
        Rule(Regex("工资|薪资|代发|月薪"), "工资"),
        Rule(Regex("奖金|绩效|年终|提成"), "奖金"),
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
        Regex("信用卡") to "信用卡",
        Regex("储蓄卡|借记卡|银行卡|工行|建行|招行|农行|中行|工商|建设|农业") to "储蓄卡",
        Regex("现金") to "现金",
        Regex("数字人民币|dcep|数币") to "数字人民币",
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
        // Only general food may receive a meal; explicit subcategories and manual meals survive.
        if (categoryHit?.category == "餐饮" && (categoryHit.subCategory == null || (categoryHit.subCategory == "三餐" && categoryHit.source == RuleSource.BUILTIN))) {
            val text = listOfNotNull(draft.merchant, draft.note, draft.strategy).joinToString(" ")
            val explicit = MealRules.names.firstOrNull { text.contains(it) }
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
        val text = listOfNotNull(draft.merchant, draft.note, draft.strategy).joinToString(" ")
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

        accounts.sortedByDescending { it.length }
            .firstOrNull { text.contains(it, ignoreCase = true) }
            ?.let { return it }

        accountHints.firstOrNull { it.first.containsMatchIn(text.lowercase()) }?.second
            ?.let { hint -> AssetMath.canonicalName(hint, accounts, AssetStore.accounts.value)?.let { return it } }

        return AssetStore.accounts.value.asSequence().filter { !it.archived && it.name in accounts }
            .flatMap { account -> AssetMath.aliases(account).asSequence().map { it to account.name } }
            .sortedByDescending { it.first.length }.firstOrNull { text.contains(it.first, ignoreCase = true) }?.second
    }
}
