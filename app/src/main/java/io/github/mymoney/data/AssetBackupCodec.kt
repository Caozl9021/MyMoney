package io.github.mymoney.data

import java.util.UUID

/** Strict portable asset ledger codec. A malformed snapshot must never be partially restored. */
object AssetBackupCodec {
    private val kinds = setOf("CASH", "PAYMENT", "SAVINGS", "CREDIT", "OTHER_ASSET", "OTHER_DEBT")
    private val entryKinds = setOf("OPENING", "ADJUSTMENT", "BILL", "TRANSFER", "REVERSAL")

    fun encode(snapshot: AssetSnapshot): Map<String, Any?> {
        validate(snapshot)
        return linkedMapOf(
            "accounts" to snapshot.accounts.map { a -> linkedMapOf(
                "id" to a.id, "name" to a.name, "kind" to a.kind, "aliases" to MiniJson.decode(a.aliases),
                "sortOrder" to a.sortOrder, "archived" to a.archived, "included" to a.included,
                "initialized" to a.initialized, "createdAt" to a.createdAt, "updatedAt" to a.updatedAt,
            ) },
            "entries" to snapshot.entries.map { e -> linkedMapOf(
                "id" to e.id, "operationId" to e.operationId, "accountId" to e.accountId,
                "deltaCents" to e.deltaCents, "kind" to e.kind, "note" to e.note,
                "createdAt" to e.createdAt, "billKey" to e.billKey, "reversalOf" to e.reversalOf,
            ) },
        )
    }

    fun decode(raw: Any?): AssetSnapshot {
        val root = raw as? Map<*, *> ?: errorInvalid("资产段必须是对象")
        val accounts = root.list("accounts").map { item ->
            val a = item as? Map<*, *> ?: errorInvalid("资产账户必须是对象")
            val order = a.integer("sortOrder")
            require(order in 0..Int.MAX_VALUE.toLong()) { "账户排序无效" }
            val aliases = a.list("aliases").map { it as? String ?: errorInvalid("账户别名必须为文字") }
            AssetAccount(id = a.text("id"), name = a.text("name"), kind = a.text("kind"),
                aliases = MiniJson.encode(aliases), sortOrder = order.toInt(),
                archived = a.flag("archived"), included = a.flag("included"), initialized = a.flag("initialized"),
                createdAt = a.integer("createdAt"), updatedAt = a.integer("updatedAt"))
        }
        val entries = root.list("entries").map { item ->
            val e = item as? Map<*, *> ?: errorInvalid("资产流水必须是对象")
            AssetEntry(id = e.text("id"), operationId = e.text("operationId"), accountId = e.text("accountId"),
                deltaCents = e.integer("deltaCents"), kind = e.text("kind"), note = e.text("note", allowEmpty = true),
                createdAt = e.integer("createdAt"), billKey = e.optionalText("billKey"), reversalOf = e.optionalText("reversalOf"))
        }
        return AssetSnapshot(accounts, entries).also(::validate)
    }

