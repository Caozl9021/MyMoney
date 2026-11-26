package io.github.mymoney.data

/** Pure preference codec: absent/invalid falls back; a valid [] stays empty. */
internal object LedgerListCodec {
    fun readGroups(raw: String?, fallback: List<CategoryGroup>): List<CategoryGroup> =
        if (raw == null) fallback else runCatching {
            val list = MiniJson.decode(raw) as? List<*> ?: error("Expected array")
            list.map { item ->
                val obj = item as? Map<*, *> ?: error("Expected group")
                val name = (obj["n"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: error("Missing name")
                val subs = if (obj.containsKey("s")) strings(obj["s"]) else emptyList()
                val iconKey = (obj["i"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val colorHex = (obj["c"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                CategoryGroup(name, subs, iconKey, colorHex)
            }.distinctBy { it.name }
        }.getOrDefault(fallback)

    fun readStrings(raw: String?, fallback: List<String>): List<String> =
        if (raw == null) fallback else runCatching { strings(MiniJson.decode(raw)) }.getOrDefault(fallback)

    private fun strings(raw: Any?): List<String> = (raw as? List<*> ?: error("Expected array"))
        .map { (it as? String)?.trim()?.takeIf(String::isNotEmpty) ?: error("Invalid name") }.distinct()

    fun encodeGroups(groups: List<CategoryGroup>): String = MiniJson.encode(groups
        .map {
            it.copy(
                name = it.name.trim(),
                subs = it.subs.map(String::trim).filter(String::isNotEmpty).distinct(),
                iconKey = it.iconKey?.trim()?.takeIf(String::isNotEmpty),
                colorHex = it.colorHex?.trim()?.takeIf(String::isNotEmpty),
            )
        }
        .filter { it.name.isNotEmpty() }.distinctBy { it.name }
        .map { g ->
            linkedMapOf<String, Any?>("n" to g.name, "s" to g.subs).apply {
                g.iconKey?.let { put("i", it) }
                g.colorHex?.let { put("c", it) }
            }
        })

    fun encodeStrings(list: List<String>): String = MiniJson.encode(list.map(String::trim).filter(String::isNotEmpty).distinct())
}
