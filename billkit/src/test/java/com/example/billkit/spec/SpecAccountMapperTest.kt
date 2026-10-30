package com.example.billkit.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SPEC §8 阶段 6 的账户映射契约。
 *
 * 守两条底线：
 * - 用户账本里有的账户优先用（用户建了 `招商银行` 就不要归到 `信用卡`）；
 * - 认不出来就留空，绝不编一个账本里不存在的账户名。
 */
class SpecAccountMapperTest {

    private val defaults = listOf(
        "微信钱包", "微信零钱通", "支付宝", "余额宝", "花呗", "白条",
        "储蓄卡", "信用卡", "现金", "数字人民币",
    )

    @Test
    fun `尾号从原文剥离不参与匹配`() {
        assertEquals("招商银行信用卡", SpecAccountMapper.normalize("招商银行信用卡(1234)"))
        assertEquals("招商银行信用卡", SpecAccountMapper.normalize("招商银行信用卡（1234）"))
        assertEquals("银行卡", SpecAccountMapper.normalize("银行卡尾号 1234"))
        assertEquals("1234", SpecAccountMapper.tail("招商银行信用卡(1234)"))
        assertNull(SpecAccountMapper.tail("支付宝余额"))
    }

    @Test
    fun `产品名映射到账本默认账户`() {
        // 规则里最常见的几种写法
        assertEquals("支付宝", SpecAccountMapper.resolve("支付宝余额", defaults))
        assertEquals("余额宝", SpecAccountMapper.resolve("余额宝", defaults))
        assertEquals("花呗", SpecAccountMapper.resolve("支付宝花呗", defaults))
        assertEquals("微信钱包", SpecAccountMapper.resolve("微信零钱", defaults))
        assertEquals("微信零钱通", SpecAccountMapper.resolve("零钱通", defaults))
        assertEquals("信用卡", SpecAccountMapper.resolve("招商银行信用卡(1234)", defaults))
        assertEquals("储蓄卡", SpecAccountMapper.resolve("工商银行储蓄卡(8899)", defaults))
    }

    @Test
    fun `用户自己建的账户优先于默认兜底`() {
        val custom = listOf("招商银行", "支付宝", "微信钱包", "储蓄卡")
        // 用户建了同名银行账户，就用它，而不是归到「信用卡」
        assertEquals("招商银行", SpecAccountMapper.resolve("招商银行信用卡(1234)", custom))
    }

    @Test
    fun `认不出来的账户留空而不是编造`() {
        assertNull(SpecAccountMapper.resolve("", defaults))
        assertNull(SpecAccountMapper.resolve(null, defaults))
        assertNull(SpecAccountMapper.resolve("某某不存在的钱包", defaults))
        // 账本里一个账户都没有时也不能凭空造
        assertNull(SpecAccountMapper.resolve("支付宝余额", emptyList()))
    }

    @Test
    fun `规格资产表只在用户没有同名账户时兜底`() {
        val specAssets = listOf("工商银行", "农业银行", "支付宝", "微信", "高")
        // 用户账本里没有「工商银行」，用规格资产名兜底
        assertEquals("工商银行", SpecAccountMapper.resolve("工商银行储蓄卡(8899)", emptyList(), specAssets))
        // 用户账本里有「储蓄卡」时，用户账户优先于规格资产名——
        // 不能凭空映射到账本里并不存在的「工商银行」。
        assertEquals("储蓄卡", SpecAccountMapper.resolve("工商银行储蓄卡(8899)", defaults, specAssets))
    }

    @Test
    fun `示例月付这类没有对应账户的留空`() {
        // 账本默认没有「示例月付」账户；原文仍然保留在 method 字段，不丢信息。
        assertNull(SpecAccountMapper.resolve("示例月付", defaults))
    }
}
