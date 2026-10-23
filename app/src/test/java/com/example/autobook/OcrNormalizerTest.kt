package com.example.autobook

import com.example.billkit.OcrNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrNormalizerTest {

    @Test
    fun `丢掉状态栏的时间和信号`() {
        val out = OcrNormalizer.normalize(
            listOf("9:50", "5G 92%", "支付成功", "¥25.80")
        )
        assertEquals(listOf("支付成功", "¥25.80"), out)
    }

    @Test
    fun `账单内部的时间不会被当成状态栏丢掉`() {
        val out = OcrNormalizer.normalize(
            listOf("支付成功", "¥25.80", "支付时间", "09:50", "2024年10月21日")
        )
        assertTrue(out.contains("09:50"))
    }

    @Test
    fun `孤立的币种与下一行数字合并`() {
        val out = OcrNormalizer.normalize(
            listOf("实付款", "¥", "25.80", "支付方式", "零钱")
        )
        assertEquals(listOf("实付款", "¥25.80", "支付方式", "零钱"), out)
    }

    @Test
    fun `到推荐位就截断`() {
        val out = OcrNormalizer.normalize(
            listOf("支付成功", "¥25.80", "猜你喜欢", "某商品", "¥9.9")
        )
        assertEquals(listOf("支付成功", "¥25.80"), out)
    }

    @Test
    fun `纯符号行与连续重复被清掉`() {
        val out = OcrNormalizer.normalize(
            listOf("支付成功", "·", "支付成功", "¥25.80")
        )
        assertEquals(listOf("支付成功", "¥25.80"), out)
    }

    @Test
    fun `条数与字数上限生效`() {
        val raw = (1..300).map { "第${it}行内容" }
        val out = OcrNormalizer.normalize(raw, maxItems = 50)
        assertEquals(50, out.size)
        assertFalse(out.any { it.contains("第51行") })
    }
}
