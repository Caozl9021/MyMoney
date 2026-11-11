package io.github.mymoney.data

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

// Kotlin String.isBlank whitespace, also used for statistics' fallback labels.
private const val GROUP_BLANK_CHARS = "char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)"
private const val CATEGORY_GROUP_SQL = "CASE WHEN trim(COALESCE(category, ''), " + GROUP_BLANK_CHARS + ") = '' THEN '未分类' ELSE category END"
private const val ACCOUNT_VALUE_SQL = "COALESCE((SELECT name FROM asset_accounts WHERE id = bills.accountId), account)"
private const val ACCOUNT_GROUP_SQL = "CASE WHEN trim(COALESCE(" + ACCOUNT_VALUE_SQL + ", ''), " + GROUP_BLANK_CHARS + ") = '' THEN '未知账户' ELSE " + ACCOUNT_VALUE_SQL + " END"

/** Kept in one constant so the visible window and complete totals cannot drift apart. */
private const val FILTERED_LEDGER_WHERE = """
    WHERE (COALESCE(merchant, '') LIKE :pattern ESCAPE '\' OR COALESCE(note, '') LIKE :pattern ESCAPE '\')
      AND timeMillis >= :from AND timeMillis < :to
      AND (:category IS NULL OR COALESCE(category, '') = :category)
      AND (:type IS NULL OR type = :type)
      AND (:statisticsCategory IS NULL OR (""" + CATEGORY_GROUP_SQL + """) = :statisticsCategory)
      AND (:statisticsAccount IS NULL OR (""" + ACCOUNT_GROUP_SQL + """) = :statisticsAccount)
      AND (:reimburse IS NULL OR reimburseStatus = :reimburse)
"""

/**
 * 本地的账单表。草稿模型（BillDraft/BillType）在 :billkit 库里，
 * 这里只负责"确认之后"的持久化。
 */
@Entity(
    tableName = "bills",
    indices = [Index("fingerprint"), Index("timeMillis"), Index(value = ["recognitionId"], unique = true), Index(value = ["syncId"], unique = true)],
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
    val recognitionId: String? = null,
    val accountId: String? = null,
    val transferAccountId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val assetLinked: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "''") val assetKey: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val transferFeeCents: Long = 0,
    val feeParentKey: String? = null,
    val transferAccount: String? = null,
    val productName: String? = null,
    val productSource: String? = null,
    val noteOrigin: String? = null,
    val noteTemplateSnapshot: String? = null,
    // W2 账本副本同步：稳定跨设备标识 + 更新时间 + 删除墓碑。syncId 不用自增 id/指纹/订单尾号。
    @androidx.room.ColumnInfo(defaultValue = "''") val syncId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "'NONE'") val reimburseStatus: String = Reimburse.NONE,
    val reimbursedAt: Long? = null,
    /** 报销退回记入的资产账户。空表示还没选；159 已报销的迁移成原支付账户。 */
    val reimburseAccountId: String? = null,
)

@Dao
interface BillDao {

    @Query("SELECT * FROM bills ORDER BY timeMillis DESC LIMIT 500")
    fun observeRecent(): Flow<List<BillEntity>>

    /** 完整明细的可扩展窗口；同一时间用主键保证稳定顺序。 */
    @Query("SELECT * FROM bills ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    fun observeWindow(limit: Int): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills " + FILTERED_LEDGER_WHERE + " ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    fun observeFilteredWindow(
        pattern: String, from: Long, to: Long, category: String?, type: String?, limit: Int,
        statisticsCategory: String? = null, statisticsAccount: String? = null, reimburse: String? = null,
    ): Flow<List<BillEntity>>

    @Query("""
        SELECT COUNT(*) AS count,
            COALESCE(SUM(CASE WHEN type = 'EXPENSE' AND COALESCE(reimburseStatus, 'NONE') != 'REIMBURSED' THEN priceCents ELSE 0 END), 0) AS expense,
            COALESCE(SUM(CASE WHEN type = 'INCOME' THEN priceCents ELSE 0 END), 0) AS income,
            COALESCE(SUM(CASE WHEN type = 'REFUND' THEN priceCents ELSE 0 END), 0) AS refund
        FROM bills
    """ + FILTERED_LEDGER_WHERE)
    fun observeFilteredSummary(
        pattern: String, from: Long, to: Long, category: String?, type: String?,
        statisticsCategory: String? = null, statisticsAccount: String? = null, reimburse: String? = null,
    ): Flow<BillQuerySummary>

