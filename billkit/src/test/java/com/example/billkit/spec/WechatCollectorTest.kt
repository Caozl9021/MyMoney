package com.example.billkit.spec

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 采集XML和原创规则的合成/脱敏回放；不把合成XML称为真机捕获。 */
class WechatCollectorTest {

    private val engine = SpecRuleEngine().apply {
        rebuild(
            SpecAssets.classpath(SpecAssets.COMMON_JS),
            SpecAssets.parseRules(SpecAssets.classpath(SpecAssets.RULES_ALL)),
        )
    }

    private val emitted = mutableListOf<Triple<String, String, String>>()
    private var clock = 1_791_372_831_000L
    private val collector = WechatCollector(
        emitter = { app, type, payload -> emitted += Triple(app, type, payload) },
        now = { clock },
    )

    /** 把一条原创 mMap 样本还原成 appmsg XML（结构与 SPEC §3.3 注释一致）。 */
    private fun xmlOf(mMap: Map<*, *>): String = """
        <msg>
          <appmsg>
            <title>${esc(mMap["title"])}</title>
            <des>${esc(mMap["description"])}</des>
            <mmreader>
              <template_header><display_name>${esc(mMap["display_name"])}</display_name></template_header>
              <publisher><nickname>${esc(mMap["display_name"])}</nickname></publisher>
            </mmreader>
          </appmsg>
          <appinfo><appname>${esc(mMap["source"])}</appname></appinfo>
        </msg>
    """.trimIndent()

    private fun esc(value: Any?): String = (value as? String).orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun msgBoxRow(mMap: Map<*, *>): Map<String, Any?> = mapOf(
        "type" to WechatCollector.TYPE_MSG_BOX,
        "content" to xmlOf(mMap),
        "talker" to "",
    )

    @Test
    fun `原创支付凭证经过采集层后保持业务语义`() {
        val samples = SpecSamples.load()
            .filter { it.packageName == "com.tencent.mm" && it.type == "DATA" && it.expectMatch }
        assertTrue(samples.size >= 9, "微信正样本太少：${samples.size}")

        var checked = 0
        for (sample in samples) {
            val data = SpecJson.decodeObject(sample.data)
            val mMap = data["mMap"] as? Map<*, *> ?: continue
            // 只覆盖「支付凭证」这一类（title 含 已支付/已收款）；账单/公众号走别的入口。
            val title = (mMap["title"] as? String).orEmpty()
            if (!title.startsWith("已支付") && !title.startsWith("已收款")) continue

            emitted.clear()
            // 回放样本里同一天会重复出现一模一样的凭证（同一段描述），采集层按 SPEC §3.3 会去重；
            // 这条用例验的是「形态能否被规则吃下」，所以每条样本前先清掉去重状态。
            collector.clearDedup()
            assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, msgBoxRow(mMap)),
                "采集层没产出 payload：$title")
            assertEquals(1, emitted.size)
            val (app, type, payload) = emitted.single()
            assertEquals(WechatCollector.APP, app)
            assertEquals(WechatCollector.TYPE, type)

