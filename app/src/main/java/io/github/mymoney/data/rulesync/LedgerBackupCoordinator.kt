package io.github.mymoney.data.rulesync

import android.content.Context
import io.github.mymoney.data.Backup
import io.github.mymoney.data.BackupStore
import io.github.mymoney.data.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manual operations only. Local durable pending uploads survive a lost upload receipt.
 *
 * Locks split (AA3): [downloadMutex] guards only the download cache directory + one
 * in-flight download per backup id; [restoreMutex] serialises the destructive local
 * restore write. Network reads (list/status) hold no download lock, so a slow backup no
 * longer freezes the history list, and cancelling a download never waits on the write.
 */
object LedgerBackupCoordinator {
    /** Held only while reading/writing a downloaded cache file and the single-flight map. */
    private val downloadMutex = Mutex()
    /** Held only while a destructive restore writes local data (never across a download). */
    private val restoreMutex = Mutex()
    /** Guards building/reading/writing the emitted pending upload, independent of downloads. */
    private val uploadMutex = Mutex()
    /** One in-flight [prepare] per backup id, so tapping the same backup twice reuses it. */
    private val preparing = HashMap<String, File>()
    /** How many cached download files to keep at most. */
    private const val CACHE_MAX_FILES = 3
    /** Drop cached downloads older than this many days. */
    private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    /** Total cached-download budget (the server also caps a single backup at 200 MiB). */
    private const val CACHE_MAX_TOTAL_BYTES = 400L * 1024 * 1024

    data class Preview(
        val entry: LedgerBackupApi.Entry, val file: File, val snapshot: Backup.Snapshot,
        val sourceId: String = "", val spaceId: String = "", val epoch: String = "",
        val fromCache: Boolean = false,
    )

