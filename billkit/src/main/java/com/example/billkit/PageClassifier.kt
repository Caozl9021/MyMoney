package com.example.billkit

/**
 * 页面分类：回答"这一屏是不是一个可记账的账单详情页，属于谁家的哪种页面"。
 *
 * 规则取向：
 * - 每个 App 必须命中「强信号」（成功态文案 / 页面特有字段）才算命中；
 * - 命中后再过一遍「反向信号」（列表页、输入页、二维码页、误截的系统界面）；
 * - 任何不确定 → null，调用方直接放弃（漏记可以，错记不行）。
 *
 * 新增页面：加一个 PageKind + 一个分支 + 一条测试样本（见 PageClassifierTest）。
 */
enum class PageKind {
    WECHAT_BILL,        // 微信账单详情 / 支付凭证
    WECHAT_PAY,         // 微信支付成功页（付款码、群收款）
    WECHAT_TRANSFER,    // 微信转账成功 / 转账详情
    WECHAT_RECEIVE,     // 微信收款详情（收入）
    WECHAT_LQT,         // 微信零钱通转入/转出/收益
    WECHAT_RED_PACKET,  // 微信红包（收入）
    ALIPAY_BILL,        // 支付宝账单 / 收支详情
    ALIPAY_PAY,         // 支付宝支付结果页
    ALIPAY_TRANSFER,    // 支付宝转账成功
    UNIONPAY_BILL,      // 云闪付账单详情
    UNIONPAY_PAY,       // 云闪付支付页
    JD_BILL,            // 京东 / 京东金融 / 京东外卖账单详情
    JD_PAY,             // 京东收银台完成页
    MEITUAN_BILL,       // 美团 / 点评 / 美团外卖 订单或账单详情
    TAOBAO_PAY,         // 淘宝 / 天猫支付成功
    PDD_BILL,           // 拼多多账单/订单详情
    DOUYIN_BILL,        // 抖音支付 / 账单 / 月付还款
    DCEP_BILL,          // 数字人民币
    GENERIC_BILL,       // 白名单里的陌生包（分身/宿主包/厂商定制）按内容兜底识别出的账单详情
}

data class PageMatch(val kind: PageKind, val strategy: String)

object Packages {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val MYBANK = "com.mybank.android.phone"
    const val UNIONPAY = "com.unionpay"
    const val JD = "com.jingdong.app.mall"
    const val JD_FINANCE = "com.jd.jrapp"
    const val JD_WAIMAI = "com.jd.waimai"
    const val MEITUAN = "com.sankuai.meituan"
    const val MEITUAN_WAIMAI = "com.sankuai.meituan.takeoutnew"
    const val MEITUAN_YOUXUAN = "com.sankuai.youxuan"
    const val DIANPING = "com.dianping.v1"
    const val PDD = "com.xunmeng.pinduoduo"
    const val TAOBAO = "com.taobao.taobao"
    const val TMALL = "com.tmall.wireless"
    const val DCEP = "cn.gov.pbc.dcep"
    const val DOUYIN = "com.ss.android.ugc.aweme"
    const val DOUYIN_LITE = "com.ss.android.ugc.aweme.lite"

    val jdGroup = setOf(JD, JD_FINANCE, JD_WAIMAI)
    val meituanGroup = setOf(MEITUAN, MEITUAN_WAIMAI, MEITUAN_YOUXUAN, DIANPING)
    val taobaoGroup = setOf(TAOBAO, TMALL)
    val douyinGroup = setOf(DOUYIN, DOUYIN_LITE)

    val defaultWhitelist = setOf(
        WECHAT, ALIPAY, MYBANK, UNIONPAY,
    ) + jdGroup + meituanGroup + taobaoGroup + douyinGroup + setOf(PDD, DCEP)

    fun isSupported(pkg: String): Boolean = pkg in defaultWhitelist
}

class PageClassifier {

    /** 列表页强信号：只有列表/筛选界面才会出现，命中就该跳过 */
    private val strongListSignals = listOf(
        "搜索我的订单", "查找交易", "没有更多交易了",
        "全部订单", "待付款", "待收货", "待评价", "筛选",
        "进入钱包", "收支统计",
    )

