package com.example.billkit.spec

import com.example.billkit.BillType

/**
 * SPEC §7.4 第二层去重：`DuplicateDetector` 的本地实现。
 *
 * <h3>它解决的具体问题</h3>
 * 同一笔微信支付会被两条通道各抓一次：WCDB 写入（hook）给出 `mMap`，通知栏给出 `{title,text,t}`。
 * 两条数据都没有订单号，[com.example.billkit.TransactionIdentity] 无法凭内容认出「这是同一笔」，
 * 于是会生成两条草稿、记两笔账。规格的答案是：时间窗内同金额同方向即视为同一笔，只落一条。
 *
 * <h3>为什么键名带 `dup:` 前缀</h3>
 * 这个身份是**合成的**，不是真实订单号。区别很重要：
 * 真实订单号相同 = 确定是同一笔，任何情况都能合并；
 * 合成身份相同 = 只是「金额和时间撞上了」，只有在**两个不同来源**各报一次时才算同一笔。
 * 同一来源在 10 分钟内报两次同额（连续买两杯同样的咖啡）是两笔真实消费，不能吞。
 * 这个判定收在 [shouldMerge] 里，是纯函数，可离线验证。
 *
 * <h3>窗口与桶</h3>
 * 时间切成 [WINDOW_MS] 的整桶，桶号进身份。跨桶边界的同一笔（12:09:59 与 12:10:01）会漏合并——
 * 刻意的取舍：桶边界只损失极少数合并机会，而滑动窗口会产生「A 与 B 同桶、B 与 C 同桶、
 * 但 A 与 C 不同桶」这种不满足传递性的判定，反而更难解释。
 */
object SpecDedup {

    /** SPEC §7.4 给的建议区间是 3–10 分钟；取 3 分钟下限，宁可漏合并也不吞掉第二笔真实付款。 */
    const val WINDOW_MS = 3 * 60 * 1000L

    private const val PREFIX = "dup:"

    /**
     * 合成交易身份。返回 null 表示这条草稿不该参与合并（来源、时间或金额不可信）。
     *
     * @param app 数据来源包名——同一笔支付只会在同一个 App 里发生
     * @param timeMillis 交易时间；必须是**明确时间**，不能传「现在」
     */
    fun key(app: String, type: BillType, priceCents: Long, timeMillis: Long,
            windowMs: Long = WINDOW_MS): String? {
        if (app.isBlank() || priceCents <= 0L || timeMillis <= 0L || windowMs <= 0L) return null
        return "$PREFIX$app|${type.name}|$priceCents|${timeMillis / windowMs}"
    }

    fun isSynthetic(key: String?): Boolean = key?.startsWith(PREFIX) == true

    /**
     * 这条合成身份能否与已有记录合并。
     *
     * @param existingSources 已有记录上登记过的采集来源（PAGE / NOTIFICATION / SMS …）
     * @return true 仅当身份是合成的、且已有记录来自**别的**来源——即多源重复，而非同源重付
     */
    fun shouldMerge(key: String?, inputSource: String, existingSources: Collection<String>): Boolean {
        if (!isSynthetic(key)) return false
        return existingSources.any { it != inputSource }
    }
}
