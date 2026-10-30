package com.example.autobook.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 备份的"有副作用"那一半：查库、写偏好、读写文件。
 *
 * 数据格式与校验在纯逻辑的 [Backup] 里（可单测），这里只负责把它接到 Android 上。
 * 没有 `allowBackup` 兜底（Manifest 里是 false），所以这是换机/重装的唯一通道。
 */
object BackupStore {
    private val restoreMutex = Mutex()
    private const val PENDING_CONFIG = "pending-restore-config"

    /** 恢复策略 */
    enum class Mode {
        /** 合并：指纹已存在就跳过，不动现有记录（默认，安全） */
        MERGE,

        /** 覆盖：先清空 bills 表再导入（危险，UI 必须二次确认） */
        REPLACE,
    }

    /** 生成 JSON 文本（IO 线程；几千条账单大约几百毫秒） */
    suspend fun exportJson(context: Context): String = withContext(Dispatchers.IO) {
        RecognitionStore.init(context)
        RecognitionStore.awaitReady()
        AssetStore.init(context)
        AssetStore.awaitReady()
        val db = AppDb.get(context)
        var importRows = emptyList<ImportIdentity>()
        val (data, captureRows) = db.withTransaction {
            importRows = db.imports().all()
            Triple(db.bills().allOnce(), db.recognitions().allOnce(), AssetSnapshot(db.assets().accounts(), db.assets().entries())) to
                CaptureSnapshot(db.capture().aliases(), db.capture().exportEvidence(), db.capture().deliveries())
        }
        val (rows, historyRows, assetRows) = data
        val settings = Settings(context)
        Backup.Snapshot(
            exportedAt = System.currentTimeMillis(),
            bills = rows.map { r ->
                Backup.Bill(
                    priceCents = r.priceCents,
                    type = r.type,
                    timeMillis = r.timeMillis,
                    merchant = r.merchant,
                    method = r.method,
                    orderTail = r.orderTail,
                    sourcePkg = r.sourcePkg,
                    strategy = r.strategy,
                    fingerprint = r.fingerprint,
                    createdAt = r.createdAt,
                    category = r.category,
                    subCategory = r.subCategory,
                    account = r.account,
                    note = r.note, productName = r.productName, productSource = r.productSource,
                    noteOrigin = r.noteOrigin, noteTemplateSnapshot = r.noteTemplateSnapshot,
                    recognitionId = r.recognitionId,
                    id = r.id,
                    accountId = r.accountId, transferAccountId = r.transferAccountId,
                    assetLinked = r.assetLinked, assetKey = r.assetKey,
                    transferFeeCents = r.transferFeeCents, feeParentKey = r.feeParentKey, transferAccount = r.transferAccount,
                )
            },
            history = historyRows,
            imports = importRows,
            capture = CaptureMetadataCodec.portable(captureRows),
            assets = assetRows,
            ledger = Backup.Ledger(
                expense = LedgerConfig.expenseGroups,
                income = LedgerConfig.incomeGroups,
                accounts = assetRows.accounts.filterNot { it.archived }.sortedBy { it.sortOrder }.map { it.name },
                noteTemplate = LedgerConfig.noteTemplate,
                localRules = LocalRuleStore.snapshot(),
                monthStartDay = LedgerPeriodStore.read(context),
            ),
            settings = settingsSnapshot(settings),
        ).let { Backup.encode(it) }
    }

    /**
     * 恢复。调用方负责在 IO 线程之外更新 UI；重复指纹的处理见 [Mode]。
     *
     * @throws IllegalArgumentException 备份内容不可用（由 UI 显示原因）
     */
    suspend fun restore(context: Context, json: String, mode: Mode): Backup.RestoreStats =
        withContext(Dispatchers.IO) { restoreMutex.withLock {
            val snapshot = Backup.decode(json)
            RecognitionStore.init(context)
            RecognitionStore.awaitReady()
            AssetStore.init(context)
            AssetStore.awaitReady()
            val db = AppDb.get(context)
            applyPendingConfiguration(context)

            val (billCount, historyCount) = restoreDatabase(db, snapshot, mode)

            applyPendingConfiguration(context)
            AssetStore.refresh()
            com.example.autobook.systems.SystemIntegration.refresh(context)
            Backup.RestoreStats(
                bills = billCount,
                history = historyCount,
                ledger = snapshot.ledger != null,
                settings = snapshot.settings,
                assets = snapshot.assets != null,
                assetWarning = if (snapshot.assets == null) "旧备份不含资产，已保留当前余额和流水；导入账单不自动计入余额，请核对账户。" else null,
            )
        } }