    /**
     * 列表页弱信号：**详情页里也常有**（支付宝新版详情页顶部就有「全部账单」返回入口，
     * 账单详情页还常有「交易记录」）。只有在没有详情页字段时才当作列表页，
     * 否则真机页面会被误判成列表页 → `skip:not-a-bill-page`。
     */
    private val weakListSignals = listOf("全部账单", "交易记录", "账单列表", "收支明细")

    /** 详情页字段：出现任意一个，说明这一屏是"某一笔账单"，不是流水列表 */
    private val detailSignals = listOf(
        "当前状态", "支付时间", "付款时间", "交易时间", "转账时间", "创建时间", "扣款时间",
        "交易单号", "转账单号", "商户单号", "订单号", "订单编号", "交易号", "流水号",
        "商户全称", "商户名称", "商户订单号",
        "支付方式", "付款方式", "交易方式", "扣款方式",
        "账单服务", "本服务由财付通提供", "对此订单有疑问", "对订单有疑惑",
    )

    /** 非账单页（下单页、二维码页），以及 OCR 误截到的系统界面 */
    private val nonBillSignals = listOf(
        "立即支付", "提交订单", "确认付款", "向商家付钱", "付款码数字",
        "正在加载", "控制中心", "WLAN", "飞行模式", "深色模式",
    )

    private val dateRowRe = Regex("""^\d{1,2}月\d{1,2}日\s*\d{1,2}:\d{2}$""")

    fun classify(snapshot: UiSnapshot): PageMatch? {
        val items = PaymentResultParser.normalize(snapshot.items)
        if (items.size < 3) return null
        if (looksLikeListPage(items) || looksLikeNonBillPage(items)) return null

        ExternalPageRules.match(snapshot.copy(items = items))?.let { return it }
        val pkg = snapshot.packageName
        return when {
            pkg == Packages.WECHAT -> wechat(snapshot.activityClass, items)
            pkg == Packages.ALIPAY || pkg == Packages.MYBANK -> alipay(snapshot.activityClass, items)
            pkg == Packages.UNIONPAY -> unionpay(items)
            pkg in Packages.jdGroup -> jd(items)
            pkg in Packages.meituanGroup -> meituan(items)
            pkg in Packages.taobaoGroup -> taobao(items)
            pkg == Packages.PDD -> pdd(items)
            pkg in Packages.douyinGroup -> douyin(items)
            pkg == Packages.DCEP -> dcep(items)
            // 用户自己加进白名单的 App（支付宝分身 / 厂商定制包名 / 新版改包名）
            else -> generic(items)
        }
    }

    // ---------- 微信 ----------

    private fun wechat(activity: String?, items: List<String>): PageMatch? {
        // 零钱通：转入/转出/收益，字段固定
        if (hasAny(items, "转入方式", "转出方式", "收益发放") ||
            (has(items, "零钱通") && hasAny(items, "转入", "转出", "收益"))
        ) {
            return PageMatch(PageKind.WECHAT_LQT, "微信零钱通")
        }

        // 红包：Activity 白名单 或 文案特征
        if (activity?.contains("luckymoney", true) == true ||
            (has(items, "红包") && hasAny(items, "已存入零钱", "个红包共", "的红包", "已领取"))
        ) {
            return PageMatch(PageKind.WECHAT_RED_PACKET, "微信红包")
        }

        // 收款（收入方向）：先于账单判定，避免被当成普通支出
        if (hasAny(items, "收款详情", "你已收款", "已收钱", "已存入经营账户")) {
            return PageMatch(PageKind.WECHAT_RECEIVE, "微信收款详情")
        }

        // 转账成功页：转账成功 + 金额
        if (has(items, "转账成功") || (hasAny(items, "转账金额", "确认收款") && has(items, "转账"))) {
            return PageMatch(PageKind.WECHAT_TRANSFER, "微信转账成功")
        }

        // 账单详情：必须同时有「单号类字段」和「状态/财付通/时间」
        val hasOrder = hasAny(items, "交易单号", "商户单号", "转账单号", "提现单号", "单号")
        if (hasOrder && hasAny(items, "当前状态", "本服务由财付通提供", "支付时间", "转账时间", "账单服务")) {
            return PageMatch(PageKind.WECHAT_BILL, "微信账单详情")
        }

        // 极简付款结果没有支付方式/单号：仅允许微信独立成功标题、完成动作及唯一币种金额。
        if (isMinimalWechatPayment(items)) {
            return PageMatch(PageKind.WECHAT_PAY, "微信极简支付成功页")
        }

        // 支付成功页
        if (AmountParser.status(items) == AmountParser.Status.SUCCESS &&
            hasAny(items, "支付方式", "付款方式", "支付工具", "资金渠道")
        ) {
            return PageMatch(PageKind.WECHAT_PAY, "微信支付成功页")
        }
        return null
    }

