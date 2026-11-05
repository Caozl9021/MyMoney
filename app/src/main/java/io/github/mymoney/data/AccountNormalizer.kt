package io.github.mymoney.data

/**
 * 账户模糊匹配的轻量归一：把「工商银行储蓄卡(5821)」「工行储蓄卡 5821」「工行」等写法收敛到可比较的形态，
 * 用于弹卡前「查账本」时判断一笔新采集和账本里已有记录是否是同一个付款账户。
 *
 * 不做外部依赖、不追求完备——只在窗口内几十条候选上做包含/前缀比对。太严会漏标、太松会误标，
 * 需真实账单调参；这里先给保守的一版，只把明确的写法差异抹平：
 *  - 全角/半角、括号统一去掉
 *  - 常见冗词（储蓄卡/借记卡/信用卡/银行卡/卡/账户/储蓄/银行）去掉
 *  - 「工商/建设/农业/交通/招商/中国银行…」等行名与「工行/建行/农行…」等简称做别名归并
 */
object AccountNormalizer {

    /** 银行全称/口语 → 标准简称。用于把「工商银行」和「工行」压成同一个 token。 */
    private val BANK_ALIASES: List<Pair<String, String>> = listOf(
        "中国工商银行" to "工行", "工商银行" to "工行", "工商" to "工行", "工行" to "工行",
        "中国建设银行" to "建行", "建设银行" to "建行", "建设" to "建行", "建行" to "建行",
        "中国农业银行" to "农行", "农业银行" to "农行", "农业" to "农行", "农行" to "农行",
        "中国银行" to "中行", "中行" to "中行",
        "中国交通银行" to "交行", "交通银行" to "交行", "交行" to "交行",
        "招商银行" to "招行", "招行" to "招行",
        "中国邮政储蓄银行" to "邮储", "邮政储蓄银行" to "邮储", "邮储银行" to "邮储", "邮储" to "邮储",
        "浦发银行" to "浦发", "浦发" to "浦发",
        "中信银行" to "中信", "中信" to "中信",
        "民生银行" to "民生", "民生" to "民生",
        "兴业银行" to "兴业", "兴业" to "兴业",
        "光大银行" to "光大", "光大" to "光大",
        "平安银行" to "平安", "平安" to "平安",
        "华夏银行" to "华夏", "华夏" to "华夏",
        "广发银行" to "广发", "广发" to "广发",
        "微信零钱" to "零钱", "零钱通" to "零钱通", "零钱" to "零钱",
        "支付宝余额" to "余额", "余额宝" to "余额宝", "余额" to "余额",
    )

    /** 命中即视为同一账户时忽略的冗词。 */
    private val NOISE = listOf("储蓄卡", "借记卡", "信用卡", "银行卡", "账户", "储蓄", "银行", "的", "卡")

    private fun strip(text: String): String {
        var t = text.trim().lowercase()
        // 全角括号/空格/常见分隔
        t = t.replace('（', '(').replace('）', ')')
        t = t.replace(Regex("[\\s\\-—_·.,，。()（）\\[\\]【】]"), "")
        return t
    }

    /** 银行名归一：先按别名表替换，再剥冗词，返回标准 token 串（可能多个 token 直接拼接）。 */
    fun bankToken(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var t = strip(raw)
        // 先做别名替换（长串优先，避免「中国银行」先被「银行」误伤）——按别名长度降序
        BANK_ALIASES.sortedByDescending { it.first.length }.forEach { (full, short) ->
            if (t.contains(full)) t = t.replace(full, short)
        }
        // 剩余冗词去掉
        NOISE.forEach { if (it != "银行") t = t.replace(it, "") }
        return t
    }

    /** 提取纯数字尾号（如 5821），空则 ""。 */
    fun tailDigits(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 4) digits.takeLast(4) else ""
    }

    /**
     * 是否是同一个付款账户。
     * 规则（保守）：
     *  - 任一方为空 → 视为「账户信息不足」，返回 true（不因缺账户信息而漏标，交用户判断）。
     *  - 双方都有 4 位尾号且不同 → 明确不同账户，false。
     *  - 行 token 相同（含一方是另一方子串）→ 同一账户。
     *  - 双方都无银行 token 但原文归一后相同/互相包含 → 同一。
     * 其余情况视为不同，避免误标。
     */
    fun sameAccount(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        val ta = tailDigits(a)
        val tb = tailDigits(b)
        if (ta.isNotEmpty() && tb.isNotEmpty() && ta != tb) return false
        val ba = bankToken(a)
        val bb = bankToken(b)
        if (ba.isNotEmpty() && bb.isNotEmpty()) {
            if (ba == bb || ba.contains(bb) || bb.contains(ba)) return true
            // 有银行 token 但不同 → 不同账户
            return false
        }
        // 没有可识别银行 token：退回原文归一比较
        val na = strip(a)
        val nb = strip(b)
        if (na.isEmpty() || nb.isEmpty()) return true
        return na == nb || na.contains(nb) || nb.contains(na)
    }
}
