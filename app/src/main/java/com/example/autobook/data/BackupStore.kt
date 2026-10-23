package com.example.autobook.data

import android.content.Context
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
        val db = AppDb.get(context)
        val rows = db.bills().allOnce()
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
                )
            },
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
            val db = AppDb.get(context)

            if (mode == Mode.REPLACE) db.bills().deleteAll()

            // 合并模式下先查已有指纹：备份文件里同一条出现两次也要过滤掉
            val existing = if (mode == Mode.MERGE) {
                db.bills().allOnce().mapTo(HashSet()) { it.fingerprint }
            } else {
                HashSet()
            }
            val fresh = ArrayList<BillEntity>(snapshot.bills.size)
            snapshot.bills.forEach { b ->
                if (!existing.add(b.fingerprint)) return@forEach
                fresh += BillEntity(
                    priceCents = b.priceCents,
                    type = b.type,
                    timeMillis = b.timeMillis,
                    merchant = b.merchant,
                    method = b.method,
                    orderTail = b.orderTail,
                    sourcePkg = b.sourcePkg,
                    strategy = b.strategy,
                    fingerprint = b.fingerprint,
                    createdAt = b.createdAt,
                    category = b.category,
                    subCategory = b.subCategory,
                    account = b.account,
                    note = b.note,
                )
            }
            if (fresh.isNotEmpty()) db.bills().insertAll(fresh)

            snapshot.ledger?.let {
                LedgerConfig.restore(it.expense, it.income, it.accounts, it.noteTemplate)
                it.localRules?.let { rules -> LocalRuleStore.restore(rules) }
            }

            com.example.autobook.systems.SystemIntegration.refresh(context)
            Backup.RestoreStats(
                bills = fresh.size,
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
