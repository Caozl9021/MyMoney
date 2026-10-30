package io.github.mymoney.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode

@Entity(tableName = "asset_accounts", indices = [Index(value = ["name"], unique = true)])
data class AssetAccount(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String = "PAYMENT",
    val aliases: String = "[]",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val included: Boolean = true,
    val initialized: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "asset_entries", indices = [Index("accountId"), Index("billKey"), Index("operationId"), Index(value = ["reversalOf"], unique = true)])
data class AssetEntry(
    @PrimaryKey val id: String,
    val operationId: String,
    val accountId: String,
    val deltaCents: Long,
    val kind: String,
    val note: String,
    val createdAt: Long,
    val billKey: String? = null,
    val reversalOf: String? = null,
)

@Entity(tableName = "asset_meta")
data class AssetMeta(@PrimaryKey val key: String, val value: String)

data class AssetSnapshot(val accounts: List<AssetAccount>, val entries: List<AssetEntry>)
data class AssetSummary(val assets: Long, val debts: Long, val net: Long, val unknown: Int)

@Dao
interface AssetDao {
    @Query("SELECT * FROM asset_accounts ORDER BY sortOrder, createdAt, id") fun observeAccounts(): Flow<List<AssetAccount>>
    @Query("SELECT * FROM asset_entries ORDER BY createdAt, id") fun observeEntries(): Flow<List<AssetEntry>>
    @Query("SELECT * FROM asset_accounts ORDER BY sortOrder, createdAt, id") suspend fun accounts(): List<AssetAccount>
    @Query("SELECT * FROM asset_entries ORDER BY createdAt, id") suspend fun entries(): List<AssetEntry>
    @Query("SELECT * FROM asset_accounts WHERE id=:id") suspend fun account(id: String): AssetAccount?
    @Upsert suspend fun upsertAccount(account: AssetAccount)
    @Insert suspend fun insertEntry(entry: AssetEntry)
    @Query("SELECT * FROM asset_entries WHERE billKey=:key ORDER BY createdAt, id") suspend fun entriesForBill(key: String): List<AssetEntry>
    @Query("DELETE FROM asset_accounts WHERE id=:id") suspend fun deleteAccount(id: String)
    @Query("SELECT MAX(createdAt) FROM asset_entries") suspend fun latestEntryTime(): Long?
    @Query("DELETE FROM asset_entries") suspend fun clearEntries()
    @Query("DELETE FROM asset_accounts") suspend fun clearAccounts()
    @Query("SELECT value FROM asset_meta WHERE `key`=:key") suspend fun meta(key: String): String?
    @Upsert suspend fun putMeta(meta: AssetMeta)
}

/** Pure accounting rules shared by the UI, writes, backup validation and tests. */
object AssetMath {
    val kinds = listOf("CASH", "PAYMENT", "SAVINGS", "CREDIT", "OTHER_ASSET", "OTHER_DEBT")
    fun isDebt(kind: String) = kind == "CREDIT" || kind == "OTHER_DEBT"
    fun parseCents(text: String): Long {
        require(text.trim().matches(Regex("[+-]?\\d+(\\.\\d+)?"))) { "请输入有效金额" }
        return try { BigDecimal(text.trim()).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }
        catch (_: ArithmeticException) { throw IllegalArgumentException("金额超出范围") }
    }
    fun delta(type: String, cents: Long): Long {
        require(cents > 0) { "金额必须大于0" }
        return when (type) {
            "EXPENSE" -> Math.negateExact(cents)
            "INCOME", "REFUND" -> cents
            "TRANSFER" -> 0L
            else -> throw IllegalArgumentException("账单类型无效")
        }
    }
    fun balance(accountId: String, entries: List<AssetEntry>): Long = entries.asSequence()
        .filter { it.accountId == accountId }.fold(0L) { sum, entry -> Math.addExact(sum, entry.deltaCents) }
    fun summary(accounts: List<AssetAccount>, entries: List<AssetEntry>): AssetSummary {
        var assets = 0L; var debts = 0L; var unknown = 0
        accounts.filter { it.included }.forEach { account ->
            if (!account.initialized) unknown++ else {
                val position = balance(account.id, entries)
                if (position >= 0) assets = Math.addExact(assets, position)
                else debts = Math.addExact(debts, Math.negateExact(position))
            }
        }
        return AssetSummary(assets, debts, Math.subtractExact(assets, debts), unknown)
    }
    fun aliases(account: AssetAccount): List<String> =
        (MiniJson.decode(account.aliases) as? List<*>)?.map {
            require(it is String && it.isNotBlank()) { "账户别名无效" }; it
        } ?: throw IllegalArgumentException("账户别名无效")

    fun canonicalName(name: String?, candidates: List<String>, accounts: List<AssetAccount>): String? {
        if (name == null) return null
        if (name in candidates) return name
        return accounts.filter { !it.archived && it.name in candidates && name in aliases(it) }.singleOrNull()?.name
    }

    fun validate(snapshot: AssetSnapshot) {
        val accounts = snapshot.accounts
        require(accounts.map { it.id }.distinct().size == accounts.size) { "资产账户ID重复" }
        require(accounts.map { it.name }.distinct().size == accounts.size) { "资产账户名称重复" }
        val names = HashSet<String>()
        accounts.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.name == it.name.trim() && it.kind in kinds && it.sortOrder >= 0 && it.createdAt >= 0 && it.updatedAt >= it.createdAt) { "资产账户无效" }
            (listOf(it.name) + aliases(it).onEach { alias -> require(alias == alias.trim()) { "账户别名含首尾空格" } }).distinct().forEach { name -> require(names.add(name)) { "账户名或别名冲突：$name" } }
        }
        val ids = accounts.map { it.id }.toSet()
        val entries = snapshot.entries
        require(entries.map { it.id }.distinct().size == entries.size) { "资产流水ID重复" }
        val byId = entries.associateBy { it.id }
        val reversed = HashSet<String>()
        entries.forEach { e ->
            require(e.id.isNotBlank() && e.operationId.isNotBlank() && e.accountId in ids) { "资产流水引用无效" }
            require(e.kind in listOf("OPENING", "ADJUSTMENT", "BILL", "TRANSFER", "REVERSAL")) { "资产流水类型无效" }
            require(e.deltaCents != Long.MIN_VALUE) { "资产金额超出范围" }
            e.reversalOf?.let { target ->
                val original = byId[target] ?: error("资产冲销引用不存在")
                require(e.kind == "REVERSAL" && original.kind != "REVERSAL" && original.accountId == e.accountId &&
                    original.billKey == e.billKey && original.deltaCents == Math.negateExact(e.deltaCents) && reversed.add(target)) { "资产冲销不匹配" }
            }
            require(e.kind != "REVERSAL" || e.reversalOf != null) { "资产冲销缺少引用" }
            require(accounts.first { it.id == e.accountId }.initialized) { "未设置余额的账户含流水" }
        }
        accounts.forEach { require(balance(it.id, entries) != Long.MIN_VALUE) { "账户余额超出范围" } }
        summary(accounts, entries)
    }
}
