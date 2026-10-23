package com.example.billkit

/**
 * OCR 文本归一化（纯 Kotlin，可单测）。
 *
 * OCR 输出和 UI 树差别很大：会多出状态栏、会把 `¥25.80` 拆成两行、会有推荐位、
 * 还会有各种识别噪声。这里做四件事，让下游规则不需要为 OCR 单独写一套：
 *
 *   1. 丢掉状态栏行（时间 / 电量 / 信号），只在**前两行**里判断，避免误杀账单里的时间
 *   2. 合并孤立的 `¥` / `￥` 行与其后的数字
 *   3. 在"相关推荐 / 猜你喜欢"等区块处截断
 *   4. 丢掉纯符号行，去连续重复
 */
object OcrNormalizer {

    private val stopSections = listOf(
        "相关推荐", "猜你喜欢", "更多推荐", "为你推荐", "看了又看",
        "同类好物", "推荐商品", "广告", "查看更多",
    )

    private val batteryOrSignal = Regex("""(\d{1,3}\s*%|\b5G\b|\b4G\b|\bLTE\b|Wi-?Fi|WLAN)""")
    private val clockOnly = Regex("""^\d{1,2}[:：]\d{2}$""")
    private val noiseOnly = Regex("""^[^\p{L}\p{N}¥￥]+$""")
    private val currencyOnly = Regex("""^[¥￥]$""")

    fun normalize(raw: List<String>, maxItems: Int = 200, maxChars: Int = 2000): List<String> =
        normalizeResult(raw, maxItems, maxChars).items

    fun normalizeResult(
        raw: List<String>, maxItems: Int = 200, maxChars: Int = 2000, maxVisited: Int = 5_000,
    ): CollectedText {
        val out = ArrayList<String>(minOf(raw.size, maxItems.coerceAtLeast(0)))
        val budget = TextCaptureBudget(maxItems, maxChars)
        var i = 0

        while (i < raw.size) {
            if (i >= maxVisited) {
                budget.markTruncated()
                break
            }

            val position = i
            var line = raw[i].trim()
            i++
            if (line.isEmpty()) continue

            // 1) 状态栏：只在前两行判断
            if (position <= 1 && (clockOnly.matches(line) || batteryOrSignal.containsMatchIn(line))) continue

            // 2) 推荐位之后全部丢弃
            if (stopSections.any { line.startsWith(it) || line.contains(it) }) {
                budget.markTruncated()
                break
            }

            // 3) 孤立币种与下一行数字合并（OCR 最常见的拆行）
            if (currencyOnly.matches(line)) {
                val next = raw.getOrNull(i)?.trim().orEmpty()
                if (next.isNotEmpty() && next.first().isDigit()) {
                    if (i >= maxVisited) {
                        budget.markTruncated()
                        break
                    }
                    line += next
                    i++
                } else {
                    continue
                }
            }

            // 4) 纯符号行丢弃
            if (noiseOnly.matches(line)) continue

            // 连续重复去重
            if (out.lastOrNull() == line) continue

            if (!budget.add(line)) break
            out += line
        }
        return CollectedText(out, budget.truncated)
    }
}