    /**
     * Deliberately narrow: screenshot transcription is not proof of accessibility node contents.
     * Do not infer an expense from arbitrary success text or an ambiguous page full of prices.
     * A separate currency node is allowed, but split decimal digits and extra numeric content are not.
     */
    private fun isMinimalWechatPayment(items: List<String>): Boolean {
        val rows = items.map(String::trim).filter(String::isNotEmpty)
        if (rows.none { it == "支付成功" || it == "付款成功" } || "完成" !in rows) return false
        if (AmountParser.status(rows) != AmountParser.Status.SUCCESS) return false
        val directionSignals = listOf("转账", "收款成功", "收款详情", "已收钱", "已收款", "你已收款",
            "收入", "入账", "到账", "已存入", "退款", "红包", "零钱通")
        if (rows.any { row -> directionSignals.any(row::contains) }) return false

        val number = "(?:0|[1-9]\\d{0,6}|[1-9]\\d{0,2}(?:,\\d{3}){1,2})(?:\\.\\d{1,2})?"
        val currencyAmount = Regex("^[¥￥]\\s*($number)$")
        val bareAmount = Regex("^($number)$")
        var cents: Long? = null
        var index = 0
        while (index < rows.size) {
            val row = rows[index]
            val combined = currencyAmount.matchEntire(row)
            val split = if (row == "¥" || row == "￥") bareAmount.matchEntire(rows.getOrNull(index + 1).orEmpty()) else null
            val raw = combined?.groupValues?.get(1) ?: split?.groupValues?.get(1)
            if (raw != null) {
                if (cents != null) return false
                cents = AmountParser.toCents(raw)?.takeIf { it > 0 } ?: return false
                index += if (split != null) 2 else 1
            } else {
                // Without detail labels there is no safe way to choose among other prices/numbers.
                if (row.any(Char::isDigit) || row.contains('¥') || row.contains('￥')) return false
                index++
            }
        }
        val amount = cents ?: return false
        val parsed = AmountParser.findAmount(rows) ?: return false
        return parsed.cents == amount && parsed.sign == 0
    }

    // ---------- 支付宝 / 网商银行 ----------

    private fun alipay(activity: String?, items: List<String>): PageMatch? {
        if (activity?.contains("MspContainerActivity") == true &&
            hasAny(items, "交易方式", "付款方式", "支付成功")
        ) {
            return PageMatch(PageKind.ALIPAY_PAY, "支付宝支付结果页")
        }
        if (has(items, "转账成功")) {
            return PageMatch(PageKind.ALIPAY_TRANSFER, "支付宝转账")
        }
        if (hasAny(items, "账单详情", "收支详情", "余额明细详情", "网商银行·账单详情", "转入详情", "转出详情")) {
            return PageMatch(PageKind.ALIPAY_BILL, "支付宝账单详情")
        }
        if (has(items, "收款详情") && has(items, "当前状态")) {
            return PageMatch(PageKind.ALIPAY_BILL, "支付宝收款详情")
        }
        if (has(items, "账单管理") && has(items, "对此订单有疑问")) {
            return PageMatch(PageKind.ALIPAY_BILL, "支付宝账单管理")
        }
        if (PaymentResultParser.hasResultTitle(items) && PaymentResultParser.isTrustedResult(items)) {
            return PageMatch(PageKind.ALIPAY_PAY, "支付宝结构化支付结果")
        }
        if (AmountParser.status(items) == AmountParser.Status.SUCCESS &&
            hasAny(items, "交易方式", "付款方式", "支付方式")
        ) {
            return PageMatch(PageKind.ALIPAY_PAY, "支付宝支付成功页")
        }

        // 兜底：新版详情页把「账单详情」标题换成了各种叫法（甚至只有返回箭头），
        // 但「成功态 + 能取到金额 + 有订单号/时间类字段」这套组合一直在。
        // 没有这一条，文案一改版就会退化成 skip:not-a-bill-page（用户看到的就是
        //「明明识别到文字了，却什么都不弹」）。
        val success = AmountParser.status(items) == AmountParser.Status.SUCCESS
        val hasOrderish = hasAny(
            items,
            "订单号", "交易号", "商家订单号", "流水号", "创建时间",
            "付款时间", "交易时间", "支付时间", "账单详情", "收支详情",
        )
        if (success && hasOrderish && AmountParser.findAmount(items) != null) {
            return PageMatch(PageKind.ALIPAY_BILL, "支付宝账单兜底")
        }
        return null
    }

