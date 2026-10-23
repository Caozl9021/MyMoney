package com.example.autobook

import com.example.autobook.ui.formatForInput
import com.example.autobook.ui.parseAmountCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 确认卡里「用户手改金额」的解析/格式化回归。
 *
 * 这两条都是真实踩过的坑：
 * 1. `19.99 * 100` 在 Double 里是 1998.9999999999998，`toLong()` 截断会少 1 分；
 * 2. 默认 Locale 的小数点是逗号时，`"%.2f".format()` 会产出 `19,99`，再解析失败。
 */
class AmountInputTest {

    @Test
    fun `两位小数不会少一分`() {
        // 这些数字用 toLong() 截断全都会掉 1 分，用 roundToLong() 才对
        assertEquals(1999L, parseAmountCents("19.99"))
        assertEquals(29L, parseAmountCents("0.29"))
        assertEquals(115L, parseAmountCents("1.15"))
        assertEquals(870L, parseAmountCents("8.70"))
        assertEquals(3535L, parseAmountCents("35.35"))
    }

    @Test
    fun `整数与一位小数`() {
        assertEquals(1900L, parseAmountCents("19"))
        assertEquals(1950L, parseAmountCents("19.5"))
        assertEquals(10L, parseAmountCents("0.1"))
    }

    @Test
    fun `带符号与千分位也能解析`() {
        assertEquals(123456L, parseAmountCents("1234.56"))
        assertEquals(123456L, parseAmountCents("1,234.56"))
        assertEquals(123456L, parseAmountCents("¥1234.56"))
        assertEquals(123456L, parseAmountCents(" 1234.56 "))
    }

    @Test
    fun `非法输入返回 null 而不是 0`() {
        // 返回 null 时调用方回退成原金额，绝不会把一笔 19 元写成 0 元
        assertNull(parseAmountCents(""))
        assertNull(parseAmountCents("."))
        assertNull(parseAmountCents("abc"))
        assertNull(parseAmountCents("0"))
        assertNull(parseAmountCents("0.00"))
        assertNull(parseAmountCents("-5"))
        assertNull(parseAmountCents("99999999"))
    }

    @Test
    fun `格式化固定两位小数且小数点是点`() {
        assertEquals("19.99", formatForInput(1999L))
        assertEquals("0.29", formatForInput(29L))
        assertEquals("8.70", formatForInput(870L))
        assertEquals("19.00", formatForInput(1900L))
    }

    @Test
    fun `格式化后再解析回到同一个值`() {
        // 往返一致：这是「点开确认卡不动金额、直接记一笔」不变形的保证
        listOf(1L, 29L, 115L, 870L, 1999L, 3535L, 123456L).forEach { cents ->
            assertEquals(cents, parseAmountCents(formatForInput(cents)))
        }
    }
}
