package com.example.autobook.data

import androidx.room.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID

object RecognitionStatus {
    const val PENDING = "PENDING"
    const val RECORDED = "RECORDED"
    const val IGNORED = "IGNORED"
}
object RecognitionShareState {
    const val NOT_REQUESTED = "NOT_REQUESTED"
    const val REQUESTED = "REQUESTED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "recognition_history", indices = [Index(value = ["identityKey"], unique = true), Index("recognizedAt")])
data class RecognitionEntity(
    @PrimaryKey val id: String,
    val identityKey: String,
    val priceCents: Long,
    val type: String,
    val timeMillis: Long,
    val merchant: String?,
    val method: String?,
    val orderTail: String?,
    val sourcePkg: String,
    val strategy: String,
    val fingerprint: String,
    val category: String?,
    val subCategory: String?,
    val account: String?,
    val note: String?,
    val transactionKey: String?,
    val hasExplicitTime: Boolean,
    val captureSessionId: String?,
    val recognizedAt: Long,
    val updatedAt: Long,
    val status: String = RecognitionStatus.PENDING,
    val billId: Long? = null,
    val shareState: String = RecognitionShareState.NOT_REQUESTED,
) {
    fun toDraft(): BillDraft = BillDraft(priceCents = priceCents, type = BillType.valueOf(type),
        timeMillis = timeMillis, merchant = merchant, method = method, orderTail = orderTail,
        sourcePkg = sourcePkg, strategy = strategy, category = category, subCategory = subCategory,
        account = account, note = note, historyId = id, transactionKey = transactionKey,
        hasExplicitTime = hasExplicitTime, captureSessionId = captureSessionId)

    companion object {
        fun fromDraft(draft: BillDraft, identity: String = RecognitionIdentity.key(draft), at: Long = System.currentTimeMillis()) =
            RecognitionEntity(draft.historyId ?: UUID.randomUUID().toString(), identity,
                draft.priceCents, draft.type.name, draft.timeMillis, draft.merchant, draft.method,
                draft.orderTail, draft.sourcePkg, draft.strategy, draft.fingerprint, draft.category,
                draft.subCategory, draft.account, draft.note, draft.transactionKey, draft.hasExplicitTime,
                draft.captureSessionId, at, at)
    }
}

object RecognitionIdentity {
    private fun digest(parts: List<Any?>): String = MessageDigest.getInstance("SHA-256")
        .digest(MiniJson.encode(parts).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun key(draft: BillDraft): String = when {
        !draft.transactionKey.isNullOrBlank() -> "order:" + digest(listOf(draft.sourcePkg, draft.type.name, draft.transactionKey))
        draft.hasExplicitTime -> "time:" + digest(listOf(draft.sourcePkg, draft.type.name, draft.priceCents, draft.timeMillis, draft.merchant, draft.method))
        !draft.captureSessionId.isNullOrBlank() -> "session:" + digest(listOf(draft.sourcePkg, draft.type.name, draft.captureSessionId))
        else -> "legacy:" + digest(listOf(draft.fingerprint, draft.type.name))
    }
}

@Dao
interface RecognitionDao {
    @Query("SELECT * FROM recognition_history ORDER BY recognizedAt DESC, id")
    fun observeAll(): Flow<List<RecognitionEntity>>
    @Query("SELECT * FROM recognition_history ORDER BY recognizedAt DESC, id")
    suspend fun allOnce(): List<RecognitionEntity>
    @Query("SELECT * FROM recognition_history WHERE id = :id")
    suspend fun byId(id: String): RecognitionEntity?
    @Query("SELECT * FROM recognition_history WHERE identityKey = :key")
    suspend fun byIdentity(key: String): RecognitionEntity?
    @Insert suspend fun insert(row: RecognitionEntity)
    @Update suspend fun update(row: RecognitionEntity)
    @Query("DELETE FROM recognition_history WHERE status = 'IGNORED'")
    suspend fun clearIgnored(): Int
    @Query("DELETE FROM recognition_history") suspend fun deleteAll()
}