    // ---------- 云闪付 ----------

    private fun unionpay(items: List<String>): PageMatch? {
        if (has(items, "支付成功") && has(items, "付款方式")) {
            return PageMatch(PageKind.UNIONPAY_PAY, "云闪付支付页")
        }
        if (has(items, "订单信息") && has(items, "付款方式") && contains(items, "¥")) {
            return PageMatch(PageKind.UNIONPAY_PAY, "云闪付订单信息")
        }
        if (hasAny(items, "交易时间", "商户名称") && hasAny(items, "交易金额", "支出金额")) {
            return PageMatch(PageKind.UNIONPAY_BILL, "云闪付账单详情")
        }
        return null
    }

    // ---------- 京东 ----------

    private fun jd(items: List<String>): PageMatch? {
        if (hasAny(items, "账单详情", "交易详情") ||
            (has(items, "服务详情") && has(items, "交易成功"))
        ) {
            return PageMatch(PageKind.JD_BILL, "京东账单详情")
        }
        if (has(items, "查看订单") &&
            hasAny(items, "支付成功", "下单成功") &&
            !has(items, "订单编号")
        ) {
            return PageMatch(PageKind.JD_PAY, "京东收银台完成页")
        }
        if (hasAny(items, "实付款", "应付款", "实付金额") &&
            hasAny(items, "订单编号", "创建时间", "支付时间", "订单时间")
        ) {
            return PageMatch(PageKind.JD_BILL, "京东订单详情")
        }
        if (hasAny(items, "交易金额", "交易说明") && has(items, "交易成功")) {
            return PageMatch(PageKind.JD_BILL, "京东金融交易详情")
        }
        return null
    }

    // ---------- 美团系 ----------

    private fun meituan(items: List<String>): PageMatch? {
        if (has(items, "钱包小秘书")) return null // 钱包首页卡片，不是账单
        if (hasAny(items, "账单详情", "交易详情", "订单详情")) {
            return PageMatch(PageKind.MEITUAN_BILL, "美团账单详情")
        }
        if (has(items, "交易类型") && contains(items, "单号") && contains(items, "时间")) {
            return PageMatch(PageKind.MEITUAN_BILL, "美团交易详情")
        }
        if (hasAny(items, "交易成功 ¥", "支付成功 ¥")) {
            return PageMatch(PageKind.MEITUAN_BILL, "美团支付完成")
        }
        if (hasAny(items, "实付", "实付款", "合计") &&
            has(items, "支付方式") && has(items, "交易成功")
        ) {
            return PageMatch(PageKind.MEITUAN_BILL, "美团订单详情")
        }
        if (has(items, "对该账单发起投诉") || has(items, "查看原订单")) {
            return PageMatch(PageKind.MEITUAN_BILL, "美团投诉/原订单入口")
        }
        return null
    }

    // ---------- 淘宝 / 天猫 ----------

    private fun taobao(items: List<String>): PageMatch? {
        val successLike = has(items, "支付成功") &&
            (has(items, "查看订单") || has(items, "返回首页"))
        if (successLike || has(items, "正在校验") || has(items, "请输入支付密码") || has(items, "人脸检测中")) {
            return PageMatch(PageKind.TAOBAO_PAY, "淘宝支付结果页")
        }
        if (has(items, "账号") && hasAny(items, "交易方式", "付款方式") && has(items, "确认付款")) {
            return PageMatch(PageKind.TAOBAO_PAY, "淘宝确认付款页")
        }
        return null
    }

