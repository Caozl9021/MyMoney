package io.github.mymoney

import com.example.billkit.spec.PersonalRule
import io.github.mymoney.data.MerchantNoteRule
import io.github.mymoney.data.NoteFillMode
import io.github.mymoney.data.rulesync.RuleSyncCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSyncCodecTest {
    private val personal = PersonalRule(
        id = "personal_abc123",
        name = "咖啡",
        keyword = "瑞幸",
        merchant = "瑞幸咖啡",
        updatedAt = 1234L,
    )

    @Test fun personalPayloadRoundTrip() {
        assertEquals(personal, RuleSyncCodec.personalFromPayload(RuleSyncCodec.personalPayload(personal)))
    }

    @Test fun merchantPayloadRoundTripAndStableId() {
        val rule = MerchantNoteRule(
            app = "com.eg.android.AlipayGphone",
            merchant = "淘宝闪购",
            mode = NoteFillMode.FIXED,
            text = "工作餐",
            id = RuleSyncCodec.stableMerchantId("com.eg.android.AlipayGphone|EXPENSE|淘宝闪购"),
        )
        assertEquals(rule, RuleSyncCodec.merchantFromPayload(RuleSyncCodec.merchantPayload(rule)))
        assertTrue(rule.id.matches(Regex("merchant_[A-Za-z0-9_-]{6,80}")))
        assertEquals(rule.id, RuleSyncCodec.stableMerchantId("com.eg.android.AlipayGphone|EXPENSE|淘宝闪购"))
        assertNotEquals(rule.id, RuleSyncCodec.stableMerchantId("com.tencent.mm|EXPENSE|淘宝闪购"))
    }

    @Test fun officialAndDefaultsRoundTrip() {
        assertEquals(false, RuleSyncCodec.officialFromPayload(RuleSyncCodec.officialPayload(false)))
        val defaults = RuleSyncCodec.noteDefaultsFromPayload(
            RuleSyncCodec.noteDefaultsPayload("{商户}·{支付方式}", false),
        )
        assertEquals("{商户}·{支付方式}", defaults.first)
        assertEquals(false, defaults.second)
    }

    @Test fun samePayloadIgnoresKeyOrder() {
        assertTrue(RuleSyncCodec.samePayload(
            """{"noteTemplate":"{商户}","productFirst":true}""",
            """{"productFirst":true,"noteTemplate":"{商户}"}""",
        ))
        assertTrue(!RuleSyncCodec.samePayload(
            """{"noteTemplate":"{商户}","productFirst":true}""",
            """{"noteTemplate":"{商户}","productFirst":false}""",
        ))
    }

    @Test fun invalidPayloadsAreRejected() {
        val failures = listOf(
            """{"enabled":"yes"}""",
            """{"noteTemplate":"x","productFirst":1}""",
            """{"id":"bad","app":"x","merchant":"m","type":"EXPENSE","mode":"PRODUCT","text":""}""",
        )
        failures.forEach { payload ->
            assertTrue(runCatching { RuleSyncCodec.officialFromPayload(payload) }.isFailure ||
                runCatching { RuleSyncCodec.noteDefaultsFromPayload(payload) }.isFailure ||
                runCatching { RuleSyncCodec.merchantFromPayload(payload) }.isFailure)
        }
    }
}
