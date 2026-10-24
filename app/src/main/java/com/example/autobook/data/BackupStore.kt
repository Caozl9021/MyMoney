package com.example.autobook.data

import android.content.Context
import androidx.room.withTransaction
import com.example.billkit.Packages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份的"有副作用"那一半：查库、写偏好、读写文件。
 *
 * 数据格式与校验在纯逻辑的 [Backup] 里（可单测），这里只负责把它接到 Android 上。
 * 没有 `allowBackup` 兜底（Manifest 里是 false），所以这是换机/重装的唯一通道。
 */
object BackupStore {

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
        val db = AppDb.get(context)
        val (rows, historyRows) = db.withTransaction { db.bills().allOnce() to db.recognitions().allOnce() }
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
                    note = r.note,
                    recognitionId = r.recognitionId,
                    id = r.id,
                )
            },
            history = historyRows,
            ledger = Backup.Ledger(
                expense = LedgerConfig.expenseGroups,
                income = LedgerConfig.incomeGroups,
                accounts = LedgerConfig.accounts,
                noteTemplate = LedgerConfig.noteTemplate,
                localRules = LocalRuleStore.snapshot(),
            ),
            settings = Backup.SettingsSnapshot(
                whitelist = settings.whitelist.toList().sorted(),
                minIntervalMs = settings.minIntervalMs,
                ocrFallback = settings.ocrFallback,
                rootMode = settings.rootMode,
            ),
        ).let { Backup.encode(it) }
    }

    /**
     * 恢复。调用方负责在 IO 线程之外更新 UI；重复指纹的处理见 [Mode]。
     *
     * @throws IllegalArgumentException 备份内容不可用（由 UI 显示原因）
     */
    suspend fun restore(context: Context, json: String, mode: Mode): Backup.RestoreStats =
        withContext(Dispatchers.IO) {
            val snapshot = Backup.decode(json)
            RecognitionStore.init(context)
            RecognitionStore.awaitReady()
            val db = AppDb.get(context)

            val (billCount, historyCount) = db.withTransaction {
                val historyDao = db.recognitions()
                if (mode == Mode.REPLACE) {
                    db.bills().deleteAll()
                    if (snapshot.history != null) historyDao.deleteAll()
                    else historyDao.allOnce().forEach { historyDao.update(it.copy(billId = null)) }
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
                    val recognitionId = b.recognitionId?.let { canonical[it] ?: it }
                    // Legacy backups have no stable UUID: keep their original fingerprint compatibility.
                    val local = if (recognitionId != null) {
                        db.bills().byRecognitionId(recognitionId)
                            ?: db.bills().byFingerprint(b.fingerprint)?.takeIf { it.recognitionId == null }
                    } else db.bills().byFingerprint(b.fingerprint)
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
                            recognitionId = recognitionId))
                    }
                    b.id?.let { idMap[it] = billId }
                    recognitionId?.let { recognitionBills[it] = billId }
                }
                snapshot.history.orEmpty().forEach { incoming ->
                    val local = existingHistory[incoming.id]
                    val id = canonical.getValue(incoming.id)
                    val linked = recognitionBills[id] ?: incoming.billId?.let(idMap::get)
                    val restored = mergeRecognition(local, incoming.copy(id = id, billId = linked))
                    if (local == null) historyDao.insert(restored) else historyDao.update(restored)
                }
                inserted to snapshot.history.orEmpty().size
            }

            snapshot.ledger?.let {
                LedgerConfig.restore(it.expense.takeIf { _ -> it.hasExpense }, it.income.takeIf { _ -> it.hasIncome },
                    it.accounts.takeIf { _ -> it.hasAccounts }, it.noteTemplate.takeIf { _ -> it.hasNoteTemplate })
                it.localRules?.let { rules -> LocalRuleStore.restore(rules) }
            }

            com.example.autobook.systems.SystemIntegration.refresh(context)
            Backup.RestoreStats(
                bills = billCount,
                history = historyCount,
                ledger = snapshot.ledger != null,
                settings = snapshot.settings,
            )
        }

    /** 备份默认文件名：`autobook-backup-20261021-2015.json` */
    fun fileName(atMillis: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date(atMillis))
        return "autobook-backup-$stamp.json"
    }

    /** 恢复后把"能从备份带回来的设置"写回去；返回是否改过白名单（调用方据此刷新 BillKit） */
    fun applySettings(context: Context, snapshot: Backup.SettingsSnapshot): Boolean {
        val settings = Settings(context)
        val oldWhitelist = settings.whitelist
        if (snapshot.whitelist.isNotEmpty()) settings.whitelist = snapshot.whitelist.toSet()
        if (snapshot.minIntervalMs in 300L..10_000L) settings.minIntervalMs = snapshot.minIntervalMs
        settings.ocrFallback = snapshot.ocrFallback
        val restoredMode = CaptureModePolicy.restore(settings.captureMode, snapshot.rootMode)
        if (settings.captureMode == null && restoredMode != null) settings.captureMode = restoredMode
        com.example.autobook.capture.CaptureModeManager.reconcile(context)
        return settings.whitelist != oldWhitelist
    }

    /** 白名单为空时至少保留默认清单，避免恢复后什么都识别不到 */
    fun safeWhitelist(list: List<String>): Set<String> =
        list.filter { it.isNotBlank() }.toSet().ifEmpty { Packages.defaultWhitelist }
}

/** A restore may advance processing state, but cannot make a recorded item pending again. */
internal fun mergeRecognition(local: RecognitionEntity?, incoming: RecognitionEntity): RecognitionEntity {
    if (local == null) return incoming
    if (local.status == RecognitionStatus.RECORDED) return local.copy(billId = incoming.billId ?: local.billId)
    if (incoming.status == RecognitionStatus.RECORDED) return incoming.copy(id = local.id, identityKey = local.identityKey)
    return if (incoming.updatedAt > local.updatedAt) incoming.copy(id = local.id, identityKey = local.identityKey) else local
}
