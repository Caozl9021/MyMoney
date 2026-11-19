package com.example.billkit.spec

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 微信 appmsg 的 XML → JSON（SPEC §3.5 的 `xmlToJson` 最小实现）。
 *
 * 为什么不用现成库：只需要「元素树 + 叶子文本」这一种形态，DOM 是 JDK 和 Android 都有的，
 * 引第三方 XmlToJson 只会多一份要 keep 的反射代码。
 *
 * **叶子文本的放置方式很重要**：元素没有子元素时，它的文本直接作为该键的**字符串值**
 * （`{"wcpayinfo":{"feedesc":"¥100.00"}}`），而不是再包一层 `{"content": "..."}`。
 * 因为 `rule_156` 写的是 `t.toFloat(n.feedesc)`，`rule_137` 写的是
 * `...wcpayinfo.sendertitle` —— 多包一层会让 toFloat 拿到对象、得到 NaN，表现为「转账不记账」。
 * 既有子元素又有文本的节点才把文本放进 `content` 键（`rule_154` 读的正是
 * `n.content.mMap.content`，那是群收款手写拼的形态，与这里无关）。
 *
 * XXE：微信的 appmsg 不可能有 DTD，直接禁掉 doctype，不给外部实体任何机会。
 */
object WechatXml {

    /** 解析成 `Map<String, Any?>`；坏 XML 返回 null（调用方当作「这条不是支付消息」跳过）。 */
    fun parse(xml: String?): Map<String, Any?>? {
        if (xml.isNullOrBlank()) return null
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            // 禁 DTD / 外部实体。Android 的 ExpatReader 与 JDK 的 Xerces 都认这个 feature 名。
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.isExpandEntityReferences = false
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        return mapOf(root.tagName to elementValue(root))
    }

    /** 编码成 JSON 字符串（`{"msg":{...}}`）；坏 XML 返回 null。 */
    fun toJson(xml: String?): String? = parse(xml)?.let { SpecJson.encode(it) }

    /**
     * 按路径取叶子文本，例如 `text(root, "msg", "appmsg", "des")`。
     * 任一层缺失或类型不符都返回空串 —— 规则对空串是安全的，对 undefined 不是。
     */
    fun text(root: Map<String, Any?>?, vararg path: String): String {
        var current: Any? = root
        for (key in path) {
            val map = current as? Map<*, *> ?: return ""
            current = map[key] ?: return ""
        }
        return when (current) {
            is String -> current
            is Map<*, *> -> (current["content"] as? String).orEmpty()
            null -> ""
            else -> current.toString()
        }
    }

    private fun elementValue(element: Element): Any {
        val children = childElements(element)
        if (children.isEmpty()) return element.textContent?.trim().orEmpty()
        val out = LinkedHashMap<String, Any?>(children.size)
        for (child in children) out[child.tagName] = elementValue(child)
        // 既有子元素又有文本：文本放 content，避免覆盖同名子元素。
        val ownText = element.childNodes.let { nodes ->
            buildString {
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                        append(node.nodeValue.orEmpty())
                    }
                }
            }
        }.trim()
        if (ownText.isNotEmpty()) out["content"] = ownText
        return out
    }

    private fun childElements(element: Element): List<Element> {
        val nodes = element.childNodes
        val out = ArrayList<Element>(nodes.length)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) out += node
        }
        return out
    }
}
