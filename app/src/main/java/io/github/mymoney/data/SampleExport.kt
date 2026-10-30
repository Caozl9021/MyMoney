package io.github.mymoney.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把诊断页抓到的一屏文本变成「能直接粘进 `Samples.kt` 的夹具代码」。
 *
 * 这是规则工作的基础设施：以前要把页面文本从手机上抄到电脑、再手工加引号和逗号，
 * 一屏 40 行文本光转义就能出错。这里全部做成纯函数，好在 JVM 单测里锁住格式。
 *
 * 只做文本处理，不碰 Android API —— 写文件的那一层在 `SettingsScreen` 里。
 */
object SampleExport {

    /** 诊断页给的是整屏拼接文本（`UiSnapshot.joined()`），按行还原成 items */
    fun splitLines(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 生成可直接粘进 `Samples.kt` 的 `val xxx = listOf(...)` 代码块。
     *
     * @param name Kotlin 属性名，非标识符字符会被换成 `_`，且不能以数字开头
     * @param items 一屏有序文本
     */
    fun kotlinFixture(name: String, items: List<String>): String {
        val id = toIdentifier(name)
        if (items.isEmpty()) return "val $id = listOf<String>()"
        val body = items.joinToString(",\n") { "        ${literal(it)}," }
        return "    val $id = listOf(\n$body\n    )"
    }

    /** 文件系统的样本名：`<包名>-<yyyyMMdd-HHmmss>.txt`，包名里的非法字符换成 `_` */
    fun sampleFileName(packageName: String, atMillis: Long): String {
        val safe = packageName.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifBlank { "unknown" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMillis))
        return "$safe-$stamp.txt"
    }

    /** 属性名归一化：`com.tentent.mm` → `com_tentent_mm`；数字开头补下划线 */
    fun toIdentifier(name: String): String {
        val cleaned = buildString {
            name.forEach { c -> append(if (c.isLetterOrDigit() || c == '_') c else '_') }
        }.trim('_')
        if (cleaned.isEmpty()) return "sample"
        return if (cleaned.first().isDigit()) "_$cleaned" else cleaned
    }

    /**
     * Kotlin 字符串字面量。
     *
     * 依次转义 `\` / `"` / `$` 与常见控制字符 —— `$` 必须转义，
     * 否则 `$25.80` 会被当成模板变量，编译直接报错。
     */
    private fun literal(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '$' -> sb.append("\\$")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
