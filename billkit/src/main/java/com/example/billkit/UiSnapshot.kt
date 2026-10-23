package com.example.billkit

/**
 * 统一的上游输入：无论是无障碍读树，还是 root 的 uiautomator dump，
 * 都归一化成「一个包名 + 一屏有序文本」，后续分类/抽取只认这个结构。
 */
data class UiSnapshot(
    val packageName: String,
    val activityClass: String?,
    val items: List<String>,
    val truncated: Boolean = false,
    val source: Source = Source.ACCESSIBILITY,
) {
    enum class Source { ACCESSIBILITY, ROOT_DUMP, ROOT_OCR }

    fun diagnostic(description: String): String = description + if (truncated) " [截断]" else ""

    /** 便于日志与规则调试：整屏文本（不含换行噪音） */
    fun joined(limit: Int = 2000): String =
        items.joinToString("\n").take(limit)
}

/** Capture metadata shared by tree, XML and OCR collectors. */
data class CollectedText(val items: List<String>, val truncated: Boolean)

/** Strict item/character budget. Filtering and sorting remain source specific. */
class TextCaptureBudget(private val maxItems: Int, private val maxChars: Int) {
    private val values = ArrayList<String>()
    private var chars = 0
    var truncated: Boolean = false
        private set

    init { require(maxItems >= 0 && maxChars >= 0) }

    /** False means the value could not fit; no over-budget text is retained. */
    fun add(value: String): Boolean {
        if (values.size >= maxItems || value.length > maxChars - chars) {
            markTruncated()
            return false
        }
        values += value
        chars += value.length
        return true
    }

    fun markTruncated() { truncated = true }
    fun result(): CollectedText = CollectedText(values.toList(), truncated)
}
