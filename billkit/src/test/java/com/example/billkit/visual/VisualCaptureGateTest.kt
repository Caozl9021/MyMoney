package com.example.billkit.visual
import org.junit.Assert.*
import org.junit.Test
class VisualCaptureGateTest {
    @Test fun explicitSuccessfulNodesAreRequired() {
        assertTrue(VisualCaptureGate.allows("com.tencent.mm",listOf("支付成功","¥3.50"),false))
        assertFalse(VisualCaptureGate.allows("com.tencent.mm",emptyList(),false))
        assertFalse(VisualCaptureGate.allows("com.tencent.mm",listOf("完成","¥3.50"),false))
        assertFalse(VisualCaptureGate.allows("com.example.other",listOf("支付成功"),false))
    }
    @Test fun negativeSensitiveAndTruncatedEvidenceAlwaysWins() {
        for(word in listOf("输入密码","验证码","待付款","交易关闭","支付失败","身份验证"))
            assertFalse(VisualCaptureGate.allows("com.eg.android.AlipayGphone",listOf("支付成功",word),false))
        assertFalse(VisualCaptureGate.allows("com.tencent.mm",listOf("支付成功"),true))
    }
}