    // ---------- 拼多多 ----------

    private fun pdd(items: List<String>): PageMatch? {
        if (hasAny(items, "账单详情", "充值成功", "提现发起成功") && !has(items, "交易记录")) {
            return PageMatch(PageKind.PDD_BILL, "拼多多账单详情")
        }
        if (has(items, "订单详情") && hasAny(items, "实付款", "实付") && has(items, "拼单成功")) {
            return PageMatch(PageKind.PDD_BILL, "拼多多订单详情")
        }
        return null
    }

    // ---------- 抖音 ----------

    private fun douyin(items: List<String>): PageMatch? {
        if (hasAny(items, "消费详情", "赔付详情", "退款详情", "账单详情", "充值详情", "还款详情")) {
            return PageMatch(PageKind.DOUYIN_BILL, "抖音账单详情")
        }
        if (items.size >= 3 && contains(items, "¥") &&
            hasAny(items, "支付成功", "购买成功") && hasAny(items, "完成", "返回商户", "返回")
        ) {
            return PageMatch(PageKind.DOUYIN_BILL, "抖音支付页")
        }
        return null
    }

    // ---------- 数字人民币 ----------

    private fun dcep(items: List<String>): PageMatch? {
        if (has(items, "交易详情") || hasAny(items, "转钱成功", "存银行成功", "充钱包成功")) {
            return PageMatch(PageKind.DCEP_BILL, "数字人民币")
        }
        return null
    }

    // ---------- 通用（白名单里但不在已知包名单中的 App） ----------

    /**
     * 兜底规则：用户手动勾选了某个不在 [Packages] 里的包（支付宝分身、厂商定制包名、
     * 改过 applicationId 的客户端……）。这类页面没有稳定的包名规则，只能按内容判断：
     * **成功态 + 详情页字段 + 能取到金额**，三条同时成立才算账单页。
     *
     * 之所以敢在未知包上这么判：能走到这里的包一定是用户明确加进白名单的，
     * 误判的代价只是多弹一次可以"忽略"的确认卡。
     */
    private fun generic(items: List<String>): PageMatch? {
        if (AmountParser.status(items) != AmountParser.Status.SUCCESS) return null
        val detail = hasAny(items, "账单详情", "收支详情", "交易详情", "消费详情", "支付时间",
            "付款时间", "交易时间", "创建时间", "支付方式", "付款方式", "交易方式")
        val orderish = hasAny(items, "订单号", "订单编号", "交易号", "商户订单号", "商家订单号",
            "流水号", "支付时间", "付款时间", "交易时间")
        if (!detail || !orderish) return null
        if (AmountParser.findAmount(items) == null) return null
        // 别冒充 ALIPAY_BILL：结论行、DebugBus、以后的统计都靠 kind 区分来源，
        // 一个微信分身包被标成"支付宝账单"只会让排查更难。
        return PageMatch(PageKind.GENERIC_BILL, "通用账单详情")
    }

    // ---------- 反向信号 ----------

    private fun looksLikeListPage(items: List<String>): Boolean {
        if (strongListSignals.any { has(items, it) }) return true

        // 详情页字段一出现，说明是"某一笔"，弱信号（返回入口）不能当列表页证据
        val isDetail = detailSignals.any { has(items, it) }
        if (isDetail) return false

        if (weakListSignals.any { has(items, it) }) return true
        // 一屏 ≥3 行「M月d日 H:MM」= 流水列表
        return items.count { dateRowRe.matches(it.trim()) } >= 3
    }

    private fun looksLikeNonBillPage(items: List<String>): Boolean =
        nonBillSignals.any { has(items, it) }

    // ---------- 小工具 ----------

    private fun has(items: List<String>, word: String): Boolean =
        items.any { it.contains(word) }

    private fun hasAny(items: List<String>, vararg words: String): Boolean =
        words.any { has(items, it) }

    private fun contains(items: List<String>, word: String): Boolean =
        items.any { it.contains(word) }
}
