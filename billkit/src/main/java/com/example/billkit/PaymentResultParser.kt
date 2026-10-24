package com.example.billkit

/**
 * 支付结果页的主金额提取。
 *
 * 背景：支付宝/微信的支付成功页会把「实付」和商户原额、优惠、奖励、广告放在同一屏。
 * 通用的 [AmountParser.findAmount] 会优先标签、再退化为“全屏唯一金额”，在结果页上
 * 既可能选中商户原价，也可能选中广告里的“实付款 27 元”。这里给结果页一条更窄的路径：
 *
 * - 只认独立成功标题（`支付成功` / `付款成功`）之后的金额；
 * - 金额必须是独立的币种金额节点（`¥16.38`），或 `¥` 与数字分属相邻两个节点；
 * - 成功标题与主金额之间只允许重复的成功标题；商户/原价等抬头不能出现在主金额之前，
 *   否则无法区分“商户原额”和真实主额；
 * - 主金额之后出现的另一个独立金额、带符号金额、或同一节点里的多个金额都视为歧义；
 *   商户原额、订单金额、优惠、奖励、广告等文本只是边界，不参与选额；
 * - 主金额候选必须唯一；多个候选、缺边界/锚点、金额格式异常都返回 null（拒绝）。
 *
 * 返回 null 表示“这页不能证明主金额”，调用方必须跳过，**不能**回退到通用 findAmount。
 */
object PaymentResultParser {

    data class MainAmount(val cents: Long, val source: String)

    private const val SOURCE = "payment-result-main"

    private val resultTitles = setOf("支付成功", "付款成功")

    private val methodLabels = listOf(
        "支付方式", "付款方式", "交易方式", "支付工具", "资金渠道", "扣款方式",
    )

    /** 主额之后的这些行是商户/订单/优惠/奖励/广告等附属信息，不参与选额。 */
    private val attachmentHints = listOf(
        "商户", "商家", "收款人", "收款方", "对方", "转账给", "付款给",
        "订单", "实付", "应付", "原价", "原金额", "优惠", "立减", "折扣",
        "奖励", "红包", "能量", "积分", "花籽", "广告", "推荐", "券",
    )

    private const val NUMBER = "(?:0|[1-9]\\d{0,6}|[1-9]\\d{0,2}(?:,\\d{3}){1,2})(?:\\.\\d{1,2})?"
    private val currencyOnlyRe = Regex("^[¥￥]\\s*($NUMBER)$")
    private val bareAmountRe = Regex("^($NUMBER)$")
    private val signedCurrencyRe = Regex("[+＋\\-－−]\\s*[¥￥]")

    /** 页面是否有独立的成功结果标题；供分类器在详情兜底前做窄判断。 */
    fun hasResultTitle(items: List<String>): Boolean =
        normalize(items).any { it in resultTitles }

    /** 可信结果上下文：独立成功标题 + 完成/支付方式锚点。 */
    fun isTrustedResult(items: List<String>): Boolean {
        val rows = normalize(items)
        val titleIndex = rows.indexOfFirst { it in resultTitles }
        if (titleIndex < 0) return false
        return rows.drop(titleIndex + 1).any(::isAnchor)
    }

    /**
     * 营销/帮助文案里的方向词不是真实交易方向。命中这些混合结果页时调用方应保守跳过，
     * 而不是把它们当成退款、收入或转账。
     */
    fun hasAmbiguousDirectionPromo(items: List<String>): Boolean = normalize(items).any { row ->
        (row.contains("退款成功") && (row.contains("帮助") || row.contains("怎么办"))) ||
            (row.contains("推荐") && row.contains("收益")) ||
            (row.contains("转账") && row.contains("优惠活动"))
    }

    /** 按阅读顺序展开容器里的换行，供结果页内的邻接判断使用。 */
    fun normalize(items: List<String>): List<String> = items
        .flatMap { it.split(Regex("\\r\\n|\\n|\\r")) }
        .map(String::trim)
        .filter(String::isNotEmpty)

    /**
     * @return 唯一可信主金额；无候选、候选不唯一或缺少可信锚点时返回 null。
     */
    fun findMainAmount(items: List<String>): MainAmount? {
        val rows = normalize(items)
        val titleIndex = rows.indexOfFirst { it in resultTitles }
        if (titleIndex < 0) return null

        var candidate: MainAmount? = null
        var index = titleIndex + 1
        while (index < rows.size) {
            val row = rows[index]

            if (isAnchor(row)) break

            if (candidate == null) {
                if (row in resultTitles) {
                    index++
                    continue
                }
                if (row == "¥" || row == "￥") {
                    val raw = bareAmountRe.matchEntire(rows.getOrNull(index + 1).orEmpty())
                        ?.groupValues?.get(1) ?: return null
                    val cents = centsOf(raw) ?: return null
                    candidate = MainAmount(cents, SOURCE)
                    index += 2
                    continue
                }

                val currency = currencyOnlyRe.matchEntire(row)
                if (currency != null) {
                    val cents = centsOf(currency.groupValues[1]) ?: return null
                    candidate = MainAmount(cents, SOURCE)
                    index++
                    continue
                }

                // 主额之前出现商户/原价/订单/奖励/广告或任何非金额内容，都无法证明下一个
                // 金额不是商户原额，保守拒绝。
                return null
            }

            // 主额之后的另一个独立/带符号/同节点多金额是歧义，拒绝。
            if (isAnotherMainCandidate(rows, index)) return null
            // 第一段其他内容只是附属信息/边界，保留已经找到的主额并停止向后找。
            break
        }

        val amount = candidate ?: return null
        // 金额之后必须有“完成”或支付方式，证明这是结果页而不是只有金额的普通内容。
        val anchored = rows.drop(titleIndex + 1).any(::isAnchor)
        return amount.takeIf { anchored }
    }

    /**
     * 主额之后是否又出现一个不能当附属信息处理的金额候选。
     *
     * 单行 `示例餐厅 ¥17.00`、`订单金额 ¥17.00` 这类带商户/订单/优惠说明的文本是附件；
     * 纯金额、带符号金额、同节点两个金额、未知的单币种行都按歧义拒绝。
     */
    private fun isAnotherMainCandidate(rows: List<String>, index: Int): Boolean {
        val row = rows[index]
        if (row == "¥" || row == "￥") {
            return bareAmountRe.matches(rows.getOrNull(index + 1).orEmpty())
        }
        if (bareAmountRe.matches(row)) return true
        if (currencyOnlyRe.matches(row)) return true

        if (row.contains('¥') || row.contains('￥')) {
            if (signedCurrencyRe.containsMatchIn(row)) return true
            val symbols = row.count { it == '¥' || it == '￥' }
            if (symbols >= 2) return true
            // 带明确商户/订单/优惠等说明的单币种行是附属信息。
            val merchantPrice = Regex("^[^¥￥\\d+＋\\-－−]{2,40}\\s*[¥￥]\\s*$NUMBER$")
            return attachmentHints.none(row::contains) && !merchantPrice.matches(row)
        }
        return false
    }

    private fun isAnchor(row: String): Boolean =
        row == "完成" || methodLabels.any { row.startsWith(it) }

    private fun centsOf(raw: String): Long? =
        AmountParser.toCents(raw)?.takeIf { it > 0 }
}
