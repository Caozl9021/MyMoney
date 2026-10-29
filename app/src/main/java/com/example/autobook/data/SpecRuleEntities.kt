package com.example.autobook.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 本地规则表。系统包更新只替换系统行，不删除用户行或原始诊断。 */
@Entity(
    tableName = "rule_model",
    indices = [Index(value = ["app", "type", "enabled", "id"])],
)
data class RuleModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val app: String,
    val type: String,
    val js: String,
    val name: String,
    val systemRuleName: String,
    val creator: String = "system",
    val struct: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val autoRecord: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val updateAt: Long = 0,
)

/**
 * `app_data`：每一条到达的数据都先落库，再调规则（SPEC §7）。
 *
 * 顺序不能反：规则命中与否、规则抛没抛异常、金额是否被 isSane 拦下，
 * 都要能回溯到「当时喂给规则的原始字符串」。这是排查漏报的唯一事实来源。
 */
@Entity(
    tableName = "app_data",
    indices = [Index("time"), Index(value = ["app", "match"])],
)
data class AppDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val data: String,
    val type: String,
    val app: String,
    val time: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val match: Boolean = false,
    val rule: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val issue: Boolean = false,
    val version: String = "",
    val image: String = "",
)

@Dao
interface SpecRuleDao {
    @Query("SELECT * FROM rule_model ORDER BY id ASC")
    suspend fun allRules(): List<RuleModelEntity>

    @Query("DELETE FROM rule_model WHERE creator IN ('system', 'maimi')")
    suspend fun clearBundledRules()

    @Query("SELECT * FROM rule_model WHERE enabled = 1 ORDER BY id ASC")
    suspend fun enabledRules(): List<RuleModelEntity>

    @Query("SELECT COUNT(*) FROM rule_model")
    suspend fun ruleCount(): Int

    @Query("SELECT COUNT(*) FROM rule_model WHERE type = :type")
    suspend fun ruleCountByType(type: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<RuleModelEntity>)

    @Query("DELETE FROM rule_model")
    suspend fun clearRules()

    @Query("SELECT * FROM app_data ORDER BY time DESC LIMIT :limit")
    suspend fun recentData(limit: Int): List<AppDataEntity>

    @Query("SELECT * FROM app_data WHERE app = :app AND match = 0 ORDER BY time DESC LIMIT :limit")
    suspend fun recentMisses(app: String, limit: Int): List<AppDataEntity>

    @Query("SELECT * FROM app_data WHERE app = :app AND type = 'DATA' AND time >= :since AND time <= :until AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun correlationData(app: String, since: Long, until: Long, afterId: Long, limit: Int): List<AppDataEntity>

    @Query("SELECT * FROM app_data WHERE id = :id")
    suspend fun dataById(id: Long): AppDataEntity?

    @Insert
    suspend fun insertData(row: AppDataEntity): Long

    @Query("UPDATE app_data SET match = 1, rule = :rule WHERE id = :id")
    suspend fun markMatched(id: Long, rule: String)

    @Query("UPDATE app_data SET issue = 1 WHERE id = :id")
    suspend fun markIssue(id: Long)

    @Query("DELETE FROM app_data WHERE time < :before")
    suspend fun pruneData(before: Long)
}
