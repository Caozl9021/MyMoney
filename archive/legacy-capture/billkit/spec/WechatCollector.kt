package com.example.billkit.spec

/**
 * 微信 WCDB 采集层（SPEC §3）。
 *
 * **为什么能拿到明文**：WCDB 是微信对 SQLCipher 的封装，加密发生在 SQLite 驱动层；
 * `insertWithOnConflict` 收到的 `ContentValues` 还是明文。所以在这一层截获就够了，
 * 完全不需要去解密 `EnMicroMsg.db`（SPEC §0 明确禁止那条路）。
 *
 * 这一层放在 :billkit 而不是 Xposed 模块里，原因：
 * - 它全是纯逻辑（表名/类型分派、字段抽取、去重、payload 组装），JVM 单测能覆盖；
 * - Xposed 模块跑在微信进程里，那边没法跑测试，写错了只能靠真机付款试。
 *
 * 输入用 `Map<String, Any?>` 而不是 `ContentValues`：`ContentValues` 是 Android 类，
 * 在 JVM 单测里是空桩（`unitTests.isReturnDefaultValues = true`），拿不到值。
 * Xposed 侧负责把 `ContentValues` 摊平成 Map 再调进来。
 */
class WechatCollector(
    private val emitter: (app: String, type: String, payload: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val dedupTtlMs: Long = DEDUP_TTL_MS,
) {

    companion object {
        const val APP = "com.tencent.mm"
        const val TYPE = "DATA"

        const val TABLE_MESSAGE = "message"
        const val TABLE_APP_MESSAGE = "AppMessage"

        /** 群收款系统消息 */
        const val TYPE_GROUP_SYSTEM = 10000

        /** MsgBox / 服务号推送 —— 微信支付凭证走这里 */
        const val TYPE_MSG_BOX = 318767153

        /** 转账 */
        const val TYPE_TRANSFER = 419430449

        /** 红包 */
        const val TYPE_RED_PACKAGE = 436207665

        const val APPMSG_TYPE_ARTICLE = 5
        const val APPMSG_TYPE_TRANSFER = 2000

        /** 采集层去重 TTL：15 分钟（SPEC §7.4 第一层）。 */
        const val DEDUP_TTL_MS = 15 * 60 * 1000L
    }

    /** description 摘要 → 首次出现时间。LRU 上限 512，避免微信长跑时内存增长。 */
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 512
    }

    /**
     * 一次 insert 的入口。
     *
     * @param table  表名（`message` / `AppMessage`），其它表直接忽略
     * @param values 该行的明文列
     * @return true 表示产出了 payload（便于诊断与单测计数）
     */
    @Synchronized
    fun onInsert(table: String?, values: Map<String, Any?>?): Boolean {
        if (values == null) return false
        if (table != TABLE_MESSAGE && table != TABLE_APP_MESSAGE) return false
        val type = intOf(values["type"]) ?: return false
        return if (table == TABLE_MESSAGE) {
            handleMessage(type, values)
        } else {
            handleAppMessage(type, values)
        }
    }

    private fun handleMessage(type: Int, values: Map<String, Any?>): Boolean = when (type) {
        TYPE_GROUP_SYSTEM -> handleGroupCollection(values)
        TYPE_MSG_BOX -> handleMsgBox(values)
        TYPE_TRANSFER -> handleStructured(values, "transfer")
        TYPE_RED_PACKAGE -> handleStructured(values, "redPackage")
        // 其余类型（聊天文本、语音、图片…）与账无关，不处理。
        else -> false
    }

    /** 支付凭证（核心路径）。SPEC §3.3 步骤 1–8。 */
    private fun handleMsgBox(values: Map<String, Any?>): Boolean {
        val xml = strOf(values["content"])
        val root = WechatXml.parse(xml) ?: return false
        // 没有 <appmsg> 就不是公众号/支付消息（可能是系统提示），直接跳过。
        path(root, "msg", "appmsg") ?: return false
        val description = WechatXml.text(root, "msg", "appmsg", "des")
        if (description.isEmpty()) return false
        if (isDuplicate(description)) return false
        val title = WechatXml.text(root, "msg", "appmsg", "title")
        // display_name 优先 mmreader.template_header，兜底 publisher.nickname（SPEC §3.3 步骤 4）。
        val displayName = WechatXml.text(root, "msg", "appmsg", "mmreader", "template_header", "display_name")
            .ifEmpty { WechatXml.text(root, "msg", "appmsg", "mmreader", "publisher", "nickname") }
        // source 必须取自 appinfo.appname：规则第一步就是 `m.source !== "微信支付"` 过滤，
        // 采集层硬编码成「微信支付」会把公众号推文也当成付款。
        val source = WechatXml.text(root, "msg", "appinfo", "appname")
        val payload = SpecHookPayload.wechatMsgBox(
            description = description,
            title = title,
            displayName = displayName,
            source = source,
            t = now(),
        )
        emitter(APP, TYPE, payload)
        return true
    }

    /** 转账 / 红包：`content` 是 XML，转成 JSON **字符串**后交给规则自己 `JSON.parse`。 */
    private fun handleStructured(values: Map<String, Any?>, kind: String): Boolean {
        val xml = strOf(values["content"])
        val contentJson = WechatXml.toJson(xml) ?: return false
        val payload = SpecHookPayload.wechatStructured(
            type = kind,
            isSend = intOf(values["isSend"]) ?: 0,
            contentJson = contentJson,
            chatUser = strOf(values["talker"]),
            t = now(),
        )
        emitter(APP, TYPE, payload)
        return true
    }

    /**
     * 群收款（type=10000）。
     *
     * `rule_154` 读的是 `n.content.mMap.content`，所以 content 必须是**对象**，
     * 里面再套 `mMap.content`。这个形态与转账/红包不同，别图省事合并成一个方法。
     */
    private fun handleGroupCollection(values: Map<String, Any?>): Boolean {
        // 群收款系统消息的正文在 content 列里（有时是 XML，有时是纯文本）。
        val raw = strOf(values["content"])
        val text = WechatXml.text(WechatXml.parse(raw), "msg", "appmsg", "title").ifEmpty { raw }
        if (text.isEmpty()) return false
        val payload = SpecHookPayload.wechatGroupCollection(
            mMapContent = text,
            isSend = intOf(values["isSend"]) ?: 0,
            chatUser = strOf(values["talker"]),
            t = now(),
        )
        emitter(APP, TYPE, payload)
        return true
    }

    private fun handleAppMessage(type: Int, values: Map<String, Any?>): Boolean = when (type) {
        APPMSG_TYPE_ARTICLE -> {
            // 旧版公众号文章：description 已在列里，整体投递。规则自己挑能认的字段。
            val desc = strOf(values["description"])
            if (desc.isEmpty() || isDuplicate(desc)) {
                false
            } else {
                val payload = SpecHookPayload.wechatMsgBox(
                    description = desc,
                    title = strOf(values["title"]),
                    displayName = strOf(values["display_name"]),
                    source = strOf(values["appname"]),
                    t = now(),
                )
                emitter(APP, TYPE, payload)
                true
            }
        }

        APPMSG_TYPE_TRANSFER -> {
            val xml = strOf(values["xml"])
            val contentJson = WechatXml.toJson(xml) ?: return false
            val payload = SpecHookPayload.wechatStructured(
                type = "transfer",
                isSend = intOf(values["isSend"]) ?: 0,
                contentJson = contentJson,
                chatUser = strOf(values["talker"]),
                t = now(),
            )
            emitter(APP, TYPE, payload)
            true
        }

        else -> false
    }

    /** SPEC §7.4 第一层：微信只算 `md5(description)`；这里用等价的摘要键。 */
    @Synchronized
    fun isDuplicate(description: String): Boolean {
        val key = SpecHookPayload.wechatDedupKey(description)
        val at = now()
        val last = seen[key]
        if (last != null && at - last < dedupTtlMs) return true
        seen[key] = at
        if (seen.size > 400) seen.entries.removeAll { at - it.value >= dedupTtlMs }
        return false
    }

    @Synchronized
    fun clearDedup() = seen.clear()

    private fun path(root: Map<String, Any?>?, vararg keys: String): Map<String, Any?>? {
        var current: Any? = root
        for (key in keys) {
            current = (current as? Map<*, *>)?.get(key) ?: return null
        }
        @Suppress("UNCHECKED_CAST")
        return current as? Map<String, Any?>
    }

    private fun strOf(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }

    private fun intOf(value: Any?): Int? = when (value) {
        null -> null
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
