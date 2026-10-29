package com.example.autobook.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * `rule_model`：规则 JS 的唯一来源（SPEC §7）。
 *
 * 为什么规则要落库而不是只读 assets：
 * - 规则会被热更（用户导入新版 rules_all.json），不能每次发版；
 * - 命中记录要能引用 `systemRuleName` 回溯到具体那条规则；
 * - 163 条 × 平均 2KB 的正则如果每次启动都从 JSON 解析一遍，冷启动会明显变慢。
 *
 * `js` 存原始 `var rule_N=function(...)...;` 文本，引擎侧再剥前缀。
 */
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
