package io.github.mymoney.data

import androidx.room.*
import com.example.billkit.BillDraft
import java.security.MessageDigest

@Entity(tableName = "capture_aliases", foreignKeys = [ForeignKey(entity = RecognitionEntity::class,
    parentColumns = ["id"], childColumns = ["recognitionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recognitionId")])
data class CaptureAlias(@PrimaryKey val aliasKey: String, val recognitionId: String)

@Entity(tableName = "capture_evidence", foreignKeys = [ForeignKey(entity = RecognitionEntity::class,
    parentColumns = ["id"], childColumns = ["recognitionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recognitionId")])
data class CaptureEvidence(@PrimaryKey val eventId: String, val recognitionId: String?, val source: String,
    val packageName: String, val receivedAt: Long, val outcome: String, val detail: String)

@Entity(tableName = "capture_deliveries", foreignKeys = [ForeignKey(entity = RecognitionEntity::class,
    parentColumns = ["id"], childColumns = ["recognitionId"], onDelete = ForeignKey.CASCADE)])
data class CaptureDelivery(@PrimaryKey val recognitionId: String, val state: String, val attemptId: String, val updatedAt: Long)

object CaptureDeliveryState {
    const val CLAIMED = "CLAIMED"
    const val ACKNOWLEDGED = "ACKNOWLEDGED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
    const val RESTORED = "RESTORED"
    const val SUPPRESSED = "SUPPRESSED"
    val all = setOf(CLAIMED, ACKNOWLEDGED, FAILED, UNKNOWN, RESTORED, SUPPRESSED)
}

@Dao
interface CaptureDao {
    @Query("SELECT * FROM capture_aliases") suspend fun aliases(): List<CaptureAlias>
    @Query("SELECT * FROM capture_evidence ORDER BY receivedAt DESC LIMIT 2000") suspend fun evidence(): List<CaptureEvidence>
    @Query("SELECT * FROM capture_evidence WHERE recognitionId IS NOT NULL ORDER BY receivedAt DESC") suspend fun exportEvidence(): List<CaptureEvidence>
    @Query("SELECT * FROM capture_evidence WHERE eventId = :id") suspend fun evidenceById(id: String): CaptureEvidence?
    @Query("UPDATE capture_aliases SET recognitionId = :to WHERE recognitionId = :from") suspend fun moveAliases(from: String, to: String)
    @Query("UPDATE capture_evidence SET recognitionId = :to WHERE recognitionId = :from") suspend fun moveEvidence(from: String, to: String)
    @Query("SELECT * FROM capture_evidence WHERE source = 'NOTIFICATION' ORDER BY receivedAt DESC LIMIT 1") suspend fun latestNotification(): CaptureEvidence?
    @Query("SELECT * FROM capture_deliveries") suspend fun deliveries(): List<CaptureDelivery>
    @Query("SELECT * FROM capture_aliases WHERE aliasKey = :key") suspend fun alias(key: String): CaptureAlias?
    @Query("SELECT * FROM capture_deliveries WHERE recognitionId = :id") suspend fun delivery(id: String): CaptureDelivery?
    @Query("SELECT * FROM capture_evidence WHERE recognitionId = :id ORDER BY receivedAt DESC LIMIT 20") suspend fun forRecord(id: String): List<CaptureEvidence>
    @Query("SELECT DISTINCT source FROM capture_evidence WHERE recognitionId = :id")
    suspend fun sourcesForRecord(id: String): List<String>
    @Query("SELECT aliasKey FROM capture_aliases WHERE recognitionId = :id AND aliasKey LIKE 'transaction:%'")
    suspend fun transactionAliases(id: String): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putAlias(alias: CaptureAlias): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReviewSlot(alias: CaptureAlias)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putEvidence(evidence: CaptureEvidence): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDelivery(delivery: CaptureDelivery)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun claim(delivery: CaptureDelivery): Long
    @Query("DELETE FROM capture_aliases") suspend fun clearAliases()
    @Query("DELETE FROM capture_evidence") suspend fun clearEvidence()
    @Query("DELETE FROM capture_deliveries") suspend fun clearDeliveries()
    @Query("DELETE FROM capture_evidence WHERE recognitionId IS NULL AND eventId NOT IN (SELECT eventId FROM capture_evidence WHERE recognitionId IS NULL ORDER BY receivedAt DESC LIMIT 100)") suspend fun pruneDiagnostics()
    @Transaction suspend fun clearMetadata() { clearAliases(); clearEvidence(); clearDeliveries() }
}

/** Field provenance survives dismissal; active editor fields are protected individually. */
object CaptureFields {
    const val AMOUNT = 1L; const val TYPE = 2L; const val TIME = 4L
    const val MERCHANT = 8L; const val METHOD = 16L; const val CATEGORY = 32L
    const val SUBCATEGORY = 64L; const val ACCOUNT = 128L; const val NOTE = 256L
    const val ALL = 511L
    fun changed(old: BillDraft, new: BillDraft): Long {
        var flags = 0L
        if (old.priceCents != new.priceCents) flags = flags or AMOUNT
        if (old.type != new.type) flags = flags or TYPE
        if (old.timeMillis != new.timeMillis) flags = flags or TIME
        if (old.merchant != new.merchant) flags = flags or MERCHANT
        if (old.method != new.method) flags = flags or METHOD
        if (old.category != new.category) flags = flags or CATEGORY
        if (old.subCategory != new.subCategory) flags = flags or SUBCATEGORY
        if (old.account != new.account || old.transferAccountId != new.transferAccountId) flags = flags or ACCOUNT
        if (old.note != new.note || old.noteOrigin != new.noteOrigin) flags = flags or NOTE
        return flags
    }
}
object CaptureEditLocks {
    private val editors = mutableMapOf<String, Int>()
    private val touched = mutableMapOf<String, Long>()
    @Synchronized fun touch(id: String, fields: Long) { touched[id] = (touched[id] ?: 0L) or fields }
    @Synchronized fun fields(id: String): Long = touched[id] ?: 0L
    @Synchronized fun acquire(id: String) { editors[id] = (editors[id] ?: 0) + 1 }
    @Synchronized fun release(id: String) { val n = (editors[id] ?: 1) - 1; if (n <= 0) { editors.remove(id); touched.remove(id) } else editors[id] = n }
    @Synchronized fun contains(id: String) = (editors[id] ?: 0) > 0
    @Synchronized fun hasActiveEditor() = editors.isNotEmpty()
    @Synchronized fun activeIds(): Set<String> = editors.keys.toSet()
}

object CaptureMergePolicy {
    fun version(row: RecognitionEntity): String = digest(row.toString())
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun strongAlias(draft: BillDraft): String? = draft.transactionKey?.takeIf(String::isNotBlank)?.let {
        "transaction:" + digest(MiniJson.encode(listOf(draft.sourcePkg, draft.type.name, it)))
    }
    fun conflicts(a: BillDraft, b: BillDraft): Boolean = a.priceCents != b.priceCents || a.type != b.type ||
        (!a.transactionKey.isNullOrBlank() && !b.transactionKey.isNullOrBlank() && a.transactionKey != b.transactionKey) ||
        (a.hasExplicitTime && b.hasExplicitTime && a.timeMillis != b.timeMillis)
    fun enrich(row: RecognitionEntity, incoming: BillDraft, editing: Boolean, replaceAutomaticAccount: Boolean = false): RecognitionEntity {
        if (row.status != RecognitionStatus.PENDING) return row
        val protected = row.protectedFields or if (editing) CaptureEditLocks.fields(row.id) else 0L
        fun missing(old: String?, new: String?, flag: Long) =
            if (protected and flag != 0L) old else old?.takeIf(String::isNotBlank) ?: new
        val category = missing(row.category, incoming.category, CaptureFields.CATEGORY)
        val subCategory = if (category == incoming.category) missing(row.subCategory, incoming.subCategory, CaptureFields.SUBCATEGORY)
            else row.subCategory
        val upgradeNote = protected and CaptureFields.NOTE == 0L && NoteFillPolicy.mayUpgrade(row, incoming)
        val preserveNote = row.noteOrigin in setOf(NoteTemplate.USER, NoteTemplate.SUGGESTION, NoteFillPolicy.FIXED, NoteFillPolicy.DISABLED)
        return row.copy(
            merchant = missing(row.merchant, incoming.merchant, CaptureFields.MERCHANT),
            method = missing(row.method, incoming.method, CaptureFields.METHOD),
            category = category,
            subCategory = subCategory,
            account = if (replaceAutomaticAccount && protected and CaptureFields.ACCOUNT == 0L && !incoming.account.isNullOrBlank()) incoming.account
                else missing(row.account, incoming.account, CaptureFields.ACCOUNT),
            transferAccountId = missing(row.transferAccountId, incoming.transferAccountId, CaptureFields.ACCOUNT),
            note = if (preserveNote) row.note else if (upgradeNote) incoming.note else missing(row.note, incoming.note, CaptureFields.NOTE),
            noteOrigin = if (!preserveNote && (upgradeNote || row.note.isNullOrBlank() && protected and CaptureFields.NOTE == 0L)) incoming.noteOrigin ?: row.noteOrigin else row.noteOrigin,
            noteTemplateSnapshot = if (upgradeNote) incoming.noteTemplateSnapshot else row.noteTemplateSnapshot,
            productName = row.productName ?: incoming.productName,
            productSource = if (row.productName == null) incoming.productSource else row.productSource,
            orderTail = row.orderTail?.takeIf(String::isNotBlank) ?: incoming.orderTail,
            transactionKey = row.transactionKey ?: incoming.transactionKey,
            timeMillis = if (!row.hasExplicitTime && incoming.hasExplicitTime && protected and CaptureFields.TIME == 0L) incoming.timeMillis else row.timeMillis,
            hasExplicitTime = row.hasExplicitTime || (incoming.hasExplicitTime && protected and CaptureFields.TIME == 0L),
        )
    }
}

/** Merge a dismissed editor into newer evidence without treating stale defaults as human edits. */
object CaptureEditorPolicy {
    fun applyTouched(latest: BillDraft, edited: BillDraft): BillDraft {
        val flags = edited.captureTouchedFields
        fun has(field: Long) = flags and field != 0L
        return latest.copy(
            priceCents = if (has(CaptureFields.AMOUNT)) edited.priceCents else latest.priceCents,
            type = if (has(CaptureFields.TYPE)) edited.type else latest.type,
            timeMillis = if (has(CaptureFields.TIME)) edited.timeMillis else latest.timeMillis,
            merchant = if (has(CaptureFields.MERCHANT)) edited.merchant else latest.merchant,
            method = if (has(CaptureFields.METHOD)) edited.method else latest.method,
            category = if (has(CaptureFields.CATEGORY)) edited.category else latest.category,
            subCategory = if (has(CaptureFields.SUBCATEGORY)) edited.subCategory else latest.subCategory,
            account = if (has(CaptureFields.ACCOUNT)) edited.account else latest.account,
            transferAccountId = if (has(CaptureFields.ACCOUNT)) edited.transferAccountId else latest.transferAccountId,
            note = if (has(CaptureFields.NOTE)) edited.note else latest.note,
            noteOrigin = if (has(CaptureFields.NOTE)) edited.noteOrigin else latest.noteOrigin,
            noteTemplateSnapshot = if (has(CaptureFields.NOTE)) edited.noteTemplateSnapshot else latest.noteTemplateSnapshot,
        )
    }
}
