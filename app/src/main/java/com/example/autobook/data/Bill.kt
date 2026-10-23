package com.example.autobook.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 本地的账单表。草稿模型（BillDraft/BillType）在 :billkit 库里，
 * 这里只负责"确认之后"的持久化。
 */
@Entity(
    tableName = "bills",
    indices = [Index("fingerprint"), Index("timeMillis")],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val priceCents: Long,
    val type: String,
    val timeMillis: Long,
    val merchant: String?,
    val method: String?,
    val orderTail: String?,
    val sourcePkg: String,
    val strategy: String,
    val fingerprint: String,
    val createdAt: Long,
    val category: String? = null,
    val subCategory: String? = null,
    val account: String? = null,
    val note: String? = null,
)

@Dao
interface BillDao {

    @Query("SELECT * FROM bills ORDER BY timeMillis DESC LIMIT 500")
    fun observeRecent(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills ORDER BY timeMillis DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int): List<BillEntity>

    /** 已出现过的支付方式（去重），用给账户匹配/调试 */
    @Query("SELECT DISTINCT method FROM bills WHERE method IS NOT NULL AND method != '' ORDER BY method")
    suspend fun distinctMethods(): List<String>

    /** 某月（毫秒区间）内的账单，用给月度统计 */
    @Query("SELECT * FROM bills WHERE timeMillis >= :from AND timeMillis < :to ORDER BY timeMillis DESC")
    suspend fun range(from: Long, to: Long): List<BillEntity>

    @Query("SELECT COUNT(*) FROM bills WHERE fingerprint = :fp AND createdAt > :since")
    suspend fun countRecentFingerprint(fp: String, since: Long): Int

    /**
     * 重复判定（落库前用）。比 [countRecentFingerprint] 多一条：有单号尾号时，
     * 按「包名 + 金额 + 单号尾号」判定并**忽略时间**。
     *
     * 原因：指纹里的分钟取自页面时间，同一笔账单跨分钟被抓两次（或进程被杀后
     * 用户翻回同一页）会得到两个不同指纹，只比指纹会漏。单号尾号是强标识。
     */
    @Query(
        """
        SELECT COUNT(*) FROM bills
        WHERE createdAt > :since
          AND type = :type
          AND (
            fingerprint = :fp
            OR (:tail IS NOT NULL AND sourcePkg = :pkg AND priceCents = :cents AND orderTail = :tail)
          )
        """
    )
    suspend fun countRecentDuplicate(
        fp: String,
        pkg: String,
        cents: Long,
        type: String,
        tail: String?,
        since: Long,
    ): Int

    @Insert
    suspend fun insert(entity: BillEntity): Long

    @Update
    suspend fun update(entity: BillEntity): Int

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun byId(id: Long): BillEntity?

    /** 撤销只尝试恢复原 id；冲突时绝不覆盖其他记录。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restoreDeleted(entity: BillEntity): Long

    /** 备份用：一次取全量（不含 Flow，避免恢复时和界面互相打架） */
    @Query("SELECT * FROM bills ORDER BY timeMillis ASC")
    suspend fun allOnce(): List<BillEntity>

    /** 恢复用：批量插入（合并模式），返回新 id；重复指纹由调用方先过滤 */
    @Insert
    suspend fun insertAll(entities: List<BillEntity>): List<Long>

    @Query("SELECT * FROM bills WHERE fingerprint = :fp LIMIT 1")
    suspend fun byFingerprint(fp: String): BillEntity?

    @Query("DELETE FROM bills")
    suspend fun deleteAll()

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [BillEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {

    abstract fun bills(): BillDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        /** v1 → v2：加分类/账户/备注三列（不丢旧数据） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE bills ADD COLUMN subCategory TEXT")
                db.execSQL("ALTER TABLE bills ADD COLUMN account TEXT")
                db.execSQL("ALTER TABLE bills ADD COLUMN note TEXT")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "autobook.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
