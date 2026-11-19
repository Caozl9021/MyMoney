package com.example.billkit.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys notification` 是启发式来源：这里只锁「认得出常见形态」和「认不出时安静返回空表」。
 */
class NotificationDumpParserTest {

    private val sample = """
        NotificationRecord(0x0e2a1b3c: pkg=com.sankuai.meituan user=0 id=100 tag=null
          posttime=1791372831508
          extras={
            android.title=String (【示例月付】成功支付12.34元)
            android.text=String (查看示例使用详情>)
          })
        NotificationRecord(0x0f1b2c3d: pkg=com.mipay.wallet user=0 id=101 tag=null
          posttime=1791372831600
          extras={
            android.title=String (小米钱包送您一张券)
            android.bigText=String (本周可用，点击查看)
            android.text=String (忽略我)
          })
    """.trimIndent()

    @Test
    fun `按 NotificationRecord 分块取出包名标题正文与时间`() {
        val list = NotificationDumpParser.parse(sample)
        assertEquals(2, list.size)
        assertEquals("com.sankuai.meituan", list[0].packageName)
        assertEquals("【示例月付】成功支付12.34元", list[0].title)
        assertEquals("查看示例使用详情>", list[0].text)
        assertEquals(1_791_372_831_508L, list[0].postTime)
        assertEquals("com.mipay.wallet", list[1].packageName)
        assertEquals("本周可用，点击查看", list[1].text)
    }

    @Test
    fun `正文里的括号不能被剥掉`() {
        val raw = """
            NotificationRecord(0x1: pkg=com.tencent.mm user=0 id=1
              posttime=1791372831508
              extras={
                android.title=String (已支付¥4.00)
                android.text=String (使用工商银行储蓄卡(5821)支付¥4.00)
              })
        """.trimIndent()
        val list = NotificationDumpParser.parse(raw)
        assertEquals(1, list.size)
        assertEquals("使用工商银行储蓄卡(5821)支付¥4.00", list[0].text)
    }

    @Test
    fun `没有 posttime 时时间留零而不是编造`() {
        val raw = """
            NotificationRecord(0x1: pkg=com.eg.android.AlipayGphone user=0 id=1
              extras={
                android.title=String (付款成功)
              })
        """.trimIndent()
        val list = NotificationDumpParser.parse(raw)
        assertEquals(1, list.size)
        assertEquals(0L, list[0].postTime)
    }

    @Test
    fun `空输入与无通知记录返回空表`() {
        assertTrue(NotificationDumpParser.parse(null).isEmpty())
        assertTrue(NotificationDumpParser.parse("").isEmpty())
        assertTrue(NotificationDumpParser.parse("NotificationManagerService dump\n  mNotificationList:\n").isEmpty())
    }

    @Test
    fun `认不出的格式安静返回空表而不是抛异常`() {
        assertTrue(NotificationDumpParser.parse("NotificationRecord(0x1: 完全没有 pkg 字段)").isEmpty())
    }
}