    /** Production database path shared with isolated Room verification; no preference side effects. */
    internal suspend fun restoreDatabase(db: AppDb, snapshot: Backup.Snapshot, mode: Mode): Pair<Int, Int> {
        Backup.validateAssetBills(snapshot.bills, snapshot.assets)
        snapshot.assets?.let(AssetBackupCodec::validate)
        snapshot.capture?.let { CaptureMetadataCodec.validate(it,
            snapshot.history ?: throw IllegalArgumentException("采集元数据缺少history")) }
        return db.withTransaction {
                val historyDao = db.recognitions()
                val assetDao = db.assets()
                val localAssetSnapshot = AssetSnapshot(assetDao.accounts(), assetDao.entries())
                val existingBills = db.bills().allOnce()
                val existingImports = db.imports().all()
                // First launch has already seeded default accounts. They are placeholders only
                // when the entire asset shape is untouched and no business record uses the database.
                val initializeAssets = mode == Mode.MERGE && snapshot.assets?.accounts?.isNotEmpty() == true &&
                    existingBills.isEmpty() && existingImports.isEmpty() &&
                    AssetBackupCodec.isUntouchedDefaultSnapshot(localAssetSnapshot, LedgerConfig.DEFAULT_ACCOUNTS) &&
                    historyDao.allOnce().isEmpty()
                // Validate both identities and references before any destructive operation.
                val targetAssets = snapshot.assets?.let { incoming ->
                    if (mode == Mode.MERGE && !initializeAssets) AssetBackupCodec.merge(localAssetSnapshot, incoming) else incoming
                }
                val importedKeys = existingImports.map { it.sourceKey }.toSet()
                val existingFingerprints = existingBills.map { it.fingerprint }.toSet()
                val targetImports = snapshot.imports?.let {
                    if (mode == Mode.MERGE) ImportIdentityCodec.merge(existingImports, it) else it.also(ImportIdentityCodec::validate)
                }
                if (mode == Mode.MERGE && snapshot.assets != null) {
                    val byKey = existingBills.filter { it.assetKey.isNotBlank() }.associateBy { it.assetKey }
                    snapshot.bills.forEach { incoming ->
                        byKey[incoming.assetKey]?.let { local ->
                            require(sameAssetBill(local, incoming)) { "账单资产身份内容冲突，请核对后恢复；本次未写入" }
                        }
                    }
                }
                if (mode == Mode.REPLACE) {
                    db.bills().deleteAll()
                    if (snapshot.history != null) {
                        db.capture().clearMetadata()
                        historyDao.deleteAll()
                    } else historyDao.allOnce().forEach { historyDao.update(it.copy(billId = null)) }
                }
                if (targetAssets != null) {
                    val resetAssets = mode == Mode.REPLACE || initializeAssets
                    if (resetAssets) { assetDao.clearEntries(); assetDao.clearAccounts() }
                    val localAccountIds = if (resetAssets) emptySet() else localAssetSnapshot.accounts.map { it.id }.toSet()
                    val localEntryIds = if (resetAssets) emptySet() else localAssetSnapshot.entries.map { it.id }.toSet()
                    targetAssets.accounts.filterNot { it.id in localAccountIds }.forEach { assetDao.upsertAccount(it) }
                    targetAssets.entries.filterNot { it.id in localEntryIds }.forEach { assetDao.insertEntry(it) }
                    assetDao.putMeta(AssetMeta("legacy-accounts-v1", "complete"))
                }
                // Resolve stable UUIDs and strong identities before inserting linked bills.
                val canonical = HashMap<String, String>()
                val existingHistory = HashMap<String, RecognitionEntity?>()
                snapshot.history.orEmpty().forEach { incoming ->
                    val sameId = historyDao.byId(incoming.id)
                    require(sameId == null || sameId.identityKey == incoming.identityKey) { "识别历史ID对应不同身份，未恢复" }
                    val local = sameId ?: historyDao.byIdentity(incoming.identityKey)
                    canonical[incoming.id] = local?.id ?: incoming.id
                    existingHistory[incoming.id] = local
                }
                val idMap = HashMap<Long, Long>()
                val recognitionBills = HashMap<String, Long>()
                var inserted = 0
                snapshot.bills.forEach { b ->
                    // A local import tombstone wins during merge; explicit full replacement restores its snapshot.
                    if (mode == Mode.MERGE && b.fingerprint in importedKeys &&
                        b.fingerprint !in existingFingerprints) return@forEach
                    val recognitionId = b.recognitionId?.let { canonical[it] ?: it }
                    // Legacy backups have no stable UUID: keep their original fingerprint compatibility.
                    val stableLocal = b.assetKey.takeIf { it.isNotBlank() }?.let { key ->
                        if (mode == Mode.MERGE) existingBills.firstOrNull { it.assetKey == key } else null
                    }
                    val local = stableLocal ?: if (recognitionId != null) {
                        db.bills().byRecognitionId(recognitionId)
                            ?: db.bills().byFingerprint(b.fingerprint)?.takeIf { it.recognitionId == null }
                    } else db.bills().byFingerprint(b.fingerprint)
                    if (snapshot.assets != null && local != null && b.assetKey.isNotBlank()) {
                        require(local.assetKey == b.assetKey && sameAssetBill(local, b)) {
                            "同一账单对应不同资产身份，未恢复；请使用同一来源的完整快照"
                        }
                    }
                    val billId = if (local != null) {
                        if (recognitionId != null && local.recognitionId == null) db.bills().update(local.copy(recognitionId = recognitionId))
                        local.id
                    } else {
                        inserted++
                        db.bills().insert(BillEntity(priceCents = b.priceCents, type = b.type,
                            timeMillis = b.timeMillis, merchant = b.merchant, method = b.method,
                            orderTail = b.orderTail, sourcePkg = b.sourcePkg, strategy = b.strategy,
                            fingerprint = b.fingerprint, createdAt = b.createdAt, category = b.category,
                            subCategory = b.subCategory, account = b.account, note = b.note,
                            productName = b.productName, productSource = b.productSource, noteOrigin = b.noteOrigin, noteTemplateSnapshot = b.noteTemplateSnapshot,
                            recognitionId = recognitionId,
                            accountId = if (snapshot.assets != null) b.accountId else null,
                            transferAccountId = if (snapshot.assets != null) b.transferAccountId else null,
                            assetLinked = snapshot.assets != null && b.assetLinked,
                            assetKey = b.assetKey.takeIf { snapshot.assets != null && it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                            transferFeeCents = if (snapshot.assets != null) b.transferFeeCents else 0,
                            feeParentKey = if (snapshot.assets != null) b.feeParentKey else null,
                            transferAccount = b.transferAccount))
                    }
                    b.id?.let { idMap[it] = billId }
                    recognitionId?.let { recognitionBills[it] = billId }
                }
                targetImports?.let { rows ->
                    if (mode == Mode.REPLACE) db.imports().clear()
                    val existingKeys = if (mode == Mode.REPLACE) emptySet() else existingImports.map { it.sourceKey }.toSet()
                    rows.filterNot { it.sourceKey in existingKeys }.forEach { db.imports().insert(it) }
                }
                snapshot.history.orEmpty().forEach { incoming ->
                    val local = existingHistory[incoming.id]
                    val id = canonical.getValue(incoming.id)
                    val linked = recognitionBills[id] ?: incoming.billId?.let(idMap::get)
                    val reviewOf = incoming.reviewOf?.let { canonical[it] ?: error("待核对关联缺少识别记录映射") }
                    val restored = mergeRecognition(local, incoming.copy(id = id, billId = linked, reviewOf = reviewOf))
                    if (local == null) historyDao.insert(restored) else historyDao.update(restored)
                }
                snapshot.capture?.let { incoming ->
                    val captureDao = db.capture()
                    val local = CaptureSnapshot(captureDao.aliases(), captureDao.exportEvidence(), captureDao.deliveries())
                    val restored = CaptureMetadataCodec.remapForRestore(incoming, canonical)
                    val merged = CaptureMetadataCodec.merge(local, restored)
                    val aliasKeys = local.aliases.map { it.aliasKey }.toSet()
                    val eventIds = local.evidence.map { it.eventId }.toSet()
                    val deliveryIds = local.deliveries.map { it.recognitionId }.toSet()
                    merged.aliases.filterNot { it.aliasKey in aliasKeys }.forEach {
                        require(captureDao.putAlias(it) != -1L) { "采集别名写入冲突，未恢复" }
                    }
                    merged.evidence.filterNot { it.eventId in eventIds }.forEach {
                        require(captureDao.putEvidence(it) != -1L) { "采集事件写入冲突，未恢复" }
                    }
                    merged.deliveries.filterNot { it.recognitionId in deliveryIds }.forEach { captureDao.putDelivery(it) }
                }
                // Validate the merged effective postings as a whole, not just each input.
                // Distinct event UUIDs must not double-post the same stable bill key.
                Backup.validateAssetBills(db.bills().allOnce().map(::assetBillModel),
                    AssetSnapshot(assetDao.accounts(), assetDao.entries()))
                // Journal configuration in the same DB commit. A crash after this point can
                // safely replay configuration without replaying account balances or bill inserts.
                val configJson = Backup.encode(Backup.Snapshot(snapshot.exportedAt, emptyList(), snapshot.ledger, null))
                assetDao.putMeta(AssetMeta(PENDING_CONFIG, MiniJson.encode(mapOf(
                    "assetsPresent" to (snapshot.assets != null), "snapshot" to configJson))))
                inserted to snapshot.history.orEmpty().size
        }
    }

    /** Called after AssetStore initialization; replay is idempotent after a restore-time crash. */
    suspend fun completePendingConfiguration(context: Context) = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            AssetStore.init(context)
            AssetStore.awaitReady()
            applyPendingConfiguration(context)
        }
    }

    private suspend fun applyPendingConfiguration(context: Context) {
        val dao = AppDb.get(context).assets()
        val raw = dao.meta(PENDING_CONFIG)?.takeIf { it.isNotBlank() } ?: return
        val journal = MiniJson.decode(raw) as? Map<*, *> ?: error("恢复配置日志损坏")
        val assetsPresent = journal["assetsPresent"] as? Boolean ?: error("恢复配置日志损坏")
        val config = Backup.decode(journal["snapshot"] as? String ?: error("恢复配置日志损坏"))
        config.ledger?.let {
            val periodDay = LedgerMonthPeriod.restoredDay(LedgerPeriodStore.read(context), it.monthStartDay)
            if (it.monthStartDay != null) LedgerPeriodStore.save(context, periodDay)
            LedgerConfig.restore(it.expense.takeIf { _ -> it.hasExpense }, it.income.takeIf { _ -> it.hasIncome },
                it.accounts.takeIf { _ -> it.hasAccounts && !assetsPresent }, it.noteTemplate.takeIf { _ -> it.hasNoteTemplate })
            it.localRules?.let(LocalRuleStore::restore)
        }
        dao.putMeta(AssetMeta(PENDING_CONFIG, ""))
        AssetStore.refresh()
    }

    private fun assetBillModel(b: BillEntity) = Backup.Bill(
        b.priceCents, b.type, b.timeMillis, b.merchant, b.method, b.orderTail, b.sourcePkg, b.strategy,
        b.fingerprint, b.createdAt, b.category, b.subCategory, b.account, b.note, b.recognitionId, b.id,
        b.accountId, b.transferAccountId, b.assetLinked, b.assetKey, b.transferFeeCents, b.feeParentKey, b.transferAccount, b.productName, b.productSource, b.noteOrigin, b.noteTemplateSnapshot)

    private fun sameAssetBill(local: BillEntity, incoming: Backup.Bill): Boolean =
        local.priceCents == incoming.priceCents && local.type == incoming.type && local.timeMillis == incoming.timeMillis &&
            local.merchant == incoming.merchant && local.method == incoming.method && local.orderTail == incoming.orderTail &&
            local.sourcePkg == incoming.sourcePkg && local.strategy == incoming.strategy && local.fingerprint == incoming.fingerprint &&
            local.createdAt == incoming.createdAt && local.category == incoming.category && local.subCategory == incoming.subCategory &&
            local.account == incoming.account && local.note == incoming.note && local.accountId == incoming.accountId &&
            local.transferAccount == incoming.transferAccount && local.transferAccountId == incoming.transferAccountId && local.assetLinked == incoming.assetLinked &&
            local.assetKey == incoming.assetKey && local.transferFeeCents == incoming.transferFeeCents && local.feeParentKey == incoming.feeParentKey

    /** 备份默认文件名：`麦米-备份-20261021-2015.json` */
    fun fileName(atMillis: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date(atMillis))
        return "麦米-备份-$stamp.json"
    }

    /** Uses original choices so a self-build export does not erase another build's saved scope. */
    internal fun settingsSnapshot(settings: Settings): Backup.SettingsSnapshot = Backup.SettingsSnapshot(
        whitelist = settings.configuredWhitelist.toList().sorted(),
        minIntervalMs = settings.minIntervalMs,
        rootMode = settings.rootMode,
    )

    /** 恢复后把可移植配置原值写回；运行范围仍由Settings统一限制。 */
    fun applySettings(context: Context, snapshot: Backup.SettingsSnapshot): Boolean {
        val changed = applySettingsTo(Settings(context), snapshot)
        com.example.autobook.capture.CaptureModeManager.reconcile(context)
        return changed
    }

    /** Isolated Android verification shares the exact restore path without starting global services. */
    internal fun applySettingsTo(settings: Settings, snapshot: Backup.SettingsSnapshot): Boolean {
        val oldWhitelist = settings.whitelist
        if (snapshot.whitelist.isNotEmpty()) settings.whitelist = snapshot.whitelist.toSet()
        if (snapshot.minIntervalMs in 300L..10_000L) settings.minIntervalMs = snapshot.minIntervalMs
        val restoredMode = CaptureModePolicy.restore(settings.captureMode, snapshot.rootMode)
        if (settings.captureMode == null && restoredMode != null) settings.captureMode = restoredMode
        return settings.whitelist != oldWhitelist
    }

    /** Compatibility helper returns only packages accepted by this build. */
    fun safeWhitelist(list: List<String>): Set<String> =
        if (list.isEmpty()) CaptureScope.packages
        else CaptureScope.filterPackages(list.filter { it.isNotBlank() }.toSet())

}