    @Query("""
        SELECT COUNT(*) AS count, COALESCE(SUM(priceCents), 0) AS cents
        FROM bills
        WHERE type = 'EXPENSE' AND reimburseStatus = 'PENDING' AND feeParentKey IS NULL
    """)
    fun observePendingReimbursement(): Flow<PendingReimbursement>

    /** Includes historical categories and folds SQL NULL into the unclassified empty value. */
    @Query("SELECT DISTINCT COALESCE(category, '') FROM bills ORDER BY COALESCE(category, '')")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM bills")
    fun observeCount(): Flow<Int>

    /** 识别历史只核对身份集合，不受明细窗口或首页条数限制。 */
    @Query("SELECT id FROM bills ORDER BY id")
    fun observeIds(): Flow<List<Long>>

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: Long): Flow<BillEntity?>

    @Query("SELECT * FROM bills ORDER BY timeMillis DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int): List<BillEntity>

    /** 已出现过的支付方式（去重），用给账户匹配/调试 */
    @Query("SELECT DISTINCT method FROM bills WHERE method IS NOT NULL AND method != '' ORDER BY method")
    suspend fun distinctMethods(): List<String>

    /** 完整统计区间，左闭右开；实时更新且不受首页500笔窗口限制。 */
    @Query("SELECT * FROM bills WHERE timeMillis >= :from AND timeMillis < :to ORDER BY timeMillis DESC, id DESC")
    fun observeRange(from: Long, to: Long): Flow<List<BillEntity>>

    /** Home anchor and count use the exact same saved monthly period as its complete-day query. */
    @Query("SELECT * FROM bills WHERE timeMillis >= :from AND timeMillis < :to ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    fun observeHomeWindow(from: Long, to: Long, limit: Int): Flow<List<BillEntity>>

    @Query("SELECT COUNT(*) FROM bills WHERE timeMillis >= :from AND timeMillis < :to")
    fun observeHomeCount(from: Long, to: Long): Flow<Int>

    /** 首页完整自然日窗口：包含最老日零点及以后的全部账单，不能用行数截断日小计。 */
    @Query("SELECT * FROM bills WHERE timeMillis >= :from ORDER BY timeMillis DESC, id DESC")
    fun observeHomeFrom(from: Long): Flow<List<BillEntity>>

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

    /** Same predicate as countRecentDuplicate, returning a deterministic real identity. */
    @Query("""
        SELECT * FROM bills WHERE createdAt > :since AND type = :type
        AND (fingerprint = :fp OR (:tail IS NOT NULL AND sourcePkg = :pkg AND priceCents = :cents AND orderTail = :tail))
        ORDER BY id LIMIT 1
    """)
    suspend fun recentDuplicate(fp: String, pkg: String, cents: Long, type: String, tail: String?, since: Long): BillEntity?

    @Query("""
        SELECT * FROM bills WHERE recognitionId IS NULL AND createdAt > :since AND type = :type
        AND (fingerprint = :fp OR (:tail IS NOT NULL AND sourcePkg = :pkg AND priceCents = :cents AND orderTail = :tail))
        ORDER BY id LIMIT 1
    """)
    suspend fun legacyDuplicate(fp: String, pkg: String, cents: Long, type: String, tail: String?, since: Long): BillEntity?

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

    @Query("SELECT * FROM bills WHERE recognitionId = :id LIMIT 1")
    suspend fun byRecognitionId(id: String): BillEntity?

    /**
     * 记账前「查账本」用：[timeMillis] 有索引，先用 ±windowMs 时间窗把候选压到极小集合，
     * 再在内存里按账户模糊匹配（金额相等 + 类型相同）。LIMIT 兜底：个别时间字段脏或批量
     * 导入同秒大量记录时不至于把窗撑爆；超上限按时间最近取，够用户判断。
     */
    @Query("""
        SELECT * FROM bills
        WHERE priceCents = :cents AND type = :type
          AND timeMillis BETWEEN :lo AND :hi
        ORDER BY ABS(timeMillis - :center) ASC, id DESC
        LIMIT :limit
    """)
    suspend fun duplicateCandidates(cents: Long, type: String, lo: Long, hi: Long, center: Long, limit: Int): List<BillEntity>

    @Query("DELETE FROM bills")
    suspend fun deleteAll()

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- W2 账本副本同步 ----
    @Query("SELECT * FROM bills WHERE syncId = :syncId LIMIT 1")
    suspend fun bySyncId(syncId: String): BillEntity?

    @Query("SELECT * FROM bills")
    suspend fun listAllForSync(): List<BillEntity>

    /** 只更新同步身份/时间/墓碑，用于把本机旧账单补齐 syncId 或应用远端墓碑，不动业务字段。 */
    @Query("UPDATE bills SET syncId = :syncId, updatedAt = :updatedAt, deletedAt = :deletedAt WHERE id = :id")
    suspend fun updateSyncFields(id: Long, syncId: String, updatedAt: Long, deletedAt: Long?)

    /** 应用远端整条账单：按 syncId 定位，写全字段（id 保持本机自增不变）。 */
    @Query("UPDATE bills SET type = :type, priceCents = :priceCents, timeMillis = :timeMillis, merchant = :merchant, method = :method, orderTail = :orderTail, sourcePkg = :sourcePkg, strategy = :strategy, fingerprint = :fingerprint, createdAt = :createdAt, category = :category, subCategory = :subCategory, account = :account, note = :note, recognitionId = :recognitionId, accountId = :accountId, transferAccountId = :transferAccountId, assetLinked = :assetLinked, assetKey = :assetKey, transferFeeCents = :transferFeeCents, feeParentKey = :feeParentKey, transferAccount = :transferAccount, productName = :productName, productSource = :productSource, noteOrigin = :noteOrigin, noteTemplateSnapshot = :noteTemplateSnapshot, updatedAt = :updatedAt, deletedAt = :deletedAt, reimburseStatus = :reimburseStatus, reimbursedAt = :reimbursedAt, reimburseAccountId = :reimburseAccountId WHERE syncId = :syncId")
    suspend fun applyRemoteBySyncId(
        syncId: String, type: String, priceCents: Long, timeMillis: Long, merchant: String?, method: String?, orderTail: String?,
        sourcePkg: String, strategy: String, fingerprint: String, createdAt: Long, category: String?, subCategory: String?,
        account: String?, note: String?, recognitionId: String?, accountId: String?, transferAccountId: String?,
        assetLinked: Boolean, assetKey: String, transferFeeCents: Long, feeParentKey: String?, transferAccount: String?,
        productName: String?, productSource: String?, noteOrigin: String?, noteTemplateSnapshot: String?,
        updatedAt: Long, deletedAt: Long?, reimburseStatus: String, reimbursedAt: Long?, reimburseAccountId: String?,
    ): Int
}

@Database(entities = [BillEntity::class, RecognitionEntity::class, AssetAccount::class, AssetEntry::class, AssetMeta::class, CaptureAlias::class, CaptureEvidence::class, CaptureDelivery::class, ImportIdentity::class, RuleModelEntity::class, AppDataEntity::class, BillDeletionLog::class], version = 13, exportSchema = false)
abstract class AppDb : RoomDatabase() {

    abstract fun deletions(): BillDeletionDao
    abstract fun bills(): BillDao
    abstract fun recognitions(): RecognitionDao
    abstract fun assets(): AssetDao
    abstract fun capture(): CaptureDao
    abstract fun imports(): ImportDao
    abstract fun rules(): SpecRuleDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN recognitionId TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_recognitionId ON bills(recognitionId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS recognition_history (
                    id TEXT NOT NULL PRIMARY KEY, identityKey TEXT NOT NULL,
                    priceCents INTEGER NOT NULL, type TEXT NOT NULL, timeMillis INTEGER NOT NULL,
                    merchant TEXT, method TEXT, orderTail TEXT, sourcePkg TEXT NOT NULL,
                    strategy TEXT NOT NULL, fingerprint TEXT NOT NULL, category TEXT, subCategory TEXT,
                    account TEXT, note TEXT, transactionKey TEXT, hasExplicitTime INTEGER NOT NULL,
                    captureSessionId TEXT, recognizedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    status TEXT NOT NULL, billId INTEGER, shareState TEXT NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recognition_history_identityKey ON recognition_history(identityKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_history_recognizedAt ON recognition_history(recognizedAt)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN accountId TEXT")
                db.execSQL("ALTER TABLE bills ADD COLUMN transferAccountId TEXT")
                db.execSQL("ALTER TABLE bills ADD COLUMN assetLinked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN assetKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bills ADD COLUMN transferFeeCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN feeParentKey TEXT")
                db.execSQL("UPDATE bills SET assetKey = 'legacy-' || id")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_accounts (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
                    aliases TEXT NOT NULL, sortOrder INTEGER NOT NULL, archived INTEGER NOT NULL,
                    included INTEGER NOT NULL, initialized INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_asset_accounts_name ON asset_accounts(name)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_entries (
                    id TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, accountId TEXT NOT NULL,
                    deltaCents INTEGER NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    billKey TEXT, reversalOf TEXT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_entries_accountId ON asset_entries(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_entries_billKey ON asset_entries(billKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_entries_operationId ON asset_entries(operationId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_asset_entries_reversalOf ON asset_entries(reversalOf)")
                db.execSQL("CREATE TABLE IF NOT EXISTS asset_meta (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recognition_history ADD COLUMN protectedFields INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recognition_history ADD COLUMN reviewOf TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS capture_aliases (aliasKey TEXT NOT NULL PRIMARY KEY, recognitionId TEXT NOT NULL, FOREIGN KEY(recognitionId) REFERENCES recognition_history(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_aliases_recognitionId ON capture_aliases(recognitionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS capture_evidence (eventId TEXT NOT NULL PRIMARY KEY, recognitionId TEXT, source TEXT NOT NULL, packageName TEXT NOT NULL, receivedAt INTEGER NOT NULL, outcome TEXT NOT NULL, detail TEXT NOT NULL, FOREIGN KEY(recognitionId) REFERENCES recognition_history(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_evidence_recognitionId ON capture_evidence(recognitionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS capture_deliveries (recognitionId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, attemptId TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(recognitionId) REFERENCES recognition_history(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO capture_deliveries SELECT id, 'RESTORED', 'migration-4-5', updatedAt FROM recognition_history")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN transferAccount TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS import_identities (sourceKey TEXT NOT NULL PRIMARY KEY, contentHash TEXT NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("bills", "recognition_history")) {
                    for (column in listOf("productName", "productSource", "noteOrigin", "noteTemplateSnapshot")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN $column TEXT")
                    }
                }
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS rule_model (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, app TEXT NOT NULL, type TEXT NOT NULL,
                    js TEXT NOT NULL, name TEXT NOT NULL, systemRuleName TEXT NOT NULL,
                    creator TEXT NOT NULL, struct TEXT NOT NULL,
                    autoRecord INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1,
                    updateAt INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rule_model_app_type_enabled_id ON rule_model(app, type, enabled, id)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS app_data (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, data TEXT NOT NULL, type TEXT NOT NULL,
                    app TEXT NOT NULL, time INTEGER NOT NULL,
                    match INTEGER NOT NULL DEFAULT 0, rule TEXT NOT NULL,
                    issue INTEGER NOT NULL DEFAULT 0, version TEXT NOT NULL, image TEXT NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_data_time ON app_data(time)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_data_app_match ON app_data(app, match)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recognition_history ADD COLUMN transferAccountId TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS bill_deletion_log (
                    eventId TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL, origin TEXT NOT NULL,
                    principalId INTEGER NOT NULL, recognitionId TEXT, groupId TEXT NOT NULL,
                    snapshotVersion INTEGER NOT NULL, snapshotJson TEXT NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_deletion_log_deletedAt ON bill_deletion_log(deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_deletion_log_principalId ON bill_deletion_log(principalId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_deletion_log_recognitionId ON bill_deletion_log(recognitionId)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // W2 账本副本同步：账单加稳定 syncId/updatedAt/删除墓碑，资产条目加 updatedAt/删除墓碑。
                db.execSQL("ALTER TABLE bills ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bills ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN deletedAt INTEGER")
                db.execSQL("UPDATE bills SET syncId = 'legacy-' || id WHERE syncId = ''")
                db.execSQL("UPDATE bills SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_syncId ON bills(syncId)")
                db.execSQL("ALTER TABLE asset_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE asset_entries ADD COLUMN deletedAt INTEGER")
                db.execSQL("UPDATE asset_entries SET updatedAt = createdAt WHERE updatedAt = 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN reimburseStatus TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE bills ADD COLUMN reimbursedAt INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN reimburseAccountId TEXT")
                db.execSQL("UPDATE bills SET reimburseAccountId = accountId WHERE reimburseStatus = 'REIMBURSED' AND accountId IS NOT NULL")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "mymoney.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13).build().also { instance = it }
        }
    }
}
