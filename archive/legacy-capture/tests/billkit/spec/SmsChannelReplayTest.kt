package com.example.billkit.spec

import org.junit.Assert.*
import org.junit.Test

class SmsChannelReplayTest {
    private val engine = originalEngine()
    @Test fun `self规则包没有短信入口且完整银行成功句也拒绝`() {
        assertTrue(SpecSamples.load().none { it.packageName == "com.android.phone" })
        for (body in listOf("【招商银行】您尾号1234的借记卡消费人民币19.99元。",
            "【招商银行】您尾号1234的借记卡工资入账人民币8000.00元。")) {
            val raw = SpecJson.encode(mapOf("sender" to "95555", "body" to body, "t" to 1793289600000L))
            assertNull(engine.parse("com.android.phone", "DATA", raw))
        }
    }
    @Test fun `入账通知不推断为工资和收入`() {
        val raw = SpecJson.encode(mapOf("sender" to "95555", "body" to "【招商银行】您尾号1234的借记卡入账人民币100元。", "t" to 1793289600000L))
        assertNull(engine.parse("com.android.phone", "DATA", raw))
    }
    @Test fun `验证码和余额提醒不是交易`() {
        listOf("【招商银行】验证码123456，十分钟内有效。", "【招商银行】可用余额人民币999.00元。").forEach {
            assertNull(engine.parse("com.android.phone", "DATA", SpecJson.encode(mapOf("sender" to "95555", "body" to it))))
        }
    }
}