            val hit = assertNotNull(engine.parse(app, type, payload), "往返后规则不命中：$title")
            assertEquals(sample.expectedRuleId, hit.ruleId)
            // 商户名来自 display_name，金额来自 title
            assertEquals(sample.expectedMerchant, hit.bill.shopName)
            assertTrue(hit.bill.priceCents > 0, "金额没解出来：$title")
            checked++
        }
        assertTrue(checked >= 6, "实际核对的支付凭证只有 $checked 条")
    }

    @Test
    fun `display_name 缺失时兜底 publisher 昵称`() {
        val xml = """
            <msg>
              <appmsg>
                <title>已支付¥4.00</title>
                <des>使用工商银行储蓄卡支付¥4.00</des>
                <mmreader><publisher><nickname>简笙</nickname></publisher></mmreader>
              </appmsg>
              <appinfo><appname>微信支付</appname></appinfo>
            </msg>
        """.trimIndent()
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE,
            mapOf("type" to WechatCollector.TYPE_MSG_BOX, "content" to xml)))
        val payload = emitted.single().third
        val mMap = SpecJson.decodeObject(payload)["mMap"] as Map<*, *>
        assertEquals("简笙", mMap["display_name"])
    }

    @Test
    fun `十五分钟内的同一段描述只产出一次`() {
        val row = msgBoxRow(mapOf(
            "title" to "已支付¥4.00",
            "description" to "使用工商银行储蓄卡支付¥4.00\n交易状态支付成功，对方已收款",
            "display_name" to "早餐店",
            "source" to "微信支付",
        ))
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, row))
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE, row), "TTL 内重复应被丢弃")
        assertEquals(1, emitted.size)

        // 过了 TTL 就允许再次产出（用户可能真的又付了一笔一样的钱）
        clock += WechatCollector.DEDUP_TTL_MS + 1
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, row))
        assertEquals(2, emitted.size)
    }

    @Test
    fun `其它表与其它类型一律忽略`() {
        emitted.clear()
        assertFalse(collector.onInsert("Chatting", mapOf("type" to WechatCollector.TYPE_MSG_BOX)))
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE, mapOf("type" to 1)))
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE, mapOf("type" to 49)))
        assertFalse(collector.onInsert(null, null))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `没有 des 的 MsgBox 不产出`() {
        val xml = "<msg><appmsg><title>已支付¥4.00</title></appmsg><appinfo><appname>微信支付</appname></appinfo></msg>"
        emitted.clear()
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE,
            mapOf("type" to WechatCollector.TYPE_MSG_BOX, "content" to xml)))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `source 缺失时规则不会误判成付款`() {
        val xml = """
            <msg>
              <appmsg><title>已支付¥4.00</title><des>使用工商银行储蓄卡支付¥4.00</des></appmsg>
            </msg>
        """.trimIndent()
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE,
            mapOf("type" to WechatCollector.TYPE_MSG_BOX, "content" to xml)))
        val payload = emitted.single().third
        assertNull(engine.parse("com.tencent.mm", "DATA", payload), "source 空时不该命中")
    }

    @Test
    fun `转账与红包的 content 走再编码字符串`() {
        val xml = """
            <msg><appmsg><wcpayinfo>
              <feedesc>¥100.00</feedesc><pay_memo>还款</pay_memo><paysubtype>1</paysubtype>
            </wcpayinfo></appmsg></msg>
        """.trimIndent()
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, mapOf(
            "type" to WechatCollector.TYPE_TRANSFER,
            "content" to xml,
            "isSend" to 1,
            "talker" to "wxid_abc",
        )))
        val payload = emitted.single().third
        val decoded = SpecJson.decodeObject(payload)
        assertTrue(decoded["content"] is String, "转账的 content 必须是字符串，规则要再 JSON.parse 一次")
        assertNull(engine.parse("com.tencent.mm", "DATA", payload), "此片段缺完整单号/业务时间，不得猜账")
    }

    @Test
    fun `群收款的 content 是对象而不是字符串`() {
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, mapOf(
            "type" to WechatCollector.TYPE_GROUP_SYSTEM,
            "content" to "<msg><appmsg><title>你支付了张三发起的群收款</title></appmsg></msg>",
            "isSend" to 1,
            "talker" to "12345@chatroom",
        )))
        val payload = emitted.single().third
        val decoded = SpecJson.decodeObject(payload)
        assertTrue(decoded["content"] is Map<*, *>, "群收款的 content 必须是对象，规则读 n.content.mMap")
        assertNull(engine.parse("com.tencent.mm", "DATA", payload), "群收款未明确方向/金额，不得猜账")
    }

    @Test
    fun `红包 type 分派到 redPackage 而不是 transfer`() {
        val xml = """
            <msg><appmsg><wcpayinfo><sendertitle>恭喜发财</sendertitle></wcpayinfo></appmsg></msg>
        """.trimIndent()
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, mapOf(
            "type" to WechatCollector.TYPE_RED_PACKAGE,
            "content" to xml,
            "isSend" to 1,
        )))
        val decoded = SpecJson.decodeObject(emitted.single().third)
        assertEquals("redPackage", decoded["type"])
    }

    @Test
    fun `坏 XML 不产出也不抛`() {
        emitted.clear()
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE,
            mapOf("type" to WechatCollector.TYPE_MSG_BOX, "content" to "<msg><appmsg>")))
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE,
            mapOf("type" to WechatCollector.TYPE_TRANSFER, "content" to "garbage")))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `clearDedup 后同一条能重新产出`() {
        val row = msgBoxRow(mapOf(
            "title" to "已支付¥4.00",
            "description" to "使用工商银行储蓄卡支付¥4.00",
            "display_name" to "早餐店",
            "source" to "微信支付",
        ))
        emitted.clear()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, row))
        assertFalse(collector.onInsert(WechatCollector.TABLE_MESSAGE, row))
        collector.clearDedup()
        assertTrue(collector.onInsert(WechatCollector.TABLE_MESSAGE, row))
    }
}
