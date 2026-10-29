package com.example.billkit.spec

/**
 * 极小的 JSON 编解码器（:billkit 内部用）。
 *
 * 为什么不用 `org.json`：它在 JVM 单测里是 android.jar 的空桩（方法返回 null / 直接抛
 * `Stub!`），规则目录、分类表、样本回放全都无法离线验证。也不值得为一个库引 Gson。
 *
 * 值域：`Map<String, Any?>` / `List<Any?>` / `String` / `Long` / `Double` / `Boolean` / `null`。
 * 整数走 [Long]，带小数点或指数的走 [Double]——规则产出里的 `money` 因此是 Double，
 * 换算成分时必须走 [toCents] 而不是 `* 100`。
 */
internal object SpecJson {

    fun encode(value: Any?): String = buildString { writeValue(value, this) }

    private fun writeValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                value.forEach { (k, v) ->
                    if (!first) sb.append(',')
                    first = false
                    writeString(k?.toString() ?: "", sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                value.forEach { v ->
                    if (!first) sb.append(',')
                    first = false
                    writeValue(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** 解析失败抛 [IllegalArgumentException]（坏资产要能和"空资产"区分开）。 */
    fun decode(text: String): Any? {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("JSON 尾部有多余内容（位置 ${p.pos}）")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeObject(text: String): Map<String, Any?> =
        decode(text) as? Map<String, Any?> ?: throw IllegalArgumentException("JSON 顶层不是对象")

    @Suppress("UNCHECKED_CAST")
    fun decodeArray(text: String): List<Any?> =
        decode(text) as? List<Any?> ?: throw IllegalArgumentException("JSON 顶层不是数组")

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("JSON 意外结束")
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else
                    throw IllegalArgumentException("JSON 非法字符 '$c'（位置 $pos）")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("JSON 期望 $word（位置 $pos）")
            pos += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw IllegalArgumentException("JSON 期望 ':'（位置 $pos）")
                pos++
                out[key] = parseValue()
                skipWs()
                if (atEnd()) throw IllegalArgumentException("JSON 对象未闭合")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("JSON 期望 ',' 或 '}'（位置 $pos）")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (atEnd()) throw IllegalArgumentException("JSON 数组未闭合")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("JSON 期望 ',' 或 ']'（位置 $pos）")
                }
            }
        }

        private fun parseString(): String {
            if (atEnd() || s[pos] != '"') throw IllegalArgumentException("JSON 期望字符串（位置 $pos）")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("JSON 字符串未闭合")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("JSON 转义未完成")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("JSON \\u 转义不完整")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("JSON 未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && s[pos].isDigit()) pos++
            var isFloat = false
            if (!atEnd() && s[pos] == '.') {
                isFloat = true
                pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("JSON 非法数字（位置 $start）")
            return if (isFloat) raw.toDouble() else (raw.toLongOrNull() ?: raw.toDouble())
        }
    }
}

/** 元→分。规则产出的 money 是元（Double），必须走 BigDecimal 才不会把 26.77 变成 2676。 */
internal fun toCents(yuan: Double): Long =
    java.math.BigDecimal(yuan.toString()).movePointRight(2)
        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
