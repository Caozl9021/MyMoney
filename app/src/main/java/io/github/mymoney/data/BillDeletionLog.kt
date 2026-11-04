package io.github.mymoney.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Immutable deletion evidence; deliberately independent of the live bill's lifetime. */
@Entity(tableName = "bill_deletion_log", indices = [Index("deletedAt"), Index("principalId"), Index("recognitionId")])
data class BillDeletionLog(
    @PrimaryKey val eventId: String,
    val deletedAt: Long,
    val origin: String,
    val principalId: Long,
    val recognitionId: String?,
    val groupId: String,
    val snapshotVersion: Int,
    val snapshotJson: String,
)

@Dao
interface BillDeletionDao {
    @Query("SELECT * FROM bill_deletion_log ORDER BY deletedAt DESC, eventId") fun observeAll(): Flow<List<BillDeletionLog>>
    @Query("SELECT * FROM bill_deletion_log ORDER BY deletedAt DESC, eventId") suspend fun allOnce(): List<BillDeletionLog>
    @Query("SELECT * FROM bill_deletion_log WHERE eventId=:eventId") suspend fun byId(eventId: String): BillDeletionLog?
    @Query("SELECT * FROM bill_deletion_log WHERE principalId=:id ORDER BY deletedAt DESC, eventId LIMIT 1") suspend fun latestForPrincipal(id: Long): BillDeletionLog?
    @Insert suspend fun insert(event: BillDeletionLog)
}

/** A self-contained, versioned bill group: decoding never restores a bill or asset posting. */
object BillDeletionCodec {
    private const val MAX_SNAPSHOT_BYTES = 1024 * 1024

    fun create(rows: List<BillEntity>, origin: String, at: Long = System.currentTimeMillis(), eventId: String = UUID.randomUUID().toString()): BillDeletionLog {
        require(rows.isNotEmpty()) { "删除快照缺少主账单" }
        return BillDeletionLog(eventId, at, origin, rows.first().id, rows.first().recognitionId,
            rows.first().assetKey.ifBlank { "bill:${rows.first().id}" }, 1,
            MiniJson.encode(mapOf("bills" to rows.map(::billMap)))).also(::validate)
    }

    fun encode(log: BillDeletionLog): Map<String, Any?> {
        validate(log)
        return linkedMapOf("eventId" to log.eventId, "deletedAt" to log.deletedAt, "origin" to log.origin,
            "principalId" to log.principalId, "recognitionId" to log.recognitionId, "groupId" to log.groupId,
            "snapshotVersion" to log.snapshotVersion, "snapshotJson" to log.snapshotJson)
    }

    fun decode(raw: Any?): BillDeletionLog {
        val map = raw as? Map<*, *> ?: error("删除记录必须为对象")
        return BillDeletionLog(map.text("eventId"), map.integer("deletedAt"), map.text("origin"),
            map.integer("principalId"), map.optionalText("recognitionId"), map.text("groupId"),
            map.integer("snapshotVersion").also { require(it == 1L) { "删除快照版本不支持" } }.toInt(),
            map.text("snapshotJson")).also(::validate)
    }

    fun bills(log: BillDeletionLog): List<BillEntity> {
        require(log.snapshotVersion == 1) { "删除快照版本不支持" }
        require(log.snapshotJson.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "删除快照超过大小限制" }
        val root = MiniJson.decode(log.snapshotJson) as? Map<*, *> ?: error("删除快照必须为对象")
        val rows = root["bills"] as? List<*> ?: error("删除快照缺少账单数组")
        require(rows.isNotEmpty() && rows.size <= 100) { "删除账单组大小无效" }
        return rows.map { raw -> bill(raw as? Map<*, *> ?: error("删除快照账单必须为对象")) }
    }

