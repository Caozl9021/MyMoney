package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.SampleExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleExportTest {

    @Test
    fun `splitLines 去掉空行与首尾空白`() {
        val text = "账单详情\n  \n¥25.80\n商户全称 星巴克咖啡\n\n"
        assertEquals(listOf("账单详情", "¥25.80", "商户全称 星巴克咖啡"), SampleExport.splitLines(text))
    }

    @Test
    fun `夹具里的美元符号必须转义`() {
        // 不转义的话 $25.80 会被 Kotlin 当成模板变量，粘贴后直接编译失败
        val code = SampleExport.kotlinFixture("alipayBill", listOf("¥25.80", "$25.80"))
        assertTrue(code.contains("\\$25.80"))
        assertTrue(code.contains("val alipayBill = listOf("))
    }

    @Test
    fun `夹具里的引号与反斜杠被转义`() {
        val code = SampleExport.kotlinFixture("x", listOf("星巴克\"咖啡\"", "a\\b"))
        assertTrue(code.contains("\\\"咖啡\\\""))
        assertTrue(code.contains("a\\\\b"))
    }

    @Test
    fun `属性名把包名里的点换成下划线`() {
        assertEquals("com_tentent_mm", SampleExport.toIdentifier("com.tentent.mm"))
        // 数字开头必须补下划线，否则不是合法标识符
        assertEquals("_2024", SampleExport.toIdentifier("2024"))
        assertEquals("sample", SampleExport.toIdentifier("!!!"))
    }

    @Test
    fun `样本文件名含包名与时间戳`() {
        val name = SampleExport.sampleFileName("com.tentent.mm", 1_726_000_000_000L)
        assertTrue(name.startsWith("com.tentent.mm-"))
        assertTrue(name.endsWith(".txt"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun `空列表不会生成残缺的 listOf`() {
        assertEquals("val empty = listOf<String>()", SampleExport.kotlinFixture("empty", emptyList()))
    }
}
