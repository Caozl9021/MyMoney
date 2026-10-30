package io.github.caozl9021.mymoney.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 确认卡里「用户手改的金额文本」→ 分。
 *
 * 两个坑，都别再踩：
 * 1. **不能用 `toLong()` 截断**。`19.99 * 100` 在 Double 里是 `1998.9999999999998`，
 *    截断会少 1 分（0.29 → 28、1.15 → 114、8.70 → 869）。必须 `roundToLong()`。
 * 2. **不能依赖默认 Locale 的 `"%.2f".format()`**。德语/法语等 Locale 的小数点是逗号，
 *    格式化出 `19,99`，`toDoubleOrNull()` 解析失败后会静默回退成原值。
 *    所以格式化侧一律 `Locale.US`（见 [formatForInput]），解析侧把逗号也当千分位丢掉。
 *
 * @return 分；解析失败、非正数或超过上限时返回 null，由调用方回退成原值。
 */
internal fun parseAmountCents(text: String): Long? {
    val cleaned = text
        .replace("¥", "")
        .replace("￥", "")
        .replace(",", "")
        .replace("，", "")
        .filterNot { it.isWhitespace() }
    val yuan = cleaned.toDoubleOrNull() ?: return null
    if (!yuan.isFinite()) return null
    val cents = (yuan * 100).roundToLong()
    // 上限一百万元：挡住手滑输入的超长数字（也避免后续导出/统计溢出）
    if (cents <= 0L || cents > 100_000_000L) return null
    return cents
}

/**
 * 确认卡金额框的初始文本：新建（0 分）留空，让 `0.00` 只当占位符显示 ——
 * 用户第一下就能直接输数字，不用先删 0.00；有真实金额时照常格式化。
 */
internal fun initialAmountText(cents: Long): String = if (cents <= 0L) "" else formatForInput(cents)

/** 金额 → 输入框里的文本。固定两位小数、固定 `Locale.US` 的小数点。 */
internal fun formatForInput(cents: Long): String =
    String.format(Locale.US, "%.2f", abs(cents) / 100.0)
