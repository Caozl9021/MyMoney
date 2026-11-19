package com.example.billkit.root

import com.example.billkit.notification.NotificationEnvelopeGuard
import org.junit.Assert.*
import org.junit.Test

/** 独立合成信封，验证拒绝边界，不宣称真实ROM已支持这些字段。 */
class RootNotificationEnvelopeTest {
    private fun dumped(flags: String = "0", category: String = "null", template: String = "null", text: String = "已支付19.99元") =
        NotificationDumpParser.parse("""
            NotificationRecord(0x1: pkg=com.tencent.mm key=0|com.tencent.mm|1|null|10000 Notification(channel=payment flags=$flags category=$category))
              posttime=1793300000000
              extras={
                android.template=$template
                android.title=String (微信支付)
                android.text=String ($text)
              }
        """.trimIndent()).single()

    @Test fun `只有完整显式信封才能用于准入`() {
        val d = dumped()
        assertTrue(d.envelopeMetadataKnown)
        assertEquals("payment", d.channelId)
        assertNull(NotificationEnvelopeGuard.rejection(d.observation(1_793_300_001_000L)!!))
        assertFalse(d.copy(categoryKnown = false).envelopeMetadataKnown)
        assertNull(d.copy(templateKnown = false).observation(1_793_300_001_000L))
        assertNull(d.copy(flags = null).observation(1_793_300_001_000L))
        assertNull(d.copy(extrasComplete = false).observation(1_793_300_001_000L))
    }

    @Test fun `不同格式flags均阻止汇总和持续通知`() {
        for (flags in listOf("0x200", "512", "GROUP_SUMMARY", "AUTO_CANCEL|GROUP_SUMMARY")) {
            val o = dumped(flags).observation(1_793_300_001_000L)!!
            assertEquals("group-summary", NotificationEnvelopeGuard.rejection(o))
        }
        for (flags in listOf("0x2", "2", "ONGOING_EVENT")) {
            assertEquals("ongoing-notification", NotificationEnvelopeGuard.rejection(dumped(flags).observation(1_793_300_001_000L)!!))
        }
        assertFalse(dumped("UNKNOWN_FLAG").envelopeMetadataKnown)
    }

    @Test fun `聊天风格和类别都不能因交易正文准入`() {
        assertEquals("chat-notification", NotificationEnvelopeGuard.rejection(dumped(category = "msg").observation(1_793_300_001_000L)!!))
        assertEquals("chat-notification", NotificationEnvelopeGuard.rejection(dumped(template = "String (android.app.Notification\$MessagingStyle)").observation(1_793_300_001_000L)!!))
    }

    @Test fun `缺时间空正文和截断不能准入`() {
        assertEquals("missing-observation-time", NotificationEnvelopeGuard.rejection(dumped().copy(postTime = 0).observation(1_793_300_001_000L)!!))
        assertEquals("empty-or-hidden-content", NotificationEnvelopeGuard.rejection(dumped(text = "").observation(1_793_300_001_000L)!!))
        assertEquals("truncated-content", NotificationEnvelopeGuard.rejection(dumped(text = "付款成功19.99元…").observation(1_793_300_001_000L)!!))
    }

    @Test fun `未闭合extras不能把缺省字段误当作空`() {
        val partial = """
            NotificationRecord(0x1: pkg=com.tencent.mm Notification(channel=payment flags=0))
              posttime=1793300000000
              extras={
                android.title=String (微信支付)
                android.text=String (成功付款19.99元)
        """.trimIndent()
        val d = NotificationDumpParser.parse(partial).single()
        assertEquals("成功付款19.99元", d.text)
        assertFalse(d.envelopeMetadataKnown)
        assertNull(d.observation(1_793_300_001_000L))
    }
}