/** A restore may advance processing state, but cannot make a recorded item pending again. */
internal fun mergeRecognition(local: RecognitionEntity?, incoming: RecognitionEntity): RecognitionEntity {
    if (local == null) return incoming
    val protectedFields = local.protectedFields or incoming.protectedFields
    if (local.status == RecognitionStatus.RECORDED) return local.copy(
        billId = incoming.billId ?: local.billId, protectedFields = protectedFields)
    if (local.status == RecognitionStatus.IGNORED && incoming.status != RecognitionStatus.RECORDED) {
        return local.copy(protectedFields = protectedFields)
    }
    if (incoming.status == RecognitionStatus.RECORDED && local.protectedFields != 0L) {
        require(CaptureFields.changed(local.toDraft(), incoming.toDraft()) == 0L) {
            "已编辑草稿与备份已记账内容冲突，请核对后恢复"
        }
    }
    val chosen = if (incoming.status == RecognitionStatus.RECORDED || incoming.updatedAt > local.updatedAt) incoming else local
    val merged = chosen.copy(id = local.id, identityKey = local.identityKey,
        protectedFields = protectedFields, reviewOf = local.reviewOf)
    // A user-edited local draft wins over an imported snapshot even if the backup is newer.
    // Conservatively keep the whole local editable field set once any field was protected.
    return if (local.protectedFields == 0L) merged else merged.copy(
        priceCents = local.priceCents, type = local.type, timeMillis = local.timeMillis,
        merchant = local.merchant, method = local.method, category = local.category,
        subCategory = local.subCategory, account = local.account, transferAccountId = local.transferAccountId, note = local.note)
}
