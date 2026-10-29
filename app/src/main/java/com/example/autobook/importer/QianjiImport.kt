package com.example.autobook.importer

import com.example.autobook.data.*
import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.UUID

/** Preview and commit use the same classifier; commit rechecks under a Room transaction. */
object QianjiImport {
    enum class State { READY, DUPLICATE, CONFLICT, SKIPPED }
    data class Item(val row: QianjiXlsx.Row, val state: State, val reason: String, val identity: ImportIdentity?)
    data class Preview(val document: QianjiXlsx.Document, val items: List<Item>) {
        fun count(state: State) = items.count { it.state == state }
    }
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun identity(row: QianjiXlsx.Row, zone: String) = ImportIdentity(
        "qianji:" + hash(row.cells["ID"].orEmpty().trim()),
        hash(MiniJson.encode(row.cells.filterKeys { it != "账本" }.toSortedMap()) + "|" + zone),
    )
    fun preview(document: QianjiXlsx.Document, existing: List<ImportIdentity>, fingerprints: Set<String> = emptySet()): Preview {
        val known = existing.associateBy { it.sourceKey }
        val fileIdentities = document.rows.filter { !it.cells["ID"].isNullOrBlank() }.map { identity(it, document.zone) }
        val conflicts = fileIdentities.groupBy { it.sourceKey }.filterValues { rows -> rows.map { it.contentHash }.distinct().size > 1 }.keys
        val seen = hashSetOf<String>()
        return Preview(document, document.rows.map { row ->
            when (val mapped = row.result) {
                is QianjiRow.Result.Skipped -> Item(row, State.SKIPPED, mapped.reason, null)
                is QianjiRow.Result.Accepted -> {
                    val id = identity(row, document.zone)
                    val old = known[id.sourceKey]
                    val state = when {
                        id.sourceKey in conflicts || (old != null && old != id) -> State.CONFLICT
                        old != null || id.sourceKey in fingerprints || !seen.add(id.sourceKey) -> State.DUPLICATE
                        else -> State.READY
                    }
                    Item(row, state, when (state) {
                        State.READY -> "可导入"
                        State.DUPLICATE -> "已导入或文件内重复（含已删除记录）"
                        else -> "同一钱迹 ID 内容不同，未覆盖"
                    }, id)
                }
            }
        })
    }
    suspend fun previewDatabase(db: AppDb, document: QianjiXlsx.Document): Preview = db.withTransaction {
        preview(document, db.imports().all(), db.bills().allOnce().map { it.fingerprint }.toSet())
    }
    suspend fun commit(db: AppDb, document: QianjiXlsx.Document): Preview = db.withTransaction {
        val report = previewDatabase(db, document)
        require(db.imports().all().size + report.count(State.READY) <= 200_000) { "历史导入身份已达容量上限，本次未写入" }
        val accounts = db.assets().accounts()
        fun account(name: String?): String? = accounts.filter { !it.archived && (it.name == name || name in AssetMath.aliases(it)) }.singleOrNull()?.id
        val now = System.currentTimeMillis()
        report.items.filter { it.state == State.READY }.forEach { item ->
            val r = (item.row.result as QianjiRow.Result.Accepted).record
            val id = requireNotNull(item.identity)
            var from = account(r.account)
            var to = if (r.type == "TRANSFER") account(r.transferAccount) else null
            if (from == to && from != null) { from = null; to = null }
            db.bills().insert(BillEntity(priceCents = r.cents, type = r.type, timeMillis = r.timeMillis,
                merchant = null, method = null, orderTail = null, sourcePkg = "qianji", strategy = "qianji-xlsx",
                fingerprint = id.sourceKey, createdAt = now, category = r.category, subCategory = r.subCategory,
                account = r.account, note = r.note, transferAccount = r.transferAccount,
                accountId = from, transferAccountId = to, assetLinked = false, assetKey = UUID.randomUUID().toString()))
            db.imports().insert(id)
        }
        report
    }
}
