package io.github.mymoney

import io.github.mymoney.ui.design.AccountPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPresetsTest {
    @Test
    fun kinds_include_all_six_account_types() {
        assertEquals(
            listOf("PAYMENT", "CASH", "SAVINGS", "CREDIT", "OTHER_ASSET", "OTHER_DEBT"),
            AccountPresets.kinds.map { it.kind },
        )
        assertTrue(AccountPresets.all.none { it.name == "储蓄卡" })
        assertTrue(AccountPresets.forKind("OTHER_ASSET").isNotEmpty())
        assertTrue(AccountPresets.forKind("OTHER_DEBT").isNotEmpty())
    }

    @Test
    fun no_kind_skips_specific_step_so_custom_creation_is_always_available() {
        AccountPresets.kinds.forEach { kind ->
            assertFalse("${kind.kind} should show specific/custom step", AccountPresets.skipsSpecificStep(kind.kind))
            assertTrue("${kind.kind} should have presets", AccountPresets.forKind(kind.kind).isNotEmpty())
        }
        assertTrue(AccountPresets.forKind("SAVINGS").size >= 15)
        assertTrue(AccountPresets.forKind("SAVINGS").map { it.name }.containsAll(
            listOf("工商银行", "农业银行", "中国银行", "建设银行", "招商银行", "交通银行", "邮储银行", "兴业银行"),
        ))
    }

    @Test
    fun payment_credit_and_quick_select_defaults() {
        assertTrue(AccountPresets.forKind("PAYMENT").map { it.name }.containsAll(
            listOf("钱包", "支付宝", "微信钱包", "微信零钱通", "余额宝", "数字人民币", "云闪付"),
        ))
        assertTrue(AccountPresets.forKind("CREDIT").map { it.name }.containsAll(
            listOf("花呗", "白条", "信用卡"),
        ))
        assertEquals("京东白条", AccountPresets.forKind("CREDIT").first { it.name == "白条" }.aliases)
        assertFalse(AccountPresets.kindInfo("OTHER_ASSET")!!.defaultQuickSelect)
        assertFalse(AccountPresets.kindInfo("OTHER_DEBT")!!.defaultQuickSelect)
        assertTrue(AccountPresets.kindInfo("PAYMENT")!!.defaultQuickSelect)
    }
}
