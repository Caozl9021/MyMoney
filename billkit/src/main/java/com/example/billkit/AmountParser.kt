package com.example.billkit

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 金额 / 时间 / 状态 的规范化解析。
 *
 * 纯 Kotlin（不依赖 Android），可在 JVM 单测里用真实页面文本跑回归。
 * 设计取向：**宁可返回 null 让流程跳过，也不要猜一个金额出来。**
 *
 * 实测坑（都是真机页面）：
 * - 微信「交易详情」的金额是 `-3.50`：带负号、**没有 ¥**；
 * - 京东/美团常把 `实付款` 和金额拆成两行；
 * - 页面里还有订单号、卡号尾号、余额，这些数字都不是金额。
 */
object AmountParser {

    enum class Status { SUCCESS, FAILED, CANCELLED, PENDING, UNKNOWN }

    /**
     * @param cents 金额（分），恒为非负
     * @param sign  +1 收入 / -1 支出（来自 `+` `-` 前缀） / 0 未知
     */
    data class AmountHit(val cents: Long, val source: String, val sign: Int)

    private const val SIGN_MINUS = "-－−"
    private const val SIGN_PLUS = "+＋"

    /** 带币种：可带前置正负号 */
    private val moneyRe = Regex("""([+＋\-－−])?\s*[¥￥]\s*(\d{1,7}(?:,\d{3})*(?:\.\d{1,2})?)""")

    /**
     * 标签 + 可选符号 + 金额，中间允许冒号、空格、少量汉字。
     *
     * `支出|收入` 是为了支付宝新版账单详情的主金额：`支出19元`（没有 ¥、没有小数点、
     * 标签和数字直接粘在一起）。方向由这个标签给出 —— 见 [signOfLabel]。
     */
    private val labeledRe = Regex(
        """(实付[款项]?|实际支付|应付[款项]?|合计|总计|支付金额|付款金额|消费金额|订单金额|账单金额|支出|收入|金额)""" +
            """\s*([+＋\-－−])?[^\d¥￥+＋\-－−]{0,4}[¥￥]?\s*(\d{1,7}(?:,\d{3})*(?:\.\d{1,2})?)"""
    )

    /** 整行就是一个金额：`-3.50`、`¥25.80`、`25.80元`、`+12.00` */
    private val bareRe = Regex(
        """^\s*([+＋\-－−])?\s*[¥￥]?\s*(\d{1,7}(?:,\d{3})*(?:\.\d{1,2})?)\s*(?:元)?\s*$"""
    )

    /** 只认标签、后面跟不跟数字都行（用于「标签在上一行」的邻接抽取） */
    private val labelStartsRe = Regex(
        """^(实付[款项]?|实际支付|应付[款项]?|合计|总计|支付金额|付款金额|消费金额|订单金额|账单金额|支出|收入|金额)"""
    )

    /** 这些行里的数字不是"实付"：原价、单号、卡号、余额、积分 */
    private val excludedRe = Regex(
        """原价|原金额|订单编?号|交易单号|转账单号|商户单号|流水号|积分|卡号|尾号|余额|可用|额度"""
    )

