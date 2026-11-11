package io.github.mymoney.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Room is authoritative. The synchronous candidate API is a compatibility projection only. */
object AssetStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDb
    private val _accounts = MutableStateFlow<List<AssetAccount>>(emptyList())
    private val _entries = MutableStateFlow<List<AssetEntry>>(emptyList())
    private val _ready = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    val accounts = _accounts.asStateFlow()
    val entries = _entries.asStateFlow()
    val ready = _ready.asStateFlow()
    val error = _error.asStateFlow()
    @Volatile private var started = false

    @Synchronized fun init(context: Context) {
        if (started) return
        started = true
        db = AppDb.get(context.applicationContext)
        val legacy = LedgerConfig.legacyAccounts
        scope.launch {
            try {
                migrateAccounts(db, legacy)
                ensureCatchAllWallet(db)
                openUnsetPlaceholdersAsZero(db)
                refresh()
                _ready.value = true
                launch { db.assets().observeAccounts().collect { _accounts.value = it } }
                launch { db.assets().observeEntries().collect { _entries.value = it } }
            } catch (e: Exception) { _error.value = e.message ?: "账户迁移失败" }
        }
    }
    suspend fun awaitReady() {
        check(started) { "账户仓库尚未初始化" }
        combine(ready, error) { ready, error -> ready to error }.first { it.first || it.second != null }
            .second?.let { error(it) }
    }
    suspend fun refresh() {
        val snapshot = db.withTransaction { AssetSnapshot(db.assets().accounts(), db.assets().entries()) }
        _accounts.value = snapshot.accounts; _entries.value = snapshot.entries
    }
    fun candidateNames(): List<String>? = if (ready.value) _accounts.value.filterNot { it.archived }.map { it.name } else null

    /**
     * 按展示列表的新顺序重写未归档账户的 [AssetAccount.sortOrder]。
     * [orderedDisplayedIds] 可以是全量未归档，也可以是资产页「已设置」子集；
     * 未出现在该列表中的未归档账户保持相对槽位，资产页与账户管理 / 确认卡共用。
     */
    fun reorderActive(orderedDisplayedIds: List<String>) = runBlocking(Dispatchers.IO) {
        awaitReady()
        if (orderedDisplayedIds.size <= 1) return@runBlocking
        require(orderedDisplayedIds.distinct().size == orderedDisplayedIds.size) { "排序账户重复" }
        db.withTransaction {
            val all = db.assets().accounts()
            val byId = all.associateBy { it.id }
            orderedDisplayedIds.forEach { id ->
                val row = byId[id] ?: error("账户不存在")
                require(!row.archived) { "账户已归档" }
            }
            val active = all.filter { !it.archived }
            val displayedSet = orderedDisplayedIds.toSet()
            val slots = active.mapIndexedNotNull { index, account ->
                if (account.id in displayedSet) index else null
            }
            require(slots.size == orderedDisplayedIds.size) { "排序账户不一致" }
            val merged = active.toMutableList()
            slots.forEachIndexed { i, slot -> merged[slot] = byId.getValue(orderedDisplayedIds[i]) }
            val now = System.currentTimeMillis()
            merged.forEachIndexed { index, account ->
                if (account.sortOrder != index) {
                    db.assets().upsertAccount(account.copy(sortOrder = index, updatedAt = now))
                }
            }
        }
        refresh()
    }

    fun moveAmongActive(displayedIds: List<String>, from: Int, to: Int) {
        if (from !in displayedIds.indices || to !in displayedIds.indices || from == to) return
        reorderActive(Reorder.move(displayedIds, from, to))
    }

    fun changeCandidates(names: List<String>, restoreArchived: Boolean = false) = runBlocking(Dispatchers.IO) {
        awaitReady()
        db.withTransaction {
            val normalized = names.map(String::trim).filter(String::isNotEmpty).distinct()
            val old = db.assets().accounts()
            old.filter { !it.archived && it.name !in normalized }.forEach {
                db.assets().upsertAccount(it.copy(archived = true, updatedAt = System.currentTimeMillis()))
            }
            normalized.forEachIndexed { index, name ->
                val existing = old.firstOrNull { it.name == name }
                require(existing == null || !existing.archived || restoreArchived) { "同名账户已归档，请在资产中恢复" }
                if (existing != null) db.assets().upsertAccount(existing.copy(sortOrder = index, archived = false))
                else createInTransaction(db, name, "PAYMENT", index)
            }
        }
        refresh()
    }
    suspend fun createAccount(name: String, kind: String): String = withContext(Dispatchers.IO) {
        awaitReady()
        val id = db.withTransaction { createInTransaction(db, name, kind, db.assets().accounts().size) }
        refresh(); id
    }
    suspend fun createAccountWithDetails(name: String, kind: String, aliases: String, included: Boolean, sortOrder: Int? = null): String = withContext(Dispatchers.IO) {
        awaitReady()
        val id = db.withTransaction {
            val id = createInTransaction(db, name, kind, db.assets().accounts().size)
            val created = db.assets().account(id)!!
            val updated = created.copy(aliases = aliases, included = included, sortOrder = sortOrder ?: created.sortOrder)
            AssetMath.validate(AssetSnapshot(db.assets().accounts().map { if (it.id == id) updated else it }, db.assets().entries()))
            db.assets().upsertAccount(updated)
            id
        }
        refresh(); id
    }

    /**
     * 向导开通账户：写入即已设置。空余额视为头寸 0，登记 OPENING。
     * [positionCents] 为净资产口径（信用欠款为负）。
     */
    suspend fun openAccount(
        name: String,
        kind: String,
        aliases: String = "[]",
        included: Boolean = true,
        positionCents: Long = 0L,
        reason: String = "开通账户",
    ): String = withContext(Dispatchers.IO) {
        awaitReady()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入账户名称" }
        require(reason.isNotBlank()) { "请填写开通原因" }
        require(positionCents != Long.MIN_VALUE) { "金额超出范围" }
        val id = db.withTransaction {
            val existing = db.assets().accounts().firstOrNull { it.name == trimmed }
            require(existing == null || existing.archived) { "已有同名账户「$trimmed」" }
            val now = System.currentTimeMillis()
            val accountId = if (existing != null) {
                val restored = existing.copy(
                    kind = kind,
                    aliases = aliases,
                    included = included,
                    archived = false,
                    updatedAt = now,
                )
                db.assets().upsertAccount(restored)
                if (!existing.initialized) {
                    db.assets().upsertAccount(restored.copy(initialized = true, updatedAt = now))
                    db.assets().insertEntry(
                        AssetEntry(
                            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                            existing.id, positionCents, "OPENING", reason.trim(), nextEntryTime(db),
                        ),
                    )
                }
                // 已开通的归档账户：仅恢复，不在此改余额（避免静默冲账）。
                existing.id
            } else {
                val createdId = createInTransaction(db, trimmed, kind, db.assets().accounts().size)
                val created = db.assets().account(createdId)!!
                db.assets().upsertAccount(
                    created.copy(
                        aliases = aliases,
                        included = included,
                        initialized = true,
                        updatedAt = now,
                    ),
                )
                db.assets().insertEntry(
                    AssetEntry(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        createdId, positionCents, "OPENING", reason.trim(), nextEntryTime(db),
                    ),
                )
                createdId
            }
            AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
            accountId
        }
        refresh(); id
    }
    private suspend fun createInTransaction(db: AppDb, name: String, kind: String, order: Int): String {
        val now = System.currentTimeMillis()
        val row = AssetAccount(UUID.randomUUID().toString(), name.trim(), kind, sortOrder = order, createdAt = now, updatedAt = now)
        AssetMath.validate(AssetSnapshot(db.assets().accounts() + row, db.assets().entries()))
        db.assets().upsertAccount(row)
        return row.id
    }
    suspend fun updateAccount(account: AssetAccount) = withContext(Dispatchers.IO) {
        awaitReady()
        db.withTransaction {
            val current = db.assets().account(account.id) ?: error("账户不存在")
            val updated = account.copy(name = account.name.trim(), initialized = current.initialized,
                createdAt = current.createdAt, updatedAt = System.currentTimeMillis())
            val all = db.assets().accounts().map { if (it.id == updated.id) updated else it }
            AssetMath.validate(AssetSnapshot(all, db.assets().entries()))
            db.assets().upsertAccount(updated)
        }; refresh()
    }
    suspend fun deleteEmptyAccount(id: String) = withContext(Dispatchers.IO) {
        awaitReady()
        db.withTransaction {
            val account = db.assets().account(id) ?: error("账户不存在")
            require(!account.initialized && db.assets().entries().none { it.accountId == id } &&
                db.bills().allOnce().none { it.accountId == id || it.transferAccountId == id || it.account == account.name } &&
                db.recognitions().allOnce().none { it.account == account.name }) { "此账户已有记录，请使用归档" }
            db.assets().deleteAccount(id)
        }
        refresh()
    }
    suspend fun setArchived(accountId: String, archived: Boolean) {
        awaitReady(); val row = withContext(Dispatchers.IO) { db.assets().account(accountId) } ?: error("账户不存在")
        updateAccount(row.copy(archived = archived))
    }
    suspend fun setBalance(accountId: String, positionCents: Long, reason: String, expectedPosition: Long? = null) = withContext(Dispatchers.IO) {
        awaitReady(); require(reason.isNotBlank()) { "请填写调整原因" }
        require(positionCents != Long.MIN_VALUE) { "金额超出范围" }
        db.withTransaction {
            val row = db.assets().account(accountId) ?: error("账户不存在")
            require(!row.archived) { "请先恢复归档账户" }
            val balance = AssetMath.balance(accountId, db.assets().entries())
            require(expectedPosition == null || expectedPosition == balance) { "余额已变化，请重新预览后调整" }
            val delta = Math.subtractExact(positionCents, balance)
            val now = System.currentTimeMillis()
            db.assets().upsertAccount(row.copy(initialized = true, updatedAt = now))
            db.assets().insertEntry(AssetEntry(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                accountId, delta, if (row.initialized) "ADJUSTMENT" else "OPENING", reason.trim(), nextEntryTime(db)))
            AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
        }; refresh()
    }
    suspend fun reverseAdjustment(entryId: String) = withContext(Dispatchers.IO) {
        awaitReady()
        db.withTransaction {
            val all = db.assets().entries()
            val e = all.firstOrNull { it.id == entryId } ?: error("流水不存在")
            require(e.kind == "ADJUSTMENT") { "只能撤销余额调整，初始化请使用新调整" }
            require(all.none { it.reversalOf == entryId }) { "此调整已撤销" }
            reverse(db, listOf(e), "撤销余额调整")
            AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
        }; refresh()
    }
    suspend fun resolveAccount(db: AppDb, name: String?): AssetAccount? {
        if (name.isNullOrBlank()) return null
        return db.assets().accounts().filter { !it.archived && (it.name == name.trim() || name.trim() in AssetMath.aliases(it)) }.singleOrNull()
    }
    /** Caller owns the surrounding Room transaction, including the final BillEntity write. */
    suspend fun syncBill(db: AppDb, before: BillEntity?, after: BillEntity?, forceLink: Boolean = false): BillEntity? {
        val key = after?.assetKey?.takeIf(String::isNotBlank) ?: before?.assetKey?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val existing = db.assets().entriesForBill(key)
        val reversed = existing.mapNotNull { it.reversalOf }.toSet()
        val active = existing.filter { it.reversalOf == null && it.id !in reversed }
        if (after == null) {
            reverse(db, active, "删除账单")
            AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
            return null
        }
        val payment = active.filter { it.kind != "REIMBURSE" }
        val account = if (before != null && before.account == after.account && after.accountId != null)
            db.assets().account(after.accountId) else after.accountId?.let { id -> db.assets().account(id) }
                ?.takeIf { it.name == after.account || before == null } ?: resolveAccount(db, after.account)
        val destination = after.transferAccountId?.let { db.assets().account(it) }
        val requested = forceLink || before?.assetLinked == true || (before == null && (after.assetKey.isBlank() || after.assetLinked))
        val linked = requested && account?.initialized == true &&
            (after.type != "TRANSFER" || destination?.initialized == true && destination.id != account.id)
        if (linked && (before == null && after.assetKey.isBlank() || before != null && before.accountId != account?.id)) require(account?.archived == false) { "账户已归档" }
        if (linked && after.type == "TRANSFER" && (before != null && before.transferAccountId != destination?.id)) require(destination?.archived == false) { "转入账户已归档" }
        val row = after.copy(accountId = account?.id, assetKey = key, assetLinked = linked)
        val desired = if (!linked) emptyList() else if (after.type == "TRANSFER") listOf(account!!.id to -after.priceCents, destination!!.id to after.priceCents)
            else listOf(account!!.id to AssetMath.delta(after.type, after.priceCents))
        val actual = payment.map { it.accountId to it.deltaCents }.sortedBy { it.first }
        if (actual == desired.sortedBy { it.first }) return row
        reverse(db, payment, "修改账单")
        val operation = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        desired.forEach { (id, delta) ->
            db.assets().insertEntry(AssetEntry(UUID.randomUUID().toString(), operation, id, delta,
                if (after.type == "TRANSFER") "TRANSFER" else "BILL", after.note?.takeIf { it.isNotBlank() } ?: after.merchant ?: "账单", nextEntryTime(db), key))
        }
        AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
        return row
    }

    /**
     * 报销退回单独维护，不进入 [syncBill] 的扣款对比。
     * 改备注或改金额时原支出扣款保持不动；金额变化只改这一条，换账户才冲销后重写。
     */
    suspend fun syncReimbursement(db: AppDb, bill: BillEntity): BillEntity {
        val key = bill.assetKey.takeIf { it.isNotBlank() } ?: return bill
        val existing = db.assets().entriesForBill(key)
        val reversed = existing.mapNotNull { it.reversalOf }.toSet()
        val active = existing.filter { it.kind == "REIMBURSE" && it.reversalOf == null && it.id !in reversed }
        val payment = bill.accountId?.let { db.assets().account(it) }
        val target = bill.reimburseAccountId?.let { db.assets().account(it) }
        val want = bill.reimburseStatus == Reimburse.REIMBURSED && bill.type == "EXPENSE" && bill.feeParentKey == null &&
            bill.assetLinked && payment?.initialized == true && target?.initialized == true && target.archived == false
        if (!want) {
            if (active.isNotEmpty()) {
                reverse(db, active, "撤销报销")
                AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
            }
            return bill
        }
        val delta = bill.priceCents
        val only = active.singleOrNull()
        if (only != null && only.accountId == target!!.id && only.deltaCents == delta) return bill
        if (only != null && only.accountId == target.id) {
            db.assets().updateEntry(only.copy(deltaCents = delta, note = "报销退回", updatedAt = System.currentTimeMillis()))
        } else {
            if (active.isNotEmpty()) reverse(db, active, "调整报销退回")
            db.assets().insertEntry(AssetEntry(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), target.id, delta,
                "REIMBURSE", "报销退回", bill.reimbursedAt ?: System.currentTimeMillis(), key,
            ))
        }
        AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
        return bill
    }

    private suspend fun nextEntryTime(db: AppDb): Long = maxOf(System.currentTimeMillis(),
        Math.addExact(db.assets().latestEntryTime() ?: 0L, 1L))
    private suspend fun reverse(db: AppDb, entries: List<AssetEntry>, note: String) {
        val operation = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        entries.forEach { db.assets().insertEntry(AssetEntry(UUID.randomUUID().toString(), operation, it.accountId,
            Math.negateExact(it.deltaCents), "REVERSAL", note, nextEntryTime(db), it.billKey, it.id)) }
    }
    suspend fun migrateAccounts(db: AppDb, legacyNames: List<String>) = db.withTransaction {
        if (db.assets().meta("legacy-accounts-v1") != null) return@withTransaction
        val allBills = db.bills().allOnce()
        val names = legacyNames.map(String::trim).filter(String::isNotEmpty).distinct()
        val historical = allBills.mapNotNull { it.account?.trim()?.takeIf(String::isNotEmpty) }.distinct().filterNot { it in names }
        val now = System.currentTimeMillis()
        (names + historical).forEachIndexed { index, name ->
            val account = AssetAccount(UUID.nameUUIDFromBytes(("legacy-account:" + name).toByteArray(Charsets.UTF_8)).toString(),
                name, sortOrder = index, archived = name !in names, createdAt = now, updatedAt = now)
            db.assets().upsertAccount(account)
            allBills.filter { it.account?.trim() == name }.forEach { bill ->
                db.bills().update(bill.copy(accountId = account.id, assetKey = bill.assetKey.ifBlank { "legacy-${bill.id}" }))
            }
        }
        allBills.filter { it.assetKey.isBlank() && it.account.isNullOrBlank() }.forEach { db.bills().update(it.copy(assetKey = "legacy-${it.id}")) }
        db.assets().putMeta(AssetMeta("legacy-accounts-v1", "complete"))
    }

    /** 保证兜底账户「钱包」存在；已有同名则只恢复归档。 */
    private suspend fun ensureCatchAllWallet(db: AppDb) = db.withTransaction {
        val existing = db.assets().accounts().firstOrNull { it.name == "钱包" }
        if (existing != null) {
            if (existing.archived) {
                db.assets().upsertAccount(existing.copy(archived = false, updatedAt = System.currentTimeMillis()))
            }
            return@withTransaction
        }
        createInTransaction(db, "钱包", "PAYMENT", 0)
    }

    /**
     * 旧「未设置」占位：无流水、无账单/识别关联则直接开通为 0。
     * 有关联但仍未设置的留给账户管理/详情补余额，不做归档替代。
     */
    private suspend fun openUnsetPlaceholdersAsZero(db: AppDb) = db.withTransaction {
        if (db.assets().meta("open-unset-v1") != null) return@withTransaction
        val entryIds = db.assets().entries().map { it.accountId }.toSet()
        val bills = db.bills().allOnce()
        val billAccountIds = bills.flatMap { listOfNotNull(it.accountId, it.transferAccountId) }.toSet()
        val billNames = bills.mapNotNull { it.account?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        val recogNames = db.recognitions().allOnce()
            .mapNotNull { it.account?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        db.assets().accounts().filter { !it.initialized && !it.archived }.forEach { acc ->
            val linked = acc.id in entryIds || acc.id in billAccountIds ||
                acc.name in billNames || acc.name in recogNames
            if (!linked) {
                db.assets().upsertAccount(acc.copy(initialized = true, updatedAt = System.currentTimeMillis()))
                db.assets().insertEntry(
                    AssetEntry(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        acc.id, 0L, "OPENING", "开通账户", nextEntryTime(db),
                    ),
                )
            }
        }
        db.assets().putMeta(AssetMeta("open-unset-v1", "complete"))
    }
}
