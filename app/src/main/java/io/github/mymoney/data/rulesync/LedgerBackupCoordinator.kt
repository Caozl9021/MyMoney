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

/** Manual operations only. Local durable pending uploads survive a lost upload receipt. */
object LedgerBackupCoordinator {
    private val mutex = Mutex()
    data class Preview(val entry: LedgerBackupApi.Entry, val file: File, val snapshot: Backup.Snapshot)

    private fun credentials(context: Context): Pair<RuleSyncConfig, String> {
        val config = RuleSyncSettings.load(context)
        require(config.paired && !config.initialMergePending) { "请先完成家庭连接与首次规则合并" }
        return config to (RuleSyncSecrets.loadToken(context) ?: error("家庭连接令牌不可用，请重新配对"))
    }
    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "${info.versionName}/$code"
    }
    private fun directory(context: Context) = File(context.filesDir, "ledger-backups").apply {
        require(isDirectory || mkdirs()) { "无法创建私有备份目录" }
    }
    fun lastBackup(context: Context): String = context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE)
        .getString("last_success", "尚未备份") ?: "尚未备份"

    suspend fun backupNow(context: Context): LedgerBackupApi.Entry = withContext(Dispatchers.IO) { mutex.withLock {
        val (config, token) = credentials(context)
        val dir = directory(context)
        val pending = File(dir, "pending-upload.json")
        var manifest = if (pending.exists()) MiniJson.decode(pending.readText()) as? Map<*, *> else null
        // A different family space never receives an old family's pending snapshot implicitly.
        if (manifest != null && (manifest["spaceId"] != config.spaceId || manifest["epoch"] != config.epoch)) {
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
            durableWrite(pending, MiniJson.encode(mapOf("id" to id, "spaceId" to config.spaceId, "epoch" to config.epoch,
                "appVersion" to appVersion(context))).toByteArray())
        }
        require(file.exists()) { "待上传快照缺失，请检查本机私有备份" }
        Backup.decode(file.readText())
        val version = manifest?.get("appVersion") as? String ?: appVersion(context)
        val result = LedgerBackupApi.upload(config, token, id, file, version)
        require(context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE).edit()
            .putString("last_success", "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(java.time.OffsetDateTime.parse(result.snapshotAt).toInstant().toEpochMilli()))} · ${result.billCount} 笔").commit()) { "上传成功，本机状态未能保存" }
        // Keep the completed snapshot as a local recovery point; only its pending marker is disposable.
        pending.delete()
        result
    } }

    suspend fun list(context: Context, offset: Int = 0): LedgerBackupApi.Page = withContext(Dispatchers.IO) { mutex.withLock {
        val (config, token) = credentials(context)
        LedgerBackupApi.list(config, token, offset)
    } }

    suspend fun preview(context: Context, entry: LedgerBackupApi.Entry): Preview = withContext(Dispatchers.IO) { mutex.withLock {
        val (config, token) = credentials(context)
        val file = File(directory(context), "download-${entry.id}.json")
        val snapshot = LedgerBackupApi.download(config, token, entry, file)
        Preview(entry, file, snapshot)
    } }

    suspend fun restore(context: Context, preview: Preview, mode: BackupStore.Mode, restoreConfiguration: Boolean = false): BackupStore.LedgerRestoreResult =
        withContext(Dispatchers.IO) { mutex.withLock {
            require(preview.file.length() == preview.entry.sizeBytes && LedgerBackupApi.sha256(preview.file) == preview.entry.sha256) { "恢复前校验失败，请重新下载" }
            BackupStore.restoreLedger(context, preview.file.readText(Charsets.UTF_8), mode, restoreConfiguration)
        } }

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
