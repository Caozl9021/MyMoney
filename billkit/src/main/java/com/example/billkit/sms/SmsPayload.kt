package com.example.billkit.sms

import com.example.billkit.spec.SpecJson

/**
 * 短信通道的纯逻辑部分（不依赖 Android API，可在 JVM 单测里跑）。
 *
 * 真正的 `BroadcastReceiver` 只负责把 `SmsMessage[]` 拼成一条正文、读出号码，
 * 剩下「这条短信值不值得交给规则引擎」的判断都在这里，便于离线验证。
 *
 * 数据契约见 SPEC §5.2：`{"body":"...","sender":"95555","t":<毫秒>}`，
 * 规则侧统一先调 `common.splitSms(raw)` 把「【银行名】正文」拆开。
 */
object SmsPayload {

    /**
     * 短于这个长度的基本是验证码（「【支付宝】登录验证码：161261」去掉抬头只剩 8 个字），
     * 直接丢弃可以省掉一次 JS 求值，也避免把验证码正文写进 app_data。
     */
    const val MIN_BODY_LENGTH = 8

    /** 单条正文上限，防止超长营销短信撑爆 `app_data`；运营商长短信拼接后极少超过这个量。 */
    const val MAX_BODY_LENGTH = 2_000

    /**
     * 组装规则引擎的输入。
     *
     * @return 需要入库并求值的 payload；正文过短、为空或超长被截断后为空时返回 null（调用方直接丢弃）
     */
    fun build(sender: String?, body: String?, t: Long): String? {
        val text = body?.trim().orEmpty()
        if (text.length < MIN_BODY_LENGTH) return null
        return SpecJson.encode(
            linkedMapOf(
                "body" to text.take(MAX_BODY_LENGTH),
                "sender" to sender.orEmpty(),
                "t" to t,
            ),
        )
    }
}

/**
 * 采集层去重（SPEC §7.4 第一层）。
 *
 * 同一条短信会被系统重复投递：应用被唤醒、运营商补发、双卡同时收到。
 * 用 `sender|body` 做键、默认 10 分钟 TTL，命中即丢弃——规则引擎不需要看到同一条两遍。
 *
 * 线程安全：`BroadcastReceiver.onReceive` 在同一个进程里可能被并发调用，全部方法加锁。
 */
class SmsDedup(
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val maxEntries: Int = 200,
) {
    private val seen = HashMap<String, Long>()

    /** @return true 表示这条短信是新的，可以继续处理 */
    @Synchronized
    fun accept(sender: String?, body: String?, now: Long): Boolean {
        val key = (sender.orEmpty()) + "|" + (body.orEmpty())
        val last = seen[key]
        if (last != null && now - last < ttlMs) return false
        seen[key] = now
        if (seen.size > maxEntries) seen.entries.removeAll { now - it.value >= ttlMs }
        return true
    }

    @Synchronized
    fun clear() = seen.clear()
}
