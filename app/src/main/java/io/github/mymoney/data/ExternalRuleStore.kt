package io.github.mymoney.data

import android.content.Context
import com.example.billkit.ExternalPageRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Fully validate, atomically persist, then publish. Failed imports never replace live rules. */
object ExternalRuleStore {
    private var file: File? = null
    private var bundledText = """{"version":1,"rules":[],"categories":[],"accounts":[]}"""
    private var bundled = ExternalRuleCodec.decode(bundledText, RuleSource.JSON_BUNDLED)
    private var override: ExternalRuleDocument? = null
    private var overrideText: String? = null
    private val _summary = MutableStateFlow("随包规则 v1")
    val summary: StateFlow<String> = _summary

    @Synchronized fun init(context: Context) {
        initialize(context.filesDir, context.assets.open("rules.json").bufferedReader().use { it.readText() })
    }
    @Synchronized internal fun initialize(directory: File, defaults: String) {
        val destination = File(directory, "rules_override.json")
        if (file == destination) return
        val parsedDefaults = ExternalRuleCodec.decode(defaults, RuleSource.JSON_BUNDLED)
        file = destination
        bundledText = defaults
        bundled = parsedDefaults
        override = null; overrideText = null
        if (destination.exists()) runCatching {
            require(destination.length() <= ExternalRuleCodec.MAX_BYTES)
            val text = destination.readText()
            override = ExternalRuleCodec.decode(text)
            overrideText = text
        }
        publish()
    }
    @Synchronized fun importJson(text: String): Result<Unit> = runCatching {
        val parsed = ExternalRuleCodec.decode(text)
        val destination = file ?: error("规则尚未初始化")
        atomicText(destination, text)
        override = parsed; overrideText = text
        publish()
    }
    @Synchronized fun exportJson(): String = overrideText ?: bundledText
    @Synchronized fun reset() {
        file?.let { check(!it.exists() || it.delete()) { "无法删除覆盖文件" } }
        override = null; overrideText = null; publish()
    }
    @Synchronized fun candidates(): List<RuleCandidate> = (override?.candidates.orEmpty() + bundled.candidates).toList()
    private fun publish() {
        ExternalPageRules.install(override?.pages.orEmpty() + bundled.pages)
        _summary.value = if (override == null) "随包规则 v${bundled.version} · ${bundled.pages.size}条页面/${bundled.candidates.size}条关键词"
            else "本地覆盖 v${override!!.version} · ${override!!.pages.size}条页面/${override!!.candidates.size}条关键词（优先于随包）"
    }
}

internal fun atomicText(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        temp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } finally { temp.delete() }
}
