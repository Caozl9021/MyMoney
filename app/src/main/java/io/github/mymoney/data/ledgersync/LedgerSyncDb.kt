package io.github.mymoney.data.ledgersync

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * W2 账本副本同步的独立库 ledger-sync.db。
 *
 * 与主库 mymoney.db（Room 11）分离：主库负责业务数据，本库只存 outbox / 状态 / 冲突。
 * 入队动作发生在主库事务里（见 BillWriter），通过 LedgerOutboxQueue 跨库写入；
 * 因为跨库无法共用同一 SQLite 事务，入队采用「业务提交后补写 outbox」+ 首同步全量对账兜底，
 * outbox 仅作前台快路径，真正的收敛靠 snapshot 差量，故丢失单条 enqueue 不会丢数据。
 */
object LedgerItemKind {
    const val BILL = "bill"
    const val ACCOUNT = "account"
    const val ENTRY = "entry"
}

@Entity(tableName = "ledger_outbox")
data class LedgerOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "item_kind") val itemKind: String,
    @ColumnInfo(name = "base_revision") val baseRevision: Long,
    @ColumnInfo(name = "request_json") val requestJson: String,
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_FLIGHT = "in_flight"
        const val STATUS_FAILED = "failed"
    }
}

@Entity(tableName = "ledger_conflicts")
data class LedgerConflictEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "item_kind") val itemKind: String,
    @ColumnInfo(name = "local_payload") val localPayload: String,
    @ColumnInfo(name = "remote_payload") val remotePayload: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long,
    @ColumnInfo(name = "remote_device") val remoteDevice: String? = null,
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "ledger_sync_state")
data class LedgerSyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "space_id") val spaceId: String? = null,
    @ColumnInfo(name = "ledger_id") val ledgerId: String? = null,
    val epoch: String? = null,
    val cursor: Long = 0,
    @ColumnInfo(name = "last_pulled_revision") val lastPulledRevision: Long = 0,
    @ColumnInfo(name = "initial_merge_done") val initialMergeDone: Boolean = false,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Dao
interface LedgerOutboxDao {
    @Query("SELECT * FROM ledger_outbox WHERE status = :status ORDER BY created_at, operation_id")
    suspend fun byStatus(status: String): List<LedgerOutboxEntity>

    @Query("SELECT * FROM ledger_outbox WHERE item_id = :itemId LIMIT 1")
    suspend fun byItem(itemId: String): LedgerOutboxEntity?

    @Query("SELECT COUNT(*) FROM ledger_outbox WHERE status = :status")
    suspend fun count(status: String): Int

    @Query("SELECT COUNT(*) FROM ledger_outbox")
    suspend fun total(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LedgerOutboxEntity)

    @Query("UPDATE ledger_outbox SET status = :status, last_error = :error WHERE operation_id = :operationId")
    suspend fun updateStatus(operationId: String, status: String, error: String? = null)

    @Query("UPDATE ledger_outbox SET base_revision = :baseRevision WHERE item_id = :itemId")
    suspend fun setBaseRevision(itemId: String, baseRevision: Long)

    @Query("DELETE FROM ledger_outbox WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)

    @Query("DELETE FROM ledger_outbox WHERE item_id = :itemId")
    suspend fun deleteByItem(itemId: String)

    @Query("DELETE FROM ledger_outbox")
    suspend fun clearAll()
}

@Dao
interface LedgerConflictDao {
    @Query("SELECT * FROM ledger_conflicts ORDER BY created_at, item_id")
    suspend fun all(): List<LedgerConflictEntity>

    @Query("SELECT COUNT(*) FROM ledger_conflicts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LedgerConflictEntity)

    @Query("DELETE FROM ledger_conflicts WHERE item_id = :itemId")
    suspend fun delete(itemId: String)

    @Query("DELETE FROM ledger_conflicts")
    suspend fun clearAll()
}

@Dao
interface LedgerSyncStateDao {
    @Query("SELECT * FROM ledger_sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): LedgerSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LedgerSyncStateEntity)

    @Query("UPDATE ledger_sync_state SET cursor = :cursor, last_pulled_revision = :lastPulledRevision, last_sync_at = :lastSyncAt WHERE id = 1")
    suspend fun updateCursor(cursor: Long, lastPulledRevision: Long, lastSyncAt: Long)

    @Query("UPDATE ledger_sync_state SET initial_merge_done = :done WHERE id = 1")
    suspend fun setInitialMergeDone(done: Boolean)
}

@Database(
    entities = [LedgerOutboxEntity::class, LedgerConflictEntity::class, LedgerSyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LedgerSyncDb : RoomDatabase() {
    abstract fun outbox(): LedgerOutboxDao
    abstract fun conflicts(): LedgerConflictDao
    abstract fun state(): LedgerSyncStateDao

    companion object {
        @Volatile private var instance: LedgerSyncDb? = null

        fun get(context: Context): LedgerSyncDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerSyncDb::class.java,
                "ledger-sync.db",
            ).build().also { instance = it }
        }
    }
}
