package com.example.billkit.spec

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** XML解析安全和本App采集契约的合成回放，不表示真机原始XML。 */
class WechatXmlTest {

    private val engine = SpecRuleEngine().apply {
        rebuild(SpecAssets.classpath(SpecAssets.COMMON_JS), SpecAssets.parseRules(SpecAssets.classpath(SpecAssets.RULES_ALL)))
    }

    /** SPEC §3.3 注释里给出的支付凭证 XML 原文。 */
    private val msgBoxXml = """
        <msg>
          <appmsg>
            <title>已支付¥4.00</title>
            <des>使用工商银行储蓄卡支付¥4.00
        交易状态支付成功，对方已收款</des>
            <mmreader>
              <template_header><display_name>早餐店</display_name></template_header>
              <publisher><nickname>早餐店</nickname></publisher>
            </mmreader>
          </appmsg>
          <appinfo><appname>微信支付</appname></appinfo>
        </msg>
    """.trimIndent()

    @Test
    fun `叶子元素直接是字符串而不是再包一层`() {
        val root = assertNotNull(WechatXml.parse(msgBoxXml))
        // 叶子值保留字符串，避免采集组装时改变输入结构。
        assertEquals("早餐店", WechatXml.text(root, "msg", "appmsg", "mmreader", "template_header", "display_name"))
        assertEquals("早餐店", WechatXml.text(root, "msg", "appmsg", "mmreader", "publisher", "nickname"))
        assertEquals("微信支付", WechatXml.text(root, "msg", "appinfo", "appname"))
    }

    @Test
    fun `多行文本保留换行不被压成一行`() {
        val root = assertNotNull(WechatXml.parse(msgBoxXml))
        val des = WechatXml.text(root, "msg", "appmsg", "des")
        assertTrue(des.contains("\n"), "des 的换行必须保留：规则里的正则依赖它分隔两行")
        assertTrue(des.startsWith("使用工商银行储蓄卡支付¥4.00"))
    }

    @Test
    fun `缺路径返回空串而不是 null 或抛异常`() {
        val root = assertNotNull(WechatXml.parse(msgBoxXml))
        assertEquals("", WechatXml.text(root, "msg", "appmsg", "nothere"))
        assertEquals("", WechatXml.text(root, "nothere", "appmsg"))
        assertEquals("", WechatXml.text(null, "msg"))
    }

    @Test
    fun `坏 XML 与空串返回 null`() {
        assertNull(WechatXml.parse(null))
        assertNull(WechatXml.parse(""))
        assertNull(WechatXml.parse("<msg><appmsg>"))
        assertNull(WechatXml.parse("not xml at all"))
    }

    @Test
    fun `禁用外部实体 防止 XXE`() {
        // 若 doctype 未被禁掉，解析会尝试读文件；这里断言「解析失败或实体未被展开」二者之一。
        val evil = """<?xml version="1.0"?><!DOCTYPE msg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <msg><appmsg><des>&xxe;</des></appmsg></msg>"""
        val root = WechatXml.parse(evil)
        if (root != null) {
            val des = WechatXml.text(root, "msg", "appmsg", "des")
            assertTrue(!des.contains("root:"), "实体被展开了：$des")
        }
    }

    @Test
    fun `解析出的 XML 能直接拼成规则命中的支付凭证`() {
        val root = assertNotNull(WechatXml.parse(msgBoxXml))
        val payload = SpecHookPayload.wechatMsgBox(
            description = WechatXml.text(root, "msg", "appmsg", "des"),
            title = WechatXml.text(root, "msg", "appmsg", "title"),
            displayName = WechatXml.text(root, "msg", "appmsg", "mmreader", "template_header", "display_name"),
            source = WechatXml.text(root, "msg", "appinfo", "appname"),
            t = 1_791_372_831_000L,
        )
        val hit = assertNotNull(engine.parse("com.tencent.mm", "DATA", payload))
        assertEquals("maimi_wechat_data_expense_v1", hit.ruleId)
        assertEquals(400L, hit.bill.priceCents)
        assertEquals("", hit.bill.shopName)
    }

    @Test
    fun `转账 XML 的 wcpayinfo 字段可被规则读取`() {
        val xml = """
            <msg>
              <appmsg>
                <wcpayinfo>
                  <feedesc>¥100.00</feedesc>
                  <pay_memo>还款</pay_memo>
                  <paysubtype>1</paysubtype>
                </wcpayinfo>
              </appmsg>
            </msg>
        """.trimIndent()
        val json = assertNotNull(WechatXml.toJson(xml))
        val payload = SpecHookPayload.wechatStructured(
            type = "transfer", isSend = 1, contentJson = json, chatUser = "李四",
            t = 1_791_372_831_000L, cachedPayTools = "零钱",
        )
        assertNull(engine.parse("com.tencent.mm", "DATA", payload), "片段缺完整身份/业务时间，不得推断成支出")
    }

    @Test
    fun `XML 里的数字不会被当成数字类型`() {
        // paysubtype 用 parseInt 读，字符串没问题；但 feedesc 若被解析成数字，
        // toFloat 拿到的 Double 也还好。这里锁住「一律当字符串」这个更稳的约定。
        val xml = "<msg><appmsg><wcpayinfo><paysubtype>1</paysubtype></wcpayinfo></appmsg></msg>"
        val root = assertNotNull(WechatXml.parse(xml))
        val value = WechatXml.text(root, "msg", "appmsg", "wcpayinfo", "paysubtype")
        assertEquals("1", value)
    }
}
