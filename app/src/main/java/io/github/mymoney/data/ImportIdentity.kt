package io.github.mymoney.data

import androidx.room.*

/** Independent of bill deletion; this also serves as a tombstone. */
@Entity(tableName = "import_identities")
data class ImportIdentity(@PrimaryKey val sourceKey: String, val contentHash: String)

@Dao
interface ImportDao {
    @Query("SELECT * FROM import_identities ORDER BY sourceKey") suspend fun all(): List<ImportIdentity>
    @Insert suspend fun insert(value: ImportIdentity)
    @Query("DELETE FROM import_identities") suspend fun clear()
}

object ImportIdentityCodec {
    fun validate(rows: List<ImportIdentity>) {
        require(rows.size <= 200_000 && rows.map { it.sourceKey }.distinct().size == rows.size) { "导入身份数量超限或重复" }
        require(rows.all { it.sourceKey.matches(Regex("qianji:[0-9a-f]{64}")) && it.contentHash.matches(Regex("[0-9a-f]{64}")) }) { "导入身份无效" }
    }
    fun decode(raw: Any?): List<ImportIdentity> = (raw as? List<*> ?: error("导入身份必须为数组")).map {
        val m = it as? Map<*, *> ?: error("导入身份无效")
        ImportIdentity(m["key"] as? String ?: error("导入身份缺少key"), m["hash"] as? String ?: error("导入身份缺少hash"))
    }.also(::validate)
    fun merge(local: List<ImportIdentity>, incoming: List<ImportIdentity>): List<ImportIdentity> {
        validate(local); validate(incoming)
        val result = local.associateBy { it.sourceKey }.toMutableMap()
        incoming.forEach { row ->
            require(result[row.sourceKey]?.let { it == row } != false) { "钱迹导入身份冲突，本次未恢复" }
            result[row.sourceKey] = row
        }
        return result.values.toList().also(::validate)
    }
}