    private fun credentials(context: Context): Pair<RuleSyncConfig, String> {
        val config = RuleSyncSettings.load(context)
        require(config.paired) { "请先连接云备份服务器" }
        CloudBackupCoordinator.requireCurrentIdentity(context)
        return config to (RuleSyncSecrets.loadToken(context) ?: error("家庭连接令牌不可用，请重新配对"))
    }
    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "${info.versionName}/$code"
    }
    /** 与规则同步共用同一条传输，避免“规则走隧道、备份走公网”这种半吊子状态。 */
    private fun transport(context: Context): FamilyTransport =
        FamilyTransportProvider.select(context, RuleSyncSettings.load(context))

    private fun directory(context: Context) = File(context.filesDir, "ledger-backups").apply {
        require(isDirectory || mkdirs()) { "无法创建私有备份目录" }
    }
    fun lastBackup(context: Context): String {
        val raw = context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE)
            .getString(successKey(RuleSyncSettings.load(context)), null) ?: return "尚未备份"
        // Newer builds store "id|text" so a deleted backup can be forgotten; old builds stored text only.
        return raw.substringAfter('|', raw).ifBlank { "尚未备份" }
    }

    private fun successKey(config: RuleSyncConfig) = "success_${config.spaceId}_${config.epoch}_${config.deviceId}"

    /** 断网时的本地兜底不能报一份已经删掉的备份。 */
    private fun forgetDeletedLastBackup(context: Context, config: RuleSyncConfig, ids: List<String>) {
        val prefs = context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE)
        val key = successKey(config)
        val storedId = prefs.getString(key, null)?.substringBefore('|', "")
        if (storedId.isNullOrBlank() || ids.none { it == storedId }) return
        prefs.edit().remove(key).commit()
    }

    suspend fun backupNow(context: Context): LedgerBackupApi.Entry = withContext(Dispatchers.IO) { uploadMutex.withLock {
        val (config, token) = credentials(context)
        val dir = directory(context)
        val pending = File(dir, "pending-upload.json")
        var manifest = if (pending.exists()) MiniJson.decode(pending.readText()) as? Map<*, *> else null
        // A different family space never receives an old family's pending snapshot implicitly.
        if (manifest != null && (manifest["spaceId"] != config.spaceId || manifest["epoch"] != config.epoch || manifest["sourceId"] != config.deviceId)) {
            require(pending.renameTo(File(dir, "pending-${UUID.randomUUID()}.json"))) { "无法保留旧家庭待传记录" }
            manifest = null
        }
        val id = (manifest?.get("id") as? String)?.also { UUID.fromString(it) } ?: UUID.randomUUID().toString()
        val file = File(dir, "upload-$id.json")
        if (manifest == null) {
            val json = Backup.encode(Backup.decode(BackupStore.exportJson(context)).copy(settings = null))
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size.toLong() in 1..LedgerBackupApi.MAX_BYTES) { "账本快照超过200MiB限制" }
            durableWrite(file, bytes)
            durableWrite(pending, MiniJson.encode(mapOf("id" to id, "spaceId" to config.spaceId, "epoch" to config.epoch, "sourceId" to config.deviceId,
                "appVersion" to appVersion(context))).toByteArray())
        }
        require(file.exists()) { "待上传快照缺失，请检查本机私有备份" }
        Backup.decode(file.readText())
        val version = manifest?.get("appVersion") as? String ?: appVersion(context)
        val result = LedgerBackupApi.upload(config, token, id, file, version, transport(context))
        require(context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE).edit()
            .putString(successKey(config), "${result.id}|${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(java.time.OffsetDateTime.parse(result.uploadedAt).toInstant().toEpochMilli()))} · ${result.billCount} 笔").commit()) { "上传成功，本机状态未能保存" }
        // Keep the completed snapshot as a local recovery point; only its pending marker is disposable.
        pending.delete()
        result
    } }

    /** Read-only network list. Holds no download/restore lock so a slow backup never blocks it. */
    suspend fun list(context: Context, offset: Int = 0): LedgerBackupApi.Page = withContext(Dispatchers.IO) {
        val (config, token) = credentials(context)
        LedgerBackupApi.list(config, token, offset, transport(context))
    }

    /** Marks the given backups deleted on the server and drops the matching local download copies. */
    suspend fun delete(context: Context, operationId: String, ids: List<String>): LedgerBackupApi.DeleteResult =
        withContext(Dispatchers.IO) {
            require(ids.isNotEmpty()) { "请选择要删除的备份" }
            require(ids.size <= LedgerBackupApi.DELETE_BATCH) { "一次最多删除${LedgerBackupApi.DELETE_BATCH}份备份" }
            UUID.fromString(operationId)
            val (config, token) = credentials(context)
            val result = LedgerBackupApi.delete(config, token, operationId, ids, transport(context))
            ids.forEach { File(directory(context), cacheName(it)).delete() }
            // 最新备份可能刚被删掉，别让首页继续显示缓存里的那份。
            CloudBackupSession.reset()
            forgetDeletedLastBackup(context, config, ids)
            result
        }

    /** Re-reads one backup so a backup deleted elsewhere cannot be restored from a stale list. */
    suspend fun status(context: Context, entry: LedgerBackupApi.Entry): LedgerBackupApi.Entry =
        withContext(Dispatchers.IO) {
            val (config, token) = credentials(context)
            LedgerBackupApi.status(config, token, entry.id, transport(context))
        }

    /**
     * Downloads + decodes one backup for preview (AA1/AA2/AA4). Reuses a cache copy only when
     * a fresh server `status` still reports the same SHA and the on-disk copy matches that SHA,
     * so a backup re-uploaded/replaced elsewhere is never previewed from a stale file. Progress
     * is reported as cumulative received bytes; [operation] makes the download cancellable.
     * Holds no long-lived shared lock: single-flight is tracked in [preparing] by id.
     */
    suspend fun prepare(
        context: Context,
        entry: LedgerBackupApi.Entry,
        operation: DownloadOperation? = null,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): Preview = withContext(Dispatchers.IO) {
        val (config, token) = credentials(context)
        val dir = directory(context)
        val target = File(dir, cacheName(entry.id))

        // AA4 cache reuse: trust an existing file only if its bytes still hash to the entry.
        val cached = withContext(Dispatchers.IO) {
            target.exists() && target.length() == entry.sizeBytes && runCatching { LedgerBackupApi.sha256(target) == entry.sha256 }.getOrDefault(false)
        }
        var fromCache = cached
        if (cached) {
            // Verify the server still has this exact snapshot before previewing from cache.
            val live = runCatching { LedgerBackupApi.status(config, token, entry.id, transport(context)) }.getOrNull()
            if (live == null || live.sha256 != entry.sha256 || live.sizeBytes != entry.sizeBytes) {
                target.delete(); fromCache = false
            }
        }
        if (!fromCache) {
            downloadMutex.withLock {
                // Re-check under lock: a concurrent prepare may have just finished this id.
                val again = target.exists() && target.length() == entry.sizeBytes &&
                    runCatching { LedgerBackupApi.sha256(target) == entry.sha256 }.getOrDefault(false)
                if (!again) {
                    preparing[entry.id] = target
                    try {
                        if (onProgress != null && operation != null) {
                            operation.progressListener = { received -> onProgress(received, entry.sizeBytes) }
                        }
                        LedgerBackupApi.download(config, token, entry, target, transport(context), operation)
                    } finally {
                        preparing.remove(entry.id)
                    }
                }
            }
            fromCache = false
        }
        // Decode outside the download lock (parsing a multi-MB JSON need not block other ids).
        val snapshot = Backup.decode(target.readText(Charsets.UTF_8))
        require(snapshot.bills.size.toLong() == entry.billCount) { "备份账单数与索引不一致" }
        pruneCache(dir, keep = entry.id)
        Preview(entry, target, snapshot, config.deviceId, config.spaceId, config.epoch, fromCache)
    }

    /** AA5: restore only after a second server status + on-disk SHA check; writes are serialised. */
    suspend fun restore(context: Context, preview: Preview, mode: BackupStore.Mode, restoreConfiguration: Boolean = false): BackupStore.LedgerRestoreResult =
        withContext(Dispatchers.IO) { restoreMutex.withLock {
            val (config, token) = credentials(context)
            require(preview.sourceId == config.deviceId && preview.spaceId == config.spaceId && preview.epoch == config.epoch) {
                "备份来源或服务器空间已变化，请重新打开历史备份"
            }
            // The server is the authority on whether this snapshot still exists (someone else's
            // device may have removed or replaced it); re-read right before the destructive write.
            val live = LedgerBackupApi.status(config, token, preview.entry.id, transport(context))
            require(live.sha256 == preview.entry.sha256 && live.sizeBytes == preview.entry.sizeBytes) { "服务器上的备份已变化，请重新下载预览" }
            require(preview.file.length() == preview.entry.sizeBytes && LedgerBackupApi.sha256(preview.file) == preview.entry.sha256) { "恢复前校验失败，请重新下载" }
            // restoreLedger saves the pre-restore snapshot first and stops if that fails.
            BackupStore.restoreLedger(context, preview.file.readText(Charsets.UTF_8), mode, restoreConfiguration)
        } }

    private fun cacheName(id: String) = "download-$id.json"

    /** Keeps the [keep] id plus the newest [CACHE_MAX_FILES]-1 others under the age/size budget. */
    private fun pruneCache(dir: File, keep: String) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles { f -> f.name.startsWith("download-") && f.name.endsWith(".json") }?.toMutableList() ?: return
        files.sortByDescending { it.lastModified() }
        var total = 0L
        val kept = ArrayList<File>()
        for (f in files) {
            val isKeep = f.name == cacheName(keep)
            val expired = now - f.lastModified() > CACHE_MAX_AGE_MS
            val overCount = kept.size >= CACHE_MAX_FILES && !isKeep
            val overSize = total + f.length() > CACHE_MAX_TOTAL_BYTES && !isKeep
            if (!isKeep && (expired || overCount || overSize)) { f.delete(); continue }
            kept.add(f); total += f.length()
        }
    }

    private fun durableWrite(target: File, bytes: ByteArray) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
            require(part.renameTo(target)) { "无法保存私有快照" }
            val descriptor = android.system.Os.open(target.parentFile!!.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        } finally { part.delete() }
    }
}
