package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.github.mymoney.data.NoteTemplate
import com.example.billkit.BillDraft
import com.example.billkit.BillType

class NoteTemplateRuntimeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var failure: Throwable? = null
        val results = mutableListOf<String>()
        try {
            val sample = BillDraft(2350, BillType.EXPENSE, 0, "咖啡店", null, null, "demo", "test", productName = "拿铁")
            check(NoteTemplate.unknownTokens("{商户}·{商品}").isEmpty())
            check(NoteTemplate.unknownTokens("{未知}") == listOf("{未知}"))
            check(NoteTemplate.unknownTokens("{{商品}}").isEmpty())
            results += "android_icu_token_regex: passed"
            check(NoteTemplate.render(sample, "{商户}·{商品}") == "咖啡店")
            check(NoteTemplate.render(sample, "{商户}·{商品}", includeProduct = true) == "咖啡店·拿铁")
            check(NoteTemplate.render(sample, "{{商品}}") == "{商品}")
            results += "android_icu_render_and_escape: passed"
            NoteTemplate.PRESETS.forEach { NoteTemplate.render(sample, it) }
            results += "all_eight_presets_android_runtime: passed"
        } catch (t: Throwable) { failure = t }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("results", results.joinToString("\n")); putString("error", failure?.stackTraceToString())
        })
    }
}