    fun validate(log: BillDeletionLog) {
        require(runCatching { UUID.fromString(log.eventId).toString() == log.eventId }.getOrDefault(false)) { "删除事件编号无效" }
        require(log.deletedAt > 0 && log.principalId > 0 && log.origin.matches(Regex("[A-Za-z0-9_-]{1,64}")) && log.groupId.isNotBlank()) { "删除记录元数据无效" }
        require(log.recognitionId == null || log.recognitionId.isNotBlank()) { "删除记录识别编号无效" }
        val rows = bills(log)
        val principal = rows.first()
        require(rows.map { it.id }.distinct().size == rows.size && rows.all { it.id > 0 }) { "删除快照账单编号无效" }
        require(principal.id == log.principalId && principal.recognitionId == log.recognitionId && principal.feeParentKey == null) { "删除记录主账单不一致" }
        require(log.groupId == principal.assetKey.ifBlank { "bill:${principal.id}" }) { "删除记录组编号不一致" }
        require(rows.drop(1).all { principal.type == "TRANSFER" && principal.assetKey.isNotBlank() && it.feeParentKey == principal.assetKey && it.type == "EXPENSE" }) { "删除快照手续费关联无效" }
        val fees = rows.drop(1)
        if (principal.type != "TRANSFER") require(principal.transferFeeCents == 0L && fees.isEmpty()) { "普通账单不能含转账手续费" }
        else if (principal.transferFeeCents == 0L) require(fees.isEmpty()) { "零手续费转账不能含费用账单" }
        else require(fees.size == 1 && fees.single().priceCents == principal.transferFeeCents && fees.single().accountId == principal.accountId) { "删除快照手续费不完整" }

    }

    private fun billMap(b: BillEntity): Map<String, Any?> = linkedMapOf(
        "id" to b.id, "priceCents" to b.priceCents, "type" to b.type, "timeMillis" to b.timeMillis,
        "merchant" to b.merchant, "method" to b.method, "orderTail" to b.orderTail,
        "sourcePkg" to b.sourcePkg, "strategy" to b.strategy, "fingerprint" to b.fingerprint,
        "createdAt" to b.createdAt, "category" to b.category, "subCategory" to b.subCategory,
        "account" to b.account, "note" to b.note, "recognitionId" to b.recognitionId,
        "accountId" to b.accountId, "transferAccountId" to b.transferAccountId,
        "assetLinked" to b.assetLinked, "assetKey" to b.assetKey, "transferFeeCents" to b.transferFeeCents,
        "feeParentKey" to b.feeParentKey, "transferAccount" to b.transferAccount,
        "productName" to b.productName, "productSource" to b.productSource,
        "noteOrigin" to b.noteOrigin, "noteTemplateSnapshot" to b.noteTemplateSnapshot,
    )

    private fun bill(m: Map<*, *>): BillEntity = BillEntity(
        id = m.integer("id"), priceCents = m.integer("priceCents").also { require(it > 0) { "删除快照金额无效" } },
        type = m.text("type").also { require(it in setOf("EXPENSE", "INCOME", "REFUND", "TRANSFER")) { "删除快照类型无效" } },
        timeMillis = m.integer("timeMillis"), merchant = m.optionalText("merchant", true), method = m.optionalText("method", true),
        orderTail = m.optionalText("orderTail", true), sourcePkg = m.text("sourcePkg", true), strategy = m.text("strategy", true),
        fingerprint = m.text("fingerprint", true), createdAt = m.integer("createdAt"), category = m.optionalText("category", true),
        subCategory = m.optionalText("subCategory", true), account = m.optionalText("account", true), note = m.optionalText("note", true),
        recognitionId = m.optionalText("recognitionId"), accountId = m.optionalText("accountId"), transferAccountId = m.optionalText("transferAccountId"),
        assetLinked = m["assetLinked"] as? Boolean ?: error("删除快照余额标记无效"), assetKey = m.text("assetKey", true),
        transferFeeCents = m.integer("transferFeeCents").also { require(it >= 0) { "删除快照手续费无效" } },
        feeParentKey = m.optionalText("feeParentKey"), transferAccount = m.optionalText("transferAccount", true),
        productName = m.optionalText("productName", true), productSource = m.optionalText("productSource", true),
        noteOrigin = m.optionalText("noteOrigin", true), noteTemplateSnapshot = m.optionalText("noteTemplateSnapshot", true),
    )

    private fun Map<*, *>.text(key: String, allowEmpty: Boolean = false): String =
        (this[key] as? String)?.takeIf { allowEmpty || it.isNotBlank() } ?: error("删除记录${key}必须为文字")
    private fun Map<*, *>.optionalText(key: String, allowEmpty: Boolean = false): String? =
        if (this[key] == null) null else text(key, allowEmpty)
    private fun Map<*, *>.integer(key: String): Long = when (val raw = this[key]) {
        is Long -> raw
        is Int -> raw.toLong()
        else -> error("删除记录${key}必须为整数")
    }
}
