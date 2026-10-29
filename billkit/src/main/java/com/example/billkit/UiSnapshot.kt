package com.example.billkit

/**
 * 统一的上游输入：把无障碍读到的节点树归一化成「一个包名 + 一屏有序文本」，
 * 后续分类/抽取只认这个结构。
 *
 * 历史上还有 root 的 uiautomator dump 与截图 OCR 两个来源，已随 Root 页面轮询一起删除
 * （见 docs/SPEC_INTEGRATION.md）。[Source] 里保留的 ROOT_DUMP/ROOT_OCR 是为了让旧备份、
 * 旧识别历史里的 `source` 字段还能被解析出来，新增代码不应再产生这两个值。
 */
data class UiSnapshot(
    val packageName: String,
    val activityClass: String?,
    val items: List<String>,
    val truncated: Boolean = false,
    val source: Source = Source.ACCESSIBILITY,
    val configurationToken: String? = null,
) {
    enum class Source {
        ACCESSIBILITY,

        /** @deprecated 只用于解析历史数据；Root 页面轮询已删除，新代码不会再产生。 */
        @Deprecated("Root 页面轮询已删除，仅解析历史数据时出现")
        ROOT_DUMP,

        /** @deprecated 只用于解析历史数据；OCR 已删除，新代码不会再产生。 */
        @Deprecated("OCR 已随 Root 页面轮询删除，仅解析历史数据时出现")
        ROOT_OCR,
    }

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