    fun validate(snapshot: AssetSnapshot) {
        val accounts = snapshot.accounts.associateBy { it.id }
        require(accounts.size == snapshot.accounts.size) { "资产账户ID重复" }
        val names = HashSet<String>()
        snapshot.accounts.forEach { a ->
            require(a.id.isNotBlank() && a.name.isNotBlank() && a.name == a.name.trim()) { "资产账户身份或名称无效" }
            require(a.kind in kinds && a.sortOrder >= 0 && a.createdAt >= 0 && a.updatedAt >= a.createdAt) { "资产账户资料无效" }
            val aliases = (MiniJson.decode(a.aliases) as? List<*>)?.map {
                (it as? String)?.takeIf { name -> name.isNotBlank() && name == name.trim() }
                    ?: errorInvalid("账户别名无效")
            } ?: errorInvalid("账户别名必须是数组")
            (listOf(a.name) + aliases).distinct().forEach { require(names.add(it)) { "资产账户名称或别名冲突：$it" } }
        }
        val entries = snapshot.entries.associateBy { it.id }
        require(entries.size == snapshot.entries.size) { "资产流水ID重复" }
        val reversals = HashSet<String>()
        val totals = HashMap<String, Long>()
        snapshot.entries.forEach { e ->
            require(e.id.isNotBlank() && e.operationId.isNotBlank() && e.kind in entryKinds && e.createdAt >= 0) { "资产流水身份或类型无效" }
            val account = accounts[e.accountId] ?: errorInvalid("资产流水引用不存在的账户")
            require(account.initialized) { "未设置余额的账户含资产流水" }
            require(e.deltaCents != Long.MIN_VALUE) { "资产流水金额超出范围" }
            e.billKey?.let { require(it.isNotBlank()) { "资产流水账单身份无效" } }
            if (e.kind in setOf("BILL", "TRANSFER")) require(!e.billKey.isNullOrBlank()) { "账单流水缺少稳定身份" }
            e.reversalOf?.let { key ->
                require(e.kind == "REVERSAL" && key != e.id && reversals.add(key)) { "资产冲销身份无效或重复" }
                val original = entries[key] ?: errorInvalid("资产冲销引用不存在的流水")
                require(original.kind != "REVERSAL" && original.accountId == e.accountId && original.deltaCents == -e.deltaCents &&
                    original.billKey == e.billKey) { "资产冲销金额或账户不一致" }
            }
            if (e.kind == "REVERSAL") require(e.reversalOf != null) { "资产冲销缺少原流水" }
            try { totals[e.accountId] = Math.addExact(totals[e.accountId] ?: 0L, e.deltaCents) }
            catch (_: ArithmeticException) { errorInvalid("资产余额超出范围") }
        }
        require(totals.values.none { it == Long.MIN_VALUE }) { "资产余额超出范围" }
        try { AssetMath.summary(snapshot.accounts, snapshot.entries) }
        catch (_: ArithmeticException) { errorInvalid("资产汇总超出范围") }
    }

    /** Exact bootstrap shape only; caller must also rule out local business records in its transaction. */
    fun isUntouchedDefaultSnapshot(snapshot: AssetSnapshot, defaultNames: List<String>): Boolean {
        if (defaultNames.isEmpty() || snapshot.entries.isNotEmpty() || snapshot.accounts.size != defaultNames.size) return false
        val createdAt = snapshot.accounts.first().createdAt
        val expected = defaultNames.mapIndexed { index, name ->
            AssetAccount(
                id = UUID.nameUUIDFromBytes(("legacy-account:" + name).toByteArray(Charsets.UTF_8)).toString(),
                name = name, sortOrder = index, createdAt = createdAt, updatedAt = createdAt,
            )
        }
        return createdAt >= 0 && snapshot.accounts.toSet() == expected.toSet()
    }

    /** Merge only identical UUIDs; names are never used to guess identity across devices. */
    fun merge(local: AssetSnapshot, incoming: AssetSnapshot): AssetSnapshot {
        validate(local); validate(incoming)
        fun <T> mergeRows(left: List<T>, right: List<T>, key: (T) -> String): List<T> {
            val rows = left.associateBy(key).toMutableMap()
            right.forEach { row ->
                val old = rows[key(row)]
                require(old == null || old == row) { "资产UUID内容冲突，请核对后恢复；本次未写入" }
                rows[key(row)] = row
            }
            return rows.values.toList()
        }
        return AssetSnapshot(mergeRows(local.accounts, incoming.accounts) { it.id },
            mergeRows(local.entries, incoming.entries) { it.id }).also(::validate)
    }

    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: errorInvalid("资产${key}必须是数组")
    private fun Map<*, *>.flag(key: String): Boolean = this[key] as? Boolean ?: errorInvalid("资产${key}必须为布尔值")
    private fun Map<*, *>.text(key: String, allowEmpty: Boolean = false): String =
        (this[key] as? String)?.takeIf { allowEmpty || it.isNotBlank() } ?: errorInvalid("资产${key}必须为文字")
    private fun Map<*, *>.optionalText(key: String): String? = if (this[key] == null) null else text(key)
    private fun Map<*, *>.integer(key: String): Long {
        val raw = this[key] ?: errorInvalid("资产${key}缺失")
        // MiniJson parses integral JSON tokens as Long. Reject decimals and numeric strings.
        return when (raw) { is Long -> raw; is Int -> raw.toLong(); else -> errorInvalid("资产${key}必须为整数") }
    }
    private fun errorInvalid(message: String): Nothing = throw IllegalArgumentException(message)
}
