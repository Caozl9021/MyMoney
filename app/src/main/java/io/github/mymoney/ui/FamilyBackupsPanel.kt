package io.github.mymoney.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.data.BackupStore
import io.github.mymoney.data.rulesync.LedgerBackupApi
import io.github.mymoney.data.rulesync.RuleSyncCoordinator
import io.github.mymoney.data.rulesync.LedgerBackupCoordinator
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SecondaryButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun FamilyBackupsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var lastBackup by remember { mutableStateOf(LedgerBackupCoordinator.lastBackup(context)) }
    var entries by remember { mutableStateOf<List<LedgerBackupApi.Entry>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var listed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<LedgerBackupCoordinator.Preview?>(null) }
    var restoreConfiguration by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<BackupStore.Mode?>(null) }
    var recoveryPoint by remember { mutableStateOf(File(context.filesDir, "ledger-backups").listFiles()
        ?.filter { it.name.startsWith("pre-restore-") && it.extension == "json" }?.maxByOrNull { it.lastModified() }) }
    val exportRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = recoveryPoint
        if (uri != null && file != null) {
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选文件")
                    output.use { out -> file.inputStream().use { it.copyTo(out) } }
                } }.onSuccess { message = "已导出恢复前快照，可在本地备份与恢复页面使用" }
                    .onFailure { message = it.message ?: "导出恢复点失败" }
                busy = false
            }
        }
    }

    fun runOperation(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { action() }.onFailure {
                recoveryPoint = File(context.filesDir, "ledger-backups").listFiles()
                    ?.filter { file -> file.name.startsWith("pre-restore-") && file.extension == "json" }?.maxByOrNull { file -> file.lastModified() }
                message = it.message ?: "备份操作失败，可重试"
            }
            busy = false
        }
    }

    InsetGroup {
        ListRow(title = "账本快照备份", subtitle = "手动保存完整账本到家庭服务，包含资产、流水与记录历史。不会自动备份或清理历史。")
        ListRow(title = "本机最近成功备份", subtitle = lastBackup, dividerAbove = true)
    }
    PrimaryButton(
        text = if (busy) "正在处理账本备份…" else "立即备份（失败时重试原快照）",
        onClick = { runOperation {
            val entry = LedgerBackupCoordinator.backupNow(context)
            lastBackup = LedgerBackupCoordinator.lastBackup(context)
            message = "家庭备份成功：${entry.billCount} 笔账单"
        } },
        modifier = Modifier.fillMaxWidth(), enabled = !busy,
    )
    SecondaryButton(
        text = "查看家庭备份",
        onClick = { runOperation {
            val page = LedgerBackupCoordinator.list(context)
            entries = page.items; offset = page.nextOffset; hasMore = page.hasMore; listed = true
            message = if (entries.isEmpty()) "家庭服务尚无账本备份" else "已读取家庭备份列表"
        } },
        modifier = Modifier.fillMaxWidth(), enabled = !busy,
    )
    if (listed) {
        entries.forEach { entry ->
            InsetGroup {
                ListRow(title = "${entry.deviceName} · ${entry.billCount} 笔", subtitle = "${backupDate(entry.snapshotAt)}\n${entry.appVersion} · ${entry.sizeBytes / 1024} KiB")
                SecondaryButton(
                    text = "下载并预览恢复",
                    onClick = { runOperation {
                        preview = LedgerBackupCoordinator.prepare(context, entry)
                        restoreConfiguration = false
                        message = "下载与摘要校验通过，请选择恢复方式"
                    } },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                )
            }
        }
        if (hasMore) SecondaryButton(
            text = "加载更多备份", onClick = { runOperation {
                val page = LedgerBackupCoordinator.list(context, offset)
                entries = (entries + page.items).distinctBy { it.id }; offset = page.nextOffset; hasMore = page.hasMore
            } }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
        )
    }
    if (recoveryPoint != null) SecondaryButton(
        text = "导出最近恢复前快照",
        onClick = { exportRecovery.launch("MyMoney-pre-restore.json") },
        modifier = Modifier.fillMaxWidth(), enabled = !busy,
    )
    if (message.isNotBlank()) ListRow(title = "账本备份状态", subtitle = message)

    val current = preview
    if (current != null && mode == null) AlertDialog(
        onDismissRequest = { if (!busy) preview = null },
        title = { Text("账本恢复预览") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("来源：${current.entry.deviceName}\n日期：${backupDate(current.entry.snapshotAt)}\n账单 ${current.snapshot.bills.size} 笔，识别记录 ${current.snapshot.history.orEmpty().size} 条、删除记录 ${current.snapshot.deletions.orEmpty().size} 条，资产账户 ${current.snapshot.assets?.accounts.orEmpty().size} 个。\n\n合并会跳过已有账单；覆盖会替换当前账本。恢复前将保存本机快照。当前规则、分类与备注设置、设备权限及家庭连接默认保留。")
            if (current.snapshot.ledger != null) Row {
                Checkbox(checked = restoreConfiguration, onCheckedChange = { restoreConfiguration = it })
                Text("同时恢复分类、记账周期和备注配置；备注规则会作为本机修改参与家庭同步。")
            }
        } },
        confirmButton = { TextButton(onClick = { mode = BackupStore.Mode.MERGE }) { Text("选择合并") } },
        dismissButton = { Column {
            TextButton(onClick = { mode = BackupStore.Mode.REPLACE }) { Text("选择覆盖") }
            TextButton(onClick = { preview = null }) { Text("取消") }
        } },
    )
    val selected = mode
    if (current != null && selected != null) AlertDialog(
        onDismissRequest = { if (!busy) mode = null },
        title = { Text(if (selected == BackupStore.Mode.REPLACE) "确认覆盖本机账本？" else "确认合并账本？") },
        text = { Text((if (selected == BackupStore.Mode.REPLACE)
            "将用所选快照替换当前账单、资产与识别记录；删除日志按事件合并，保留本机日志。先保存本机恢复前快照；保存失败将停止恢复。请确认来源和日期正确。"
            else "将按账单与资产身份合并；冲突时停止并保留当前账本。先保存本机恢复前快照。") +
            if (restoreConfiguration) "\n已选择恢复个人配置；备注配置会参与家庭同步。设备权限与家庭连接保留。" else "") },
        confirmButton = { TextButton(enabled = !busy, onClick = { runOperation {
            val result = LedgerBackupCoordinator.restore(context, current, selected, restoreConfiguration)
            RuleSyncCoordinator.refreshStatus()
            message = "恢复完成：新增 ${result.stats.bills} 笔。恢复前快照已在本机保留，可导出。${result.stats.assetWarning.orEmpty()}"
            recoveryPoint = result.preRestoreFile
            preview = null; mode = null
        } }) { Text(if (busy) "正在恢复…" else "确认恢复") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { mode = null; preview = null }) { Text("取消") } },
    )
}

private fun backupDate(raw: String): String = runCatching {
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()))
}.getOrDefault(raw)
