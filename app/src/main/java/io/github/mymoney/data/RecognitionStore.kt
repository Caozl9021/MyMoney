package io.github.mymoney.data

import android.content.Context
import androidx.room.withTransaction
import com.example.billkit.BillDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable source of truth. Initialization and legacy migration never use the main thread. */
object RecognitionStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = Mutex()
    private lateinit var context: Context
    private var collection: Job? = null
    private val mutableRecords = MutableStateFlow<List<RecognitionEntity>>(emptyList())
    val records: StateFlow<List<RecognitionEntity>> = mutableRecords.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun init(context: Context) {
        this.context = context.applicationContext
        scope.launch { runCatching { ensure() }.onFailure { mutableError.value = it.message ?: "历史初始化失败" } }
    }
    private suspend fun ensure(): AppDb = withContext(Dispatchers.IO) {
        check(::context.isInitialized) { "RecognitionStore.init(context) 未调用" }
        initialization.withLock {
            val db = AppDb.get(context)
            if (!mutableReady.value) {
                PendingStore.init(context)
                val legacy = PendingStore.readForMigration()
                db.withTransaction {
                    legacy.forEach { draft ->
                        val key = RecognitionIdentity.key(draft)
                        if (db.recognitions().byIdentity(key) == null) db.recognitions().insert(RecognitionEntity.fromDraft(draft, key))
                    }
                }
                PendingStore.clearAfterMigration()
                mutableRecords.value = db.recognitions().allOnce()
                mutableReady.value = true
                mutableError.value = null
                collection = scope.launch { db.recognitions().observeAll().collect { mutableRecords.value = it } }
            }
            db
        }
    }
    suspend fun awaitReady() { ensure() }

    suspend fun record(draft: BillDraft): RecognitionEntity {
        val db = ensure()
        return db.withTransaction {
            draft.historyId?.let { db.recognitions().byId(it) } ?: run {
                val key = RecognitionIdentity.key(draft)
                db.recognitions().byIdentity(key) ?: RecognitionEntity.fromDraft(draft, key).also { db.recognitions().insert(it) }
            }
        }
    }
    suspend fun find(id: String): RecognitionEntity? = ensure().recognitions().byId(id)
    suspend fun reopen(id: String): BillDraft? {
        val db = ensure()
        return db.withTransaction {
            val row = db.recognitions().byId(id) ?: return@withTransaction null
            if (row.status == RecognitionStatus.RECORDED) return@withTransaction null
            require(row.reviewOf == null || row.status == RecognitionStatus.IGNORED) { "请先核对这条记录是否为独立交易" }
            row.copy(status = RecognitionStatus.PENDING, updatedAt = System.currentTimeMillis()).also { db.recognitions().update(it) }.toDraft()
        }
    }
    suspend fun ignore(id: String) = change(id) { if (it.status == RecognitionStatus.RECORDED) it else it.copy(status = RecognitionStatus.IGNORED) }
    suspend fun preserve(draft: BillDraft) {
        val row = record(draft)
        change(row.id) { current ->
            if (current.status != RecognitionStatus.PENDING) current else RecognitionEntity.fromDraft(draft.copy(historyId = current.id), current.identityKey, current.recognizedAt)
                .copy(shareState = current.shareState, reviewOf = current.reviewOf,
                    protectedFields = current.protectedFields or CaptureFields.changed(current.toDraft(), draft))
        }
    }
    suspend fun markShare(id: String, launched: Boolean) = change(id) { it.copy(shareState = if (launched) RecognitionShareState.REQUESTED else RecognitionShareState.FAILED) }
    suspend fun resolveSeparate(id: String): BillDraft? {
        val db = ensure()
        return db.withTransaction {
            val row = db.recognitions().byId(id) ?: return@withTransaction null
            if (row.status != RecognitionStatus.PENDING) return@withTransaction null
            row.copy(reviewOf = null, protectedFields = CaptureFields.ALL, updatedAt = System.currentTimeMillis())
                .also { db.recognitions().update(it) }.toDraft()
        }
    }
    suspend fun mergeReview(id: String) {
        val db = ensure()
        db.withTransaction {
            val row = db.recognitions().byId(id) ?: error("记录不存在")
            val parent = row.reviewOf?.let { db.recognitions().byId(it) } ?: error("待核对的原记录不存在")
            require(row.status == RecognitionStatus.PENDING) { "该记录已处理" }
            require(!CaptureMergePolicy.conflicts(row.toDraft(), parent.toDraft())) { "金额、类型或交易身份不同，请作为独立账单核对" }
            CaptureMergePolicy.strongAlias(row.toDraft())?.let { alias ->
                val existing = db.capture().alias(alias)
                require(existing == null || existing.recognitionId in setOf(parent.id, row.id)) { "该交易身份已属于另一条记录，请先核对" }
                if (existing == null) db.capture().putAlias(CaptureAlias(alias, parent.id))
            }
            val enriched = CaptureMergePolicy.enrich(parent, row.toDraft(), CaptureEditLocks.contains(parent.id))
            if (enriched != parent) db.recognitions().update(enriched.copy(updatedAt = System.currentTimeMillis()))
            db.capture().moveAliases(row.id, parent.id)
            db.capture().moveEvidence(row.id, parent.id)
            db.recognitions().update(row.copy(status = RecognitionStatus.IGNORED, updatedAt = System.currentTimeMillis()))
        }
    }
    suspend fun clearIgnored(): Int {
        val db = ensure()
        return db.withTransaction { db.recognitions().unlinkIgnoredReviews(); db.recognitions().clearIgnored() }
    }
    private suspend fun change(id: String, transform: (RecognitionEntity) -> RecognitionEntity) {
        val db = ensure()
        db.withTransaction { db.recognitions().byId(id)?.let { db.recognitions().update(transform(it).copy(updatedAt = System.currentTimeMillis())) } }
    }
}
