package io.github.mymoney.data

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
    @ColumnInfo(defaultValue = "0") val protectedFields: Long = 0L,
    val reviewOf: String? = null,
    val productName: String? = null,
    val productSource: String? = null,
    val noteOrigin: String? = null,
    val noteTemplateSnapshot: String? = null,
    val transferAccountId: String? = null,
) {
    fun toDraft(): BillDraft = BillDraft(priceCents = priceCents, type = BillType.valueOf(type),
        timeMillis = timeMillis, merchant = merchant, method = method, orderTail = orderTail,
        sourcePkg = sourcePkg, strategy = strategy, category = category, subCategory = subCategory,
        account = account, note = note, historyId = id, transactionKey = transactionKey,
        hasExplicitTime = hasExplicitTime, captureSessionId = captureSessionId,
        productName = productName, productSource = productSource, noteOrigin = noteOrigin, noteTemplateSnapshot = noteTemplateSnapshot, transferAccountId = transferAccountId)

    companion object {
        fun fromDraft(draft: BillDraft, identity: String = RecognitionIdentity.key(draft), at: Long = System.currentTimeMillis()) =
            RecognitionEntity(draft.historyId ?: UUID.randomUUID().toString(), identity,
                draft.priceCents, draft.type.name, draft.timeMillis, draft.merchant, draft.method,
                draft.orderTail, draft.sourcePkg, draft.strategy, draft.fingerprint, draft.category,
                draft.subCategory, draft.account, draft.note, draft.transactionKey, draft.hasExplicitTime,
                draft.captureSessionId, at, at, productName = draft.productName, productSource = draft.productSource,
                noteOrigin = draft.noteOrigin, noteTemplateSnapshot = draft.noteTemplateSnapshot, transferAccountId = draft.transferAccountId)
    }
}

object RecognitionIdentity {
    private fun digest(parts: List<Any?>): String = MessageDigest.getInstance("SHA-256")
        .digest(MiniJson.encode(parts).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    /**
     * 同一笔交易的稳定身份。**明确页面时间**时，用「应用 + 类型 + 金额 + 支付时间 + 付款方式」
     * 判定——这三样在支付宝/微信账单详情页永远可见且精确到秒，切页重绘、点「更多」展开订单号、
     * 商户文案微调都不会改变它们，因此同一笔在多次采集下会算出同一个 key，不会重复弹卡。
     *
     * 故意**不含 merchant 文本**：商户名是页面上最易抖的字段（多字/空格/重绘），一旦掺进哈希，
     * 同一笔切页回来就会被当成新交易重复弹卡。真正能唯一确定一笔的订单号走上面的 order: 分支。
     */
    fun key(draft: BillDraft): String = when {
        !draft.transactionKey.isNullOrBlank() -> "order:" + digest(listOf(draft.sourcePkg, draft.type.name, draft.transactionKey))
        draft.hasExplicitTime -> "time:" + digest(listOf(draft.sourcePkg, draft.type.name, draft.priceCents, draft.timeMillis, draft.method))
        !draft.captureSessionId.isNullOrBlank() -> "session:" + digest(listOf(draft.sourcePkg, draft.type.name, draft.priceCents, draft.captureSessionId))
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
    @Query("SELECT * FROM recognition_history WHERE sourcePkg = :pkg AND type = :type AND priceCents = :amount AND recognizedAt BETWEEN :from AND :to ORDER BY recognizedAt DESC, id")
    suspend fun captureCandidates(pkg: String, type: String, amount: Long, from: Long, to: Long): List<RecognitionEntity>
    @Query("UPDATE recognition_history SET reviewOf = NULL WHERE reviewOf IN (SELECT id FROM recognition_history WHERE status = 'IGNORED')")
    suspend fun unlinkIgnoredReviews()
    @Insert suspend fun insert(row: RecognitionEntity)
    @Update suspend fun update(row: RecognitionEntity)
    @Query("DELETE FROM recognition_history WHERE status = 'IGNORED'")
    suspend fun clearIgnored(): Int
    @Query("DELETE FROM recognition_history") suspend fun deleteAll()
}
