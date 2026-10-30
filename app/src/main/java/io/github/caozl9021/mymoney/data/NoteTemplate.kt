package io.github.caozl9021.mymoney.data

import com.example.billkit.BillDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rendering does not opt the user into product text; only an explicit suggestion does. */
object NoteTemplate {
    const val TEMPLATE = "TEMPLATE"
    const val USER = "USER"
    const val SUGGESTION = "SUGGESTION"
    val TOKENS = listOf("{商户}", "{支付方式}", "{分类}", "{账户}", "{时间}", "{日期}", "{星期}", "{二级分类}", "{商品}")
    val PRESETS = listOf("{商户}", "{商户}·{支付方式}", "{商户}·{分类}", "自动记账", "{日期}·{商户}", "{星期}·{二级分类}", "{商户}·{商品}", "{商品}")
    private val token = Regex("\\{[^{}]+\\}")
    private val separator = Regex("[·\\-|,，\\s]+")
    fun unknownTokens(template: String): List<String> = token.findAll(template.replace("{{", "\u0001").replace("}}", "\u0002"))
        .map { it.value }.filter { it !in TOKENS }.distinct().toList()
    fun limit(text: String, max: Int = 60): String = if (text.codePointCount(0, text.length) <= max) text
        else text.substring(0, text.offsetByCodePoints(0, max))

    fun render(draft: BillDraft, template: String = LedgerConfig.noteTemplate, includeProduct: Boolean = false): String {
        if (template.isBlank()) return ""
        val date = Date(draft.timeMillis)
        val weekday = java.util.Calendar.getInstance().apply { time = date }.get(java.util.Calendar.DAY_OF_WEEK)
        val values = mapOf("{商户}" to draft.merchant.orEmpty(), "{支付方式}" to draft.method.orEmpty(),
            "{分类}" to draft.category.orEmpty(), "{账户}" to draft.account.orEmpty(),
            "{时间}" to SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date),
            "{日期}" to SimpleDateFormat("MM-dd", Locale.getDefault()).format(date),
            "{星期}" to "星期" + "日一二三四五六"[weekday - 1], "{二级分类}" to draft.subCategory.orEmpty(),
            "{商品}" to if (includeProduct) draft.productName.orEmpty() else "")
        val escaped = template.replace("{{", "\u0001").replace("}}", "\u0002")
        // Missing-token markers retain literal punctuation elsewhere, e.g. 3M-蓝色.
        var result = token.replace(escaped) { match -> values[match.value]?.ifEmpty { "\u0000" } ?: match.value }
        result = result.replace(Regex("[·\\-|,，\\s]*\\u0000[·\\-|,，\\s]*")) { match ->
            if (match.range.first == 0 || match.range.last == result.lastIndex) "" else
                separator.find(match.value)?.value?.take(1).orEmpty()
        }.replace("\u0000", "").replace('\u0001', '{').replace('\u0002', '}').trim()
        return limit(result)
    }
    fun productSuggestion(draft: BillDraft, template: String): String? = draft.productName?.let {
        if ("{商品}" in template) render(draft, template, includeProduct = true) else limit(it)
    }?.takeIf { it.isNotBlank() }
}
