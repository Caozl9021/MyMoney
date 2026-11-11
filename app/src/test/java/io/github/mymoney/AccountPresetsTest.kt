package io.github.mymoney

import io.github.mymoney.ui.design.AccountPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPresetsTest {
    @Test
    fun kinds_match_locked_catalog() {
        assertEquals(listOf("PAYMENT", "CASH", "SAVINGS", "CREDIT"), AccountPresets.kinds.map { it.kind })
        assertTrue(AccountPresets.all.none { it.name == "储蓄卡" })
        assertTrue(AccountPresets.all.none { it.kind.startsWith("OTHER_") })
    }

    @Test
    fun cash_skips_specific_step_banks_do_not() {
        assertTrue(AccountPresets.skipsSpecificStep("CASH"))
        assertFalse(AccountPresets.skipsSpecificStep("PAYMENT"))
        assertFalse(AccountPresets.skipsSpecificStep("SAVINGS"))
        assertFalse(AccountPresets.skipsSpecificStep("CREDIT"))
        assertEquals(listOf("现金"), AccountPresets.forKind("CASH").map { it.name })
        assertEquals(
            listOf("工商银行", "农业银行", "中国银行", "建设银行", "招商银行"),
            AccountPresets.forKind("SAVINGS").map { it.name },
        )
    }

    @Test
    fun payment_and_credit_presets() {
        assertEquals(
            listOf("钱包", "支付宝", "微信钱包", "数字人民币"),
            AccountPresets.forKind("PAYMENT").map { it.name },
        )
        assertEquals(listOf("花呗", "白条", "信用卡"), AccountPresets.forKind("CREDIT").map { it.name })
        assertEquals("京东白条", AccountPresets.forKind("CREDIT").first { it.name == "白条" }.aliases)
    }
}
