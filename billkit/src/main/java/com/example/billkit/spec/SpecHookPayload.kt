package com.example.billkit.spec

/**
 * SPEC §2 数据契约的**组装层**：把采集层拿到的东西拼成 163 条规则能吃的那几种 JSON 形态。
 *
 * 为什么单独一层：
 * - 采集层（Xposed hook）跑在**目标 App 的进程**里，那边没有协程、没有数据库、也不该有业务判断；
 *   它只该产出「一个字符串」然后丢给宿主。拼串的逻辑放在 :billkit 才能用 JVM 单测锁住。
 * - 规则对形态很挑：`mMap` 形态和 `content` 形态混一点就全部不命中，而失败是静默的
 *   （`__dispatch` 会 catch 掉异常继续下一条）。所以形态必须有测试兜底。
 *
 * 三种形态（对应 SPEC §2.1 / §2.2 / §2.3）：
 * 1. [wechatMsgBox] —— 微信支付凭证，产出 `{"mMap":{...}}`，走 `common.parseWechat`。
 * 2. [wechatStructured] / [wechatGroupCollection] —— 微信转账/红包/群收款。
 *    **注意 `content` 的形态不一致**：转账/红包的规则里写的是 `JSON.parse(n.content)`
 *    （`rule_137`、`rule_156`），所以 content 必须是**再编码一次的字符串**；
 *    群收款的规则写的是 `n.content.mMap`（`rule_154`），所以 content 必须是**对象**。
 *    骨架代码 `WechatDbHooker.handleStructured` 统一放对象，转账/红包那条路径其实跑不通。
 * 3. [alipaySplit] —— 支付宝长连接，规则只读 `JSON.parse(raw)[0].pl`，必须逐元素投递。
 */
object SpecHookPayload {

    /** 微信支付凭证里 `t` 缺失时规则会拿到 0；永远补一个真实毫秒时间戳。 */
    fun wechatMsgBox(
        description: String,
        title: String,
        displayName: String,
        source: String,
        t: Long,
        cachedPayTools: String = "",
        cachedPayMoney: String = "",
        cachedPayShop: String = "",
    ): String {
        // 键永远齐全（哪怕是空串）：SPEC §3.3 的顺序陷阱 —— 键缺失会让规则读到 undefined，
        // 而 `undefined !== "微信支付"` 这种判断会把整条支付凭证丢掉。
        val mMap = linkedMapOf<String, Any?>(
            "description" to description,
            "source" to source,
            "title" to title,
            "display_name" to displayName,
            "cachedPayTools" to cachedPayTools,
            "cachedPayMoney" to cachedPayMoney,
            "cachedPayShop" to cachedPayShop,
            "t" to t,
        )
        return SpecJson.encode(linkedMapOf<String, Any?>("mMap" to mMap))
    }

    /**
     * 转账 / 红包。
     *
     * @param contentJson `{"msg":{"appmsg":{...}}}` 形态的**字符串**（会被再编码一次进 content 字段）
     */
    fun wechatStructured(
        type: String,
        isSend: Int,
        contentJson: String,
        chatUser: String,
        t: Long,
        cachedPayTools: String = "",
        cachedPayMoney: String = "",
        cachedPayShop: String = "",
    ): String = SpecJson.encode(
        linkedMapOf<String, Any?>(
            "type" to type,
            "isSend" to isSend,
            "content" to contentJson,
            "chatUser" to chatUser,
            // 规则里这两个键名不统一：rule_137/rule_156 前半段读 `hookerUser`，
            // rule_156 后半段（转账详情页）读 `hookUser`。两个都发，值相同。
            "hookerUser" to chatUser,
            "hookUser" to chatUser,
            "cachedPayTools" to cachedPayTools,
            "cachedPayMoney" to cachedPayMoney,
            "cachedPayShop" to cachedPayShop,
            "t" to t,
        ),
    )

    /**
     * 群收款（系统消息 type=10000）。`content` 是**对象**，里面再套 `mMap`，
     * 因为 `rule_154` 读的是 `n.content.mMap.content`。
     */
    fun wechatGroupCollection(
        mMapContent: String,
        isSend: Int,
        chatUser: String,
        t: Long,
        cachedPayTools: String = "",
        cachedPayMoney: String = "",
        cachedPayShop: String = "",
    ): String = SpecJson.encode(
        linkedMapOf<String, Any?>(
            "type" to "groupCollection",
            "isSend" to isSend,
            "content" to linkedMapOf<String, Any?>(
                "mMap" to linkedMapOf<String, Any?>("content" to mMapContent),
            ),
            "chatUser" to chatUser,
            "cachedPayTools" to cachedPayTools,
            "cachedPayMoney" to cachedPayMoney,
            "cachedPayShop" to cachedPayShop,
            "t" to t,
        ),
    )

    /**
     * 支付宝长连接拆包（SPEC §4）：规则里写的是 `JSON.parse(raw)[0].pl`，
     * 整数组投递只会被第一条吃掉。非数组按单元素处理；坏 JSON 返回空表而不是抛。
     */
    fun alipaySplit(raw: String?, maxItems: Int = 32): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { SpecJson.decode(raw) }.getOrNull()
        val items = when (parsed) {
            is List<*> -> parsed
            null -> return emptyList()
            else -> listOf(parsed)
        }
        return items.take(maxItems).map { SpecJson.encode(listOf(it)) }
    }

    /**
     * 采集层第一层去重（SPEC §7.4）：微信只算 `md5(description)`。
     * 这里用同样的输入串，但走 [CaptureDigest] 的 SHA-256 —— 只求「同一段文本得到同一个键」，
     * 用哪种摘要不影响判定，没必要为 MD5 引入额外实现。
     */
    fun wechatDedupKey(description: String): String = SpecDedupKey.of(description)
}

/** 采集层去重键（16 进制摘要）。与 `SpecDedup` 的「合成交易身份」是两件事，别混用。 */
internal object SpecDedupKey {
    fun of(value: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
