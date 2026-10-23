package com.example.autobook

import com.example.autobook.data.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份文件的 JSON 编解码（不是通用 JSON 库，只覆盖备份用得到的值域）。
 *
 * 这些用例存在的意义：`org.json` 在 JVM 单测里是空桩，所以一旦序列化写错，
 * 只能等真机上恢复失败才发现 —— 那时用户的账本已经在备份文件里了。
 */
class MiniJsonTest {

    @Test
    fun `对象往返`() {
        val src = linkedMapOf<String, Any?>(
            "kind" to "AutoBookBackup",
            "format" to 1,
            "exportedAt" to 1_700_000_000_000L,
            "ok" to true,
            "note" to null,
        )
        val back = MiniJson.decode(MiniJson.encode(src)) as Map<*, *>
        assertEquals("AutoBookBackup", back["kind"])
        assertEquals(1L, back["format"])
        assertEquals(1_700_000_000_000L, back["exportedAt"])
        assertEquals(true, back["ok"])
        assertNull(back["note"])
    }

    @Test
    fun `数组与嵌套往返`() {
        val src = listOf(
            linkedMapOf("n" to "餐饮", "s" to listOf("三餐", "外卖")),
            linkedMapOf("n" to "交通", "s" to emptyList<String>()),
        )
        val back = MiniJson.decode(MiniJson.encode(src)) as List<*>
        assertEquals(2, back.size)
        val first = back[0] as Map<*, *>
        assertEquals("餐饮", first["n"])
        assertEquals(listOf("三餐", "外卖"), first["s"])
        assertEquals(emptyList<String>(), (back[1] as Map<*, *>)["s"])
    }

    @Test
    fun `字符串里的引号换行反斜杠都被转义`() {
        val tricky = "商户\"A\"\n第二行\\尾"
        val json = MiniJson.encode(listOf(tricky))
        // 编码后不能出现裸换行，否则某些编辑器/传输会截断文件
        assertTrue(!json.contains('\n'))
        assertEquals(listOf(tricky), MiniJson.decode(json))
    }

    @Test
    fun `中文原样保留`() {
        val json = MiniJson.encode(mapOf("note" to "星巴克咖啡·早餐"))
        assertTrue(json.contains("星巴克咖啡·早餐"))
        assertEquals("星巴克咖啡·早餐", (MiniJson.decode(json) as Map<*, *>)["note"])
    }

    @Test
    fun `整数解码成 Long 而小数解码成 Double`() {
        val back = MiniJson.decode("""{"cents":1999,"ratio":0.5}""") as Map<*, *>
        assertEquals(1999L, back["cents"])
        assertEquals(0.5, back["ratio"])
    }

    @Test
    fun `语法错误抛异常而不是返回半成品`() {
        listOf(
            """{"a":1""",       // 对象没闭合
            """{"a"}""",        // 缺冒号
            """[1,2""",         // 数组没闭合
            """{"a":1} trailing""", // 尾部多余内容
            """""",             // 空文本
        ).forEach { bad ->
            try {
                MiniJson.decode(bad)
                fail("应该抛异常: $bad")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
    }
}
