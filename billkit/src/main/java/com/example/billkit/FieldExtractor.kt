package com.example.billkit

/**
 * 「标签 → 值」邻接抽取。
 *
 * 移动端账单页几乎都是 一行标签 + 一行值（或者 `支付方式：零钱` 同一行），
 * 所以不需要语义模型，靠标签词表 + 相邻行即可覆盖绝大多数页面。
 *
 * 两个实测坑：
 * - `收款方备注` 不能被 `收款方` 规则吃掉（前缀后面必须紧跟分隔符才算"标签：值"）；
 * - 商户名经常只出现在备注里（微信：`扫二维码付款-给早餐店`），需要兜底。
 */
data class Fields(
    val merchant: String? = null,
    val method: String? = null,
    val orderId: String? = null,
    val counterParty: String? = null,
)

object FieldExtractor {

    private val merchantLabels = listOf(
        "商户全称", "商户名称", "商户", "商家名称", "店铺名称", "收款方名称", "收款方全称", "收款方",
    )
    private val methodLabels = listOf(
        "支付方式", "付款方式", "支付工具", "资金渠道", "扣款方式", "到账方式",
    )
    private val orderLabels = listOf(
        "交易单号", "商户单号", "订单编号", "订单号", "转账单号", "交易流水号", "流水号", "凭证号",
    )
    private val counterPartyLabels = listOf(
        "对方账户", "对方", "付款方", "转入账户", "转出账户",
    )

    private val punctuation = charArrayOf('：', ':', ' ', '\u00A0', '　', '>', '》')

    /** 备注里的收款方：`扫二维码付款-给早餐店`、`转账给张三` */
    private val payeeInRemark = Regex("""(?:给|向)\s*([^\s\-—·:：()（）]{2,20})""")

    fun extract(items: List<String>): Fields = Fields(
        merchant = valueOf(items, merchantLabels),
        method = valueOf(items, methodLabels),
        orderId = valueOf(items, orderLabels),
        counterParty = valueOf(items, counterPartyLabels),
    )

    /**
     * 商户兜底：从"付款/转账"类备注里抠出收款方。
     * 只在页面上没有正规商户字段时使用。
     */
    fun merchantFromRemark(items: List<String>): String? {
        for (line in items) {
            if (!line.contains("付款") && !line.contains("转账") && !line.contains("收款")) continue
            val value = payeeInRemark.find(line)?.groupValues?.get(1)?.trim().orEmpty()
            if (value.length in 2..20 && !value.contains("成功")) return value
        }
        return null
    }

    /** 一整行就是"这笔交易的钱"：`¥25.80` / `-3.50` / `支出19元` / `收入 1,200.00` */
    private val amountLineRe = Regex(
        """^\s*(?:支出|收入|付款|收款|退款|转出|转入)?\s*([+＋\-－−])?\s*[¥￥]?\s*""" +
            """\d{1,7}(?:,\d{3})*(?:\.\d{1,2})?\s*元?\s*$"""
    )

    /** 这些行不可能是商户名（详情页的按钮/标题/状态/标签） */
    private val nonNameLines = listOf(
        "返回", "全部账单", "账单详情", "收支详情", "交易详情", "消费详情", "账单管理",
        "交易成功", "支付成功", "付款成功", "转账成功", "已存入", "完成", "关闭", "更多",
        "客服", "联系", "分享", "收藏", "删除", "复制", "投诉", "疑问", "申请", "查看",
        "首页", "主页", "详情", "成功", "状态", "时间", "方式", "单号", "金额", "备注",
        "分类", "账户", "余额", "总额", "说明", "订单", "账单服务", "本服务由",
    )

    /**
     * 商户兜底 #2：支付宝这类页面把商户名直接放在大金额**上面一行**，既没有
     * 「商户名称：」标签，也不在备注里（真机：`账单详情 / 东富路家味餐厅 / 返回 /
     * 全部账单 / 支出19元`）。取金额行的前一行，并跳过按钮/标题等非名称行。
     */
    fun merchantAboveAmount(items: List<String>): String? {
        for (index in items.indices) {
            val line = items[index].trim()
            if (!amountLineRe.matches(line)) continue
            var cursor = index - 1
            while (cursor >= 0) {
                val candidate = items[cursor].trim()
                cursor--
                if (candidate.length !in 2..24) continue
                if (candidate.any { it.isDigit() }) continue
                if (nonNameLines.any { candidate.contains(it) }) continue
                if (candidate.startsWith("¥") || candidate.startsWith("￥")) continue
                return candidate
            }
        }
        return null
    }

    private fun valueOf(items: List<String>, labels: List<String>): String? {
        for (index in items.indices) {
            val line = items[index].trim()
            for (label in labels) {
                if (!line.startsWith(label)) continue

                // 同一行：`支付方式：零钱`（标签后必须紧跟分隔符，否则是"收款方备注"这类词）
                val rest = line.substring(label.length)
                if (rest.isNotEmpty()) {
                    if (rest[0] !in punctuation) continue
                    val value = rest.trimStart(*punctuation).trim()
                    if (value.isNotEmpty()) return value.take(40)
                }

                // 换行：下一行就是值（但下一行不能又是个标签）
                val next = items.getOrNull(index + 1)?.trim().orEmpty()
                if (next.isEmpty() || next.length > 40) continue
                if (labels.any { next.startsWith(it) }) continue
                return next
            }
        }
        return null
    }

    /** 只保留单号尾部，既够去重又不必存全量标识 */
    fun orderIdTail(orderId: String?): String? {
        val digits = orderId?.filter { it.isDigit() } ?: return null
        if (digits.length < 6) return digits.ifEmpty { null }
        return digits.takeLast(6)
    }
}