    private val timePatterns: List<Regex> = listOf(
        Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日\s*(\d{1,2})[:点时](\d{2})(?:[:分](\d{2}))?"""),
        Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s*(\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        Regex("""(\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日"""),
    )

    private val successWords = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成", "转账成功",
        "收款成功", "充值成功", "还款成功", "提现成功", "购买成功", "下单成功",
        "拼单成功", "已收钱", "已收款", "已领取", "已存入零钱", "交易完成",
    )
    private val failedWords = listOf("支付失败", "付款失败", "交易失败", "退款失败", "退款未成功", "退款不成功")
    private val cancelledWords = listOf("已取消", "已关闭", "已撤销", "交易已关闭", "订单已取消")
    private val pendingWords = listOf(
        "待退款", "退款待入账", "等待商家退款", "未退款", "退款申请中", "退款申请已提交", "已提交退款申请", "已申请退款", "退款处理中", "退款中", "等待退款", "退款审核", "退款待处理", "正在校验", "正在付款", "正在支付", "支付中", "处理中", "计算中",
        "请输入支付密码", "请验证指纹", "人脸检测中", "正在加载",
    )

    /** 必须是明确已完成的退款；“退回”可能只是转账退回，不能泛化。 */
    fun isCompletedRefund(items: List<String>): Boolean = items.any { line ->
        !listOf("时间", "预计", "尚未", "未退款", "申请").any { line.contains(it) } &&
        listOf("退款成功", "退款完成", "退款已完成", "已退款", "已全额退款", "已部分退款", "退款到账", "退款已到账").any { line.contains(it) }
    }

    /** 退款页可能同时展示原付款，优先取退款金额。 */
    fun findRefundAmount(items: List<String>): AmountHit? {
        for ((index, line) in items.withIndex()) {
            if (!line.contains("退款金额") && !line.contains("已退金额")) continue
            findAmount(listOf(line))?.let { return it }
            items.getOrNull(index + 1)?.let { next ->
                findAmount(listOf("金额", next))?.let { return it }
            }
        }
        if (items.any { it.contains("退款金额") || it.contains("已退金额") }) return null
        return findAmount(items)
    }

    // ---------- 金额 ----------

    /** "@¥1,234.5" -> 123450（分）。解析失败返回 null。 */
    fun toCents(raw: String): Long? {
        val s = raw.trim()
            .replace(",", "").replace("，", "")
            .replace("¥", "").replace("￥", "")
            .replace("元", "")
            .replace("+", "").replace("＋", "")
            .replace("-", "").replace("－", "").replace("−", "")
        if (s.isEmpty()) return null
        val d = s.toDoubleOrNull() ?: return null
        if (d < 0 || d > 10_000_000) return null
        return Math.round(d * 100)
    }

    /**
     * 抽取"实付金额"。优先级：
     *   标签邻接 > 带币种金额 > 唯一裸金额（含 `-3.50` 这种）
     */
    fun findAmount(items: List<String>): AmountHit? {
        // 1) 同一行里 "实付 ¥12.34" / "合计 -3.50"
        for (item in items) {
            if (excludedRe.containsMatchIn(item)) continue
            val m = labeledRe.find(item) ?: continue
            val cents = toCents(m.groupValues[3]) ?: continue
            val sign = signOf(m.groupValues[2]).let {
                if (it != 0) it else signOfLabel(m.groupValues[1])
            }
            return AmountHit(cents, "labeled:${item.take(24)}", sign)
        }
        // 2) 标签在一行、金额在下一行
        for (i in 0 until items.size - 1) {
            val head = items[i]
            if (excludedRe.containsMatchIn(head)) continue
            if (!labelStartsRe.containsMatchIn(head)) continue
            if (labeledRe.find(head)?.groupValues?.get(3)?.isNotEmpty() == true) continue
            val m = bareRe.find(items[i + 1].trim()) ?: continue
            val cents = toCents(m.groupValues[2]) ?: continue
            return AmountHit(cents, "adjacent:${head.take(12)}", signOf(m.groupValues[1]))
        }
        // 3) 带 ¥ 的金额（排除原价/单号行）
        val moneyHits = candidates(items) { line ->
            moneyRe.find(line)?.let { it.groupValues[2] to signOf(it.groupValues[1]) }
        }
        if (moneyHits != null) return moneyHits.copy(source = "money-symbol")

        // 4) 全屏只有一个"看起来像钱"的裸金额（微信交易详情就是这种：-3.50）
        val bareHits = candidates(items) { line ->
            bareRe.find(line)?.let { it.groupValues[2] to signOf(it.groupValues[1]) }
        }
        if (bareHits != null) return bareHits.copy(source = "bare-unique")

        return null
    }

    /**
     * 只在"全屏唯一金额"时才认，避免把列表页/推荐位里的数字当成实付。
     * 同一金额出现多次（例如标题和明细各一次）视为一致，取其一。
     */
    private fun candidates(
        items: List<String>,
        extract: (String) -> Pair<String, Int>?,
    ): AmountHit? {
        val hits = items
            .filterNot { excludedRe.containsMatchIn(it) }
            .mapNotNull { extract(it.trim()) }
            .mapNotNull { (raw, sign) -> toCents(raw)?.let { it to sign } }
            .distinct()

        if (hits.size != 1) return null
        val (cents, sign) = hits.first()
        return AmountHit(cents, "", sign)
    }

    private fun AmountHit.copy(source: String) = AmountHit(cents, source, sign)

    private fun signOf(sign: String?): Int = when {
        sign.isNullOrEmpty() -> 0
        SIGN_PLUS.contains(sign[0]) -> 1
        SIGN_MINUS.contains(sign[0]) -> -1
        else -> 0
    }

    /** 标签本身就是方向：支付宝详情页的 `支出19元` / `收入19元` */
    private fun signOfLabel(label: String): Int = when (label) {
        "支出" -> -1
        "收入" -> 1
        else -> 0
    }

    // ---------- 时间 ----------

    fun findRefundTime(items: List<String>): Long? {
        for ((index, line) in items.withIndex()) {
            if (!listOf("退款时间", "到账时间", "退款到账时间").any { line.contains(it) }) continue
            findTime(listOf(line))?.let { return it }
            items.getOrNull(index + 1)?.let { next -> findTime(listOf(next))?.let { return it } }
        }
        return findTime(items)
    }

    fun findTime(items: List<String>, now: LocalDateTime = LocalDateTime.now()): Long? {
        for (item in items) {
            for (pattern in timePatterns) {
                val m = pattern.find(item) ?: continue
                parseMatch(m, now)?.let { return it }
            }
        }
        return null
    }

    private fun parseMatch(m: MatchResult, now: LocalDateTime): Long? {
        val g = m.groupValues
        return try {
            when (g.size) {
                // yyyy年M月d日 H:mm[:ss] / yyyy-M-d H:mm[:ss]
                7 -> LocalDateTime.of(
                    g[1].toInt(), g[2].toInt(), g[3].toInt(),
                    g[4].toInt(), g[5].toInt(), if (g[6].isEmpty()) 0 else g[6].toInt()
                )
                // M-d H:mm[:ss]
                6 -> LocalDateTime.of(
                    now.year, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt()
                )
                // yyyy年M月d日
                4 -> LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()).atStartOfDay()
                else -> return null
            }.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: RuntimeException) {
            null // 非法日期（"13月45日" 之类 OCR 噪音）直接丢弃
        }
    }

    // ---------- 状态 ----------

    /** “申请退款”常是正常账单上的按钮，只有出现在状态字段或退款详情中才表示未完成。 */
    private fun refundApplicationIsStatus(items: List<String>): Boolean {
        val refundPage = items.any { it.trim() in listOf("退款详情", "退款申请", "退款进度") }
        return items.withIndex().any { (index, line) ->
            (line.contains("申请退款") || line.contains("退款申请")) &&
                (refundPage || line.contains("状态") ||
                    items.getOrNull(index - 1)?.let { it.contains("当前状态") || it.contains("退款状态") } == true)
        }
    }

    fun status(items: List<String>): Status {
        val joined = items.joinToString(" ")
        return when {
            failedWords.any { joined.contains(it) } -> Status.FAILED
            listOf("取消退款", "退款取消", "退款已取消", "退款关闭").any { joined.contains(it) } -> Status.CANCELLED
            pendingWords.any { joined.contains(it) } || refundApplicationIsStatus(items) -> Status.PENDING
            isCompletedRefund(items) -> Status.SUCCESS
            cancelledWords.any { joined.contains(it) } -> Status.CANCELLED
            successWords.any { joined.contains(it) } -> Status.SUCCESS
            else -> Status.UNKNOWN
        }
    }
}
