package com.example.autobook.data

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
            row.copy(status = RecognitionStatus.PENDING, updatedAt = System.currentTimeMillis()).also { db.recognitions().update(it) }.toDraft()
        }
    }
    suspend fun ignore(id: String) = change(id) { if (it.status == RecognitionStatus.RECORDED) it else it.copy(status = RecognitionStatus.IGNORED) }
    suspend fun preserve(draft: BillDraft) {
        val row = record(draft)
        change(row.id) { current ->
            if (current.status != RecognitionStatus.PENDING) current else RecognitionEntity.fromDraft(draft.copy(historyId = current.id), current.identityKey, current.recognizedAt)
                .copy(shareState = current.shareState)
        }
    }
    suspend fun markShare(id: String, launched: Boolean) = change(id) { it.copy(shareState = if (launched) RecognitionShareState.REQUESTED else RecognitionShareState.FAILED) }
    suspend fun clearIgnored(): Int = ensure().recognitions().clearIgnored()
    private suspend fun change(id: String, transform: (RecognitionEntity) -> RecognitionEntity) {
        val db = ensure()
        db.withTransaction { db.recognitions().byId(id)?.let { db.recognitions().update(transform(it).copy(updatedAt = System.currentTimeMillis())) } }
    }
}
