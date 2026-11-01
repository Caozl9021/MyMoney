package io.github.mymoney.data.rulesync

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** The four synced rule kinds in the local rule-sync database. */
object RuleSyncKind {
    const val RECOGNITION_RULE = "recognition_rule"
    const val MERCHANT_NOTE_RULE = "merchant_note_rule"
    const val OFFICIAL_RULE_SWITCH = "official_rule_switch"
    const val NOTE_DEFAULTS = "note_defaults"
    val all = setOf(RECOGNITION_RULE, MERCHANT_NOTE_RULE, OFFICIAL_RULE_SWITCH, NOTE_DEFAULTS)
}

@Entity(
    tableName = "local_rule_items",
    indices = [Index(value = ["kind", "natural_key"]), Index(value = ["kind", "deleted_at"])],
)
data class LocalRuleItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    val payload: String,
    @ColumnInfo(name = "natural_key") val naturalKey: String? = null,
    @ColumnInfo(name = "sort_key") val sortKey: Long = 0,
    @ColumnInfo(name = "server_revision") val serverRevision: Long = 0,
    @ColumnInfo(name = "content_updated_at") val contentUpdatedAt: Long? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "local_generation") val localGeneration: Long = 0,
)

@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
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

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "local_payload") val localPayload: String,
    @ColumnInfo(name = "remote_payload") val remotePayload: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long,
    @ColumnInfo(name = "remote_device") val remoteDevice: String? = null,
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "space_id") val spaceId: String? = null,
    val epoch: String? = null,
    val cursor: Long = 0,
    @ColumnInfo(name = "last_pulled_revision") val lastPulledRevision: Long = 0,
    @ColumnInfo(name = "migration_completed") val migrationCompleted: Boolean = false,
    @ColumnInfo(name = "desired_generation") val desiredGeneration: Long = 0,
    @ColumnInfo(name = "active_generation") val activeGeneration: Long = 0,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Dao
interface LocalRuleDao {
    @Query("SELECT * FROM local_rule_items WHERE deleted_at IS NULL ORDER BY kind, sort_key, id")
    suspend fun activeItems(): List<LocalRuleItemEntity>

    @Query("SELECT * FROM local_rule_items ORDER BY kind, sort_key, id")
    suspend fun allItems(): List<LocalRuleItemEntity>

    @Query("SELECT * FROM local_rule_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LocalRuleItemEntity?

    @Query("SELECT * FROM local_rule_items WHERE kind = :kind AND natural_key = :naturalKey AND deleted_at IS NULL LIMIT 1")
    suspend fun findActiveByNaturalKey(kind: String, naturalKey: String): LocalRuleItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocalRuleItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalRuleItemEntity>)

    @Query("UPDATE local_rule_items SET server_revision = :revision, content_updated_at = :contentUpdatedAt WHERE id = :id")
    suspend fun updateServerRevision(id: String, revision: Long, contentUpdatedAt: Long?)

    @Query("UPDATE local_rule_items SET payload = :payload, natural_key = :naturalKey, sort_key = :sortKey, server_revision = :revision, content_updated_at = :contentUpdatedAt, deleted_at = :deletedAt, local_generation = :generation WHERE id = :id")
    suspend fun applyRemote(id: String, payload: String, naturalKey: String?, sortKey: Long, revision: Long, contentUpdatedAt: Long?, deletedAt: Long?, generation: Long)

    @Query("UPDATE local_rule_items SET server_revision = 0")
    suspend fun resetServerRevisions()
}

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox WHERE status = :status ORDER BY created_at, operation_id")
    suspend fun byStatus(status: String): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = :status")
    suspend fun count(status: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncOutboxEntity)

    @Query("UPDATE sync_outbox SET status = :status, last_error = :error WHERE operation_id = :operationId")
    suspend fun updateStatus(operationId: String, status: String, error: String? = null)

    @Query("DELETE FROM sync_outbox WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)

    @Query("DELETE FROM sync_outbox WHERE item_id = :itemId")
    suspend fun deleteByItem(itemId: String)

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun total(): Int

    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}

@Dao
interface SyncConflictDao {
    @Query("SELECT * FROM sync_conflicts ORDER BY created_at, item_id")
    suspend fun all(): List<SyncConflictEntity>

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE item_id = :itemId")
    suspend fun delete(itemId: String)

    @Query("DELETE FROM sync_conflicts")
    suspend fun clearAll()
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET cursor = :cursor, last_pulled_revision = :lastPulledRevision, last_sync_at = :lastSyncAt WHERE id = 1")
    suspend fun updateCursor(cursor: Long, lastPulledRevision: Long, lastSyncAt: Long)
}

@Database(
    entities = [LocalRuleItemEntity::class, SyncOutboxEntity::class, SyncConflictEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RuleSyncDb : RoomDatabase() {
    abstract fun rules(): LocalRuleDao
    abstract fun outbox(): SyncOutboxDao
    abstract fun conflicts(): SyncConflictDao
    abstract fun state(): SyncStateDao

    companion object {
        @Volatile private var instance: RuleSyncDb? = null

        fun get(context: Context): RuleSyncDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RuleSyncDb::class.java,
                "rule-sync.db",
            ).build().also { instance = it }
        }
    }
}
