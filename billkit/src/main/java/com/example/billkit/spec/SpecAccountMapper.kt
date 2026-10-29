package com.example.billkit.spec

/**
 * SPEC §8 阶段 6：把规则产出的原始账户名（`accountNameFrom`）映射到用户账本里真实存在的账户。
 *
 * 规则里的写法五花八门——`招商银行信用卡(1234)`、`支付宝余额`、`余额宝`、`微信零钱`、
 * `美团月付`——而账本里的账户是用户自己建的（默认是 `储蓄卡`/`信用卡`/`支付宝`/`微信钱包`…）。
 * 不映射的话，草稿的账户字段是一串没人认得的原文，资产也对不上号。
 *
 * 三条原则：
 * 1. **用户账户优先**：用户建过 `招商银行` 就直接用，不强行归到 `信用卡`。
 * 2. **不写清单外的名字**：映射不到就返回 null，宁可留空让用户选，也不编一个账本里没有的账户。
 * 3. **保守胜过聪明**：`银行卡(1234)` 看不出是借记卡还是信用卡，就不猜。
 */
object SpecAccountMapper {

    /** 尾号：`招商银行(1234)` / `招商银行（1234）` / `银行卡尾号1234`。 */
    private val TAIL = Regex("[（(]\\s*(\\d{2,6})\\s*[)）]\\s*$")
    private val TAIL_SUFFIX = Regex("尾号\\s*(\\d{2,6})\\s*$")

    /**
     * 具体产品 → 账本默认账户。**顺序即优先级**：越靠前的越具体，先命中先返回。
     *
     * `余额宝` 必须排在 `支付宝` 前面，否则「支付宝余额宝收益」会被归成普通支付宝余额；
     * `花呗` 同理——它是一笔负债，不是余额。
     */
    private val PRODUCTS: List<Pair<List<String>, List<String>>> = listOf(
        listOf("余额宝", "余利宝") to listOf("余额宝"),
        listOf("花呗") to listOf("花呗"),
        listOf("白条") to listOf("白条"),
        listOf("零钱通") to listOf("微信零钱通"),
        listOf("微信零钱", "微信支付", "微信钱包") to listOf("微信钱包", "微信零钱通"),
        listOf("数字人民币", "数币钱包") to listOf("数字人民币"),
        listOf("现金") to listOf("现金"),
        listOf("信用卡", "贷记卡") to listOf("信用卡"),
        listOf("储蓄卡", "借记卡") to listOf("储蓄卡"),
    )

    /** 银行名 → 账户名。用户建了同名账户时直接命中；否则退到信用卡/储蓄卡。 */
    private val BANKS = listOf(
        "工商银行", "农业银行", "中国银行", "建设银行", "交通银行", "招商银行", "邮储银行",
        "邮政储蓄", "浦发银行", "中信银行", "光大银行", "民生银行", "兴业银行", "华夏银行",
        "广发银行", "平安银行", "北京银行", "江苏银行", "上海银行", "宁波银行", "南京银行",
        "杭州银行", "重庆银行", "潍坊银行", "泉州银行", "华兴银行", "农商银行", "农信", "信用联社",
    )

    /** 通用支付渠道兜底，放在最后。 */
    private val CHANNELS = listOf(
        listOf("支付宝", "余额", "小荷包", "蚂蚁财富", "网商银行", "亲情卡") to listOf("支付宝"),
        listOf("美团月付") to listOf("美团月付"),
        listOf("京东") to listOf("白条"),
        listOf("OPPO", "一加钱包", "小米智能卡") to emptyList(),
    )

    /** 去掉尾号，只留主体。`招商银行信用卡(1234)` → `招商银行信用卡`。 */
    fun normalize(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return text.replace(TAIL, "").replace(TAIL_SUFFIX, "").trim()
    }

    /** 尾号（4 位为主），拿不到就返回 null。 */
    fun tail(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        return (TAIL.find(text)?.groupValues?.getOrNull(1)
            ?: TAIL_SUFFIX.find(text)?.groupValues?.getOrNull(1))?.takeIf { it.isNotBlank() }
    }

    /**
     * 原始账户名 → 账本账户名。
     *
     * @param raw 规则给的原文，例如 `招商银行信用卡(1234)`
     * @param accounts 用户账本里可用的账户名（未归档）
     * @param fallback 规格自带的资产名（assets.json 的 5 条），仅在用户没有同名账户时兜底
     */
    fun resolve(raw: String?, accounts: List<String>, fallback: List<String> = emptyList()): String? {
        val base = normalize(raw)
        if (base.isEmpty()) return null
        // 两轮：先只用用户账本里真实存在的账户，全都对不上才退到规格资产名。
        // 这样「工商银行储蓄卡」在用户只有一张通用「储蓄卡」时归到储蓄卡，
        // 而不是凭空映射到账本里并不存在的「工商银行」。
        match(base, accounts.map(String::trim).filter(String::isNotEmpty).distinct())?.let { return it }
        return match(base, fallback.map(String::trim).filter(String::isNotEmpty).distinct())
    }

    private fun match(base: String, pool: List<String>): String? {
        if (pool.isEmpty()) return null

        // 1) 完全同名（含用户自己就建了 `支付宝余额` 这种名字的情况）
        pool.firstOrNull { it == base }?.let { return it }

        // 2) 先按语义定出「该记到哪一类」，再看账本里有没有这个账户。
        //
        //    不能一上来做子串包含：`支付宝花呗` 同时包含 `支付宝` 和 `花呗`，
        //    按长度取最长会归成支付宝余额，把一笔负债记成余额。
        val hint = productTarget(base)
        if (hint != null) pool.firstOrNull { it == hint }?.let { return it }

        // 3) 银行名：用户建了 `招商银行` 就用它（比笼统的 `信用卡` 更准确）
        bankOf(base)?.let { bank ->
            pool.firstOrNull { it == bank }?.let { return it }
            pool.firstOrNull { it.contains(bank) }?.let { return it }
        }

        // 4) 银行名对不上具体账户时，按卡种退到 `信用卡` / `储蓄卡`
        if (hint != null) {
            val cardKind = when {
                base.contains("信用卡") || base.contains("贷记卡") -> "信用卡"
                base.contains("储蓄卡") || base.contains("借记卡") -> "储蓄卡"
                else -> null
            }
            if (cardKind != null) pool.firstOrNull { it == cardKind }?.let { return it }
        }

        // 5) 账户名被原始名包含：`招商银行储蓄卡` 命中账户 `招商银行`
        pool.filter { base.contains(it) }.maxByOrNull { it.length }?.let { return it }

        // 6) 原始名被账户名包含：账户 `招商银行信用卡` vs 原始 `信用卡`
        pool.filter { it.contains(base) }.minByOrNull { it.length }?.let { return it }

        // 7) 认不出来：留空，让用户在确认卡上自己选，不编造。
        return null
    }

    /** 语义提示：这条原文最像哪一类账户。顺序即优先级。 */
    private fun productTarget(base: String): String? {
        PRODUCTS.forEach { (keys, targets) ->
            if (keys.any { base.contains(it) }) targets.firstOrNull()?.let { return it }
        }
        CHANNELS.forEach { (keys, targets) ->
            if (keys.any { base.contains(it) }) targets.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun bankOf(base: String): String? = BANKS.firstOrNull { base.contains(it) }
}
