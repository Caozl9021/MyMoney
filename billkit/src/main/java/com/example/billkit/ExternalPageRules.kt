package com.example.billkit

/** Literal contains expressions; `|` means alternatives, never a regular expression. */
data class ExternalPageRule(
    val packages: Set<String>, val kind: PageKind,
    val require: List<String>, val anyOf: List<List<String>> = emptyList(),
    val exclude: List<String> = emptyList(), val strategy: String,
) {
    fun matches(snapshot: UiSnapshot): Boolean {
        fun has(expression: String) = expression.split('|').any { word -> snapshot.items.any { word in it } }
        return snapshot.packageName in packages && require.all(::has) &&
            anyOf.all { group -> group.any(::has) } && exclude.none(::has)
    }
}

/** Host injects already validated immutable rules; billkit never reads files or owns a Context. */
object ExternalPageRules {
    @Volatile private var rules: List<ExternalPageRule> = emptyList()
    fun install(rules: List<ExternalPageRule>) { this.rules = rules.toList() }
    fun match(snapshot: UiSnapshot): PageMatch? = rules.firstOrNull { it.matches(snapshot) }
        ?.let { PageMatch(it.kind, it.strategy) }
}
