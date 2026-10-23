package com.example.autobook.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「导入时的备注」由这里控制。
 *
 * 模板占位符：`{商户}` `{支付方式}` `{分类}` `{账户}` `{时间}`
 * 默认 `{商户}`；也可以写死成"自动记账"之类。
 */
object NoteTemplate {

    val PRESETS = listOf("{商户}", "{商户}·{支付方式}", "{商户}·{分类}", "自动记账")

    fun render(draft: BillDraft, template: String = LedgerConfig.noteTemplate): String {
        if (template.isBlank()) return ""
        val values = mapOf(
            "{商户}" to (draft.merchant ?: ""),
            "{支付方式}" to (draft.method ?: ""),
            "{分类}" to (draft.category ?: ""),
            "{账户}" to (draft.account ?: ""),
            "{时间}" to SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(draft.timeMillis)),
        )

        var text = template
        values.forEach { (token, value) -> text = text.replace(token, value) }

        // 清掉占位符为空导致的悬空分隔符
        return text.replace(Regex("""[·\-|,，\s]+$"""), "")
            .replace(Regex("""[·\-|,，]\s*(?=[·\-|,，])"""), "")
            .trim()
            .take(60)
    }
}
