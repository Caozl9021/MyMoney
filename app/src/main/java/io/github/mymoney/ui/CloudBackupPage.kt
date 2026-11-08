package io.github.mymoney.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.data.BackupStore
import io.github.mymoney.data.rulesync.CloudBackupAuthorizationRequiredException
import io.github.mymoney.data.rulesync.CloudBackupApi
import io.github.mymoney.data.rulesync.CloudBackupCoordinator
import io.github.mymoney.data.rulesync.DownloadCancelledException
import io.github.mymoney.data.rulesync.DownloadOperation
import io.github.mymoney.data.rulesync.EmbeddedDownloadOperation
import io.github.mymoney.data.rulesync.DirectDownloadOperation
import io.github.mymoney.data.rulesync.FamilyInvite
import io.github.mymoney.data.rulesync.LedgerBackupApi
import io.github.mymoney.data.rulesync.LedgerBackupCoordinator
import io.github.mymoney.data.rulesync.RuleSyncApiException
import io.github.mymoney.data.rulesync.RuleSyncSettings
import io.github.mymoney.data.rulesync.TsnetSession
import io.github.mymoney.ui.design.*
import io.github.mymoney.ui.scan.CameraPermissionDialog
import io.github.mymoney.ui.scan.QrScannerScreen
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CloudBackupDestination { HOME, HISTORY, CONNECTION }

/**
 * Which operation currently blocks the page. Keeping them apart means a slow backup no
 * longer freezes the offline entries (local files, connection management) and a history
 * delete cannot be mistaken for a hang on the home card.
 */
private enum class CloudBackupBusy { CONNECTION, BACKUP, HISTORY }

/** AA2 preview preparation state machine, shown while a backup file is being fetched. */
private enum class PreparePhase { IDLE, DOWNLOADING, VERIFYING, PARSING, READY }

/** All cloud subpages share the SettingsScreen route stack and system-back handler. */
@Composable
internal fun CloudBackupPage(
    destination: CloudBackupDestination,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenLocalFiles: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(RuleSyncSettings.load(context)) }
    var verified by remember { mutableStateOf(false) }
    var needsAuthorization by remember { mutableStateOf(!config.paired) }
    var busyKind by remember { mutableStateOf<CloudBackupBusy?>(null) }
    val busy = busyKind != null
    var message by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // 扫码：先本地扫出一串 → 校验/确认后才 connect（确认前不联网、不改配置）。
    var showScanner by remember { mutableStateOf(false) }
    var scannedText by remember { mutableStateOf<String?>(null) }
    var scannedError by remember { mutableStateOf("") }
    var showPermissionHint by remember { mutableStateOf(false) }
    var lastBackup by remember { mutableStateOf(LedgerBackupCoordinator.lastBackup(context)) }
    var enrollment by remember { mutableStateOf<CloudBackupCoordinator.Enrollment?>(null) }
    var enrollmentLocked by remember { mutableStateOf(false) }
    var submittedSource by remember { mutableStateOf<CloudBackupApi.Source?>(null) }
    var sourceToReplace by remember { mutableStateOf<CloudBackupApi.Source?>(null) }
    var newName by remember { mutableStateOf(CloudBackupCoordinator.deviceName(context)) }
    var showSignOut by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LedgerBackupApi.Entry>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var historyLoaded by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<LedgerBackupCoordinator.Preview?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    // AA1/AA2: metadata-only detail first; the file is fetched only after "恢复此备份".
    var detailEntry by remember { mutableStateOf<LedgerBackupApi.Entry?>(null) }
    var preparePhase by remember { mutableStateOf(PreparePhase.IDLE) }
    var progressReceived by remember { mutableStateOf(0L) }
    var progressTotal by remember { mutableStateOf(0L) }
    var activeOperation by remember { mutableStateOf<DownloadOperation?>(null) }

    fun handleFailure(error: Throwable) {
        if (error is CancellationException) throw error
        // 断网/服务器不可达时别把系统的英文异常原样丢给用户。
        message = when (error) {
            is java.net.UnknownHostException -> "解析不到服务器地址，请检查网络或 DNS 后重试"
            is java.net.ConnectException -> "连不上服务器，请检查网络后重试；不影响本机账本"
            is java.net.SocketTimeoutException -> "连接服务器超时，请稍后重试"
            else -> error.message ?: "操作失败，请重试"
        }
        if ((error is RuleSyncApiException && error.code in listOf(401, 403)) || error is CloudBackupAuthorizationRequiredException) {
            verified = false; needsAuthorization = true; enrollment = null; sourceToReplace = null
            enrollmentLocked = false; submittedSource = null
        }
    }
    fun operate(kind: CloudBackupBusy = CloudBackupBusy.CONNECTION, action: suspend () -> Unit) {
        if (busy) return
        busyKind = kind; message = ""
        scope.launch {
            try { action() } catch (e: Exception) { handleFailure(e) }
            finally { config = RuleSyncSettings.load(context); busyKind = null }
        }
    }
    /** AA1/AA2: fetch + preview one backup only after the user asked to restore it. */
    fun startPrepare(entry: LedgerBackupApi.Entry) {
        if (busyKind != null) return
        busyKind = CloudBackupBusy.HISTORY; message = ""
        preparePhase = PreparePhase.DOWNLOADING
        progressReceived = 0L; progressTotal = entry.sizeBytes
        // Direct transport uses a disconnectable connection op; embedded uses the Go registry op.
        val operation: DownloadOperation = if (RuleSyncSettings.load(context).useEmbedded) EmbeddedDownloadOperation() else DirectDownloadOperation()
        (operation as? EmbeddedDownloadOperation)?.register()
        activeOperation = operation
        scope.launch {
            try {
                val result = LedgerBackupCoordinator.prepare(context, entry, operation) { received, total ->
                    progressReceived = received; progressTotal = if (total > 0) total else entry.sizeBytes
                }
                preparePhase = PreparePhase.VERIFYING
                preview = result; confirmRestore = false
                preparePhase = PreparePhase.IDLE
                if (result.fromCache) message = "已使用本机缓存的备份预览"
            } catch (e: DownloadCancelledException) {
                message = "已取消下载"
            } catch (e: Exception) {
                handleFailure(e)
            } finally {
                activeOperation = null
                if (preparePhase != PreparePhase.IDLE) preparePhase = PreparePhase.IDLE
                busyKind = null
            }
        }
    }
    fun cancelPrepare() {
        activeOperation?.cancel()
        busyKind = null; preparePhase = PreparePhase.IDLE; activeOperation = null
    }
    suspend fun refreshConnected(withLatest: Boolean = true) {
        // One merged request answers identity + newest backup, so the home page never lists backups.
        val state = CloudBackupCoordinator.checkConnectionState(context)
        config = RuleSyncSettings.load(context)
        verified = true; needsAuthorization = false
        newName = config.deviceName
        if (!withLatest) return
        val latest = state.latestBackup
        // 以服务器为准：删掉的备份不该继续留在「最近成功备份」里。
        lastBackup = latest?.let { "${cloudBackupDate(it.uploadedAt)} · ${it.billCount}笔账单" } ?: "尚未备份"
    }
    suspend fun loadHistory(more: Boolean = false) {
        val result = LedgerBackupCoordinator.list(context, if (more) offset else 0)
        entries = if (more) (entries + result.items).distinctBy { it.id } else result.items
        offset = result.nextOffset; hasMore = result.hasMore; historyLoaded = true
        selected = selected.intersect(entries.map { it.id }.toSet())
    }
    suspend fun finishConnection(source: CloudBackupApi.Source?) {
        if (!enrollmentLocked) { submittedSource = source; enrollmentLocked = true }
        CloudBackupCoordinator.complete(context, submittedSource, newName)
        enrollment = null; code = ""; sourceToReplace = null
        enrollmentLocked = false; submittedSource = null
        refreshConnected()
        message = "已连接，可以备份了"
    }
    // 扫码与粘贴共用同一条连接逻辑；只有确认后才调用，此前不联网、不改配置。
    suspend fun doConnect(raw: String) {
        val result = CloudBackupCoordinator.connect(context, raw)
        code = ""
        if (result == null) refreshConnected()
        else {
            enrollment = result; newName = result.name
            enrollmentLocked = result.completing; submittedSource = result.submittedSource
            if (result.sources.isEmpty() && !result.completing) finishConnection(null)
        }
    }

    LaunchedEffect(Unit) {
        busyKind = CloudBackupBusy.CONNECTION
        try {
            // The history page loads its own list, so it only needs the cheap identity check.
            if (config.paired) refreshConnected(withLatest = destination != CloudBackupDestination.HISTORY)
            else {
                enrollment = CloudBackupCoordinator.resumeEnrollment(context)
                enrollment?.let { newName = it.name; enrollmentLocked = it.completing; submittedSource = it.submittedSource }
                config = RuleSyncSettings.load(context)
                if (enrollment == null && config.paired) refreshConnected(withLatest = destination != CloudBackupDestination.HISTORY)
            }
        } catch (e: Exception) { handleFailure(e) }
        finally { busyKind = null }
    }
    LaunchedEffect(destination) {
        if (destination != CloudBackupDestination.HISTORY) return@LaunchedEffect
        // Wait for the single verification above instead of racing it on the shared mutex.
        while (busyKind == CloudBackupBusy.CONNECTION) delay(50)
        if (!historyLoaded && (verified || config.paired)) operate(CloudBackupBusy.HISTORY) { loadHistory() }
    }

    if (showScanner) {
        QrScannerScreen(
            onDecoded = { raw ->
                showScanner = false
                scannedText = raw
                scannedError = ""
            },
            onCancel = { showScanner = false },
            onPermissionDenied = { showScanner = false; showPermissionHint = true },
        )
        return
    }
    scannedText?.let { raw ->
        val invite = remember(raw) { runCatching { FamilyInvite.parse(raw) }.getOrNull() }
        val okCode = invite != null && FamilyInvite.isConnectCode(invite)
        AppAlertDialog(
            title = if (okCode) "扫描到连接码" else "这不是有效的连接码",
            message = if (okCode) "设备：${invite!!.deviceName.ifBlank { "未命名" }}\n服务器：${invite.serverUrl}\n\n确认后将连接并登记此设备；接替原设备时可保留历史备份。" else "请确认扫的是服务器管理页「连接新设备」生成的二维码，或改用粘贴连接码。",
            onDismiss = { scannedText = null; scannedError = "" },
            confirmLabel = if (okCode) "连接" else "重扫",
            confirmEnabled = !busy,
            onConfirm = {
                if (okCode) {
                    val toConnect = raw; scannedText = null
                    operate { doConnect(toConnect) }
                } else scannedText = null
            },
        )
        return
    }
    if (showPermissionHint) {
        CameraPermissionDialog(onDismiss = { showPermissionHint = false })
    }

    SettingsContentPage(when (destination) {
        CloudBackupDestination.HISTORY -> "历史备份"
        CloudBackupDestination.CONNECTION -> "连接管理"
        CloudBackupDestination.HOME -> "云备份"
    }, onBack = onBack) {
        if (destination == CloudBackupDestination.HOME) {
            InsetGroup {
                ListRow(title = when { busy -> "正在处理…"; verified -> "已连接到我的服务器"; needsAuthorization -> "连接云备份"; else -> "等待验证连接" },
                    subtitle = if (config.deviceName.isNotBlank()) "当前设备：${config.deviceName}" else "账本备份到自己的服务器")
                if (!needsAuthorization) ListRow(title = "最近成功备份", subtitle = lastBackup, dividerAbove = true)
            }
            if (!needsAuthorization && enrollment == null) {
                PrimaryButton(text = "立即备份", busy = busy, enabled = !busy && verified,
                    onClick = { operate(CloudBackupBusy.BACKUP) {
                        val entry = LedgerBackupCoordinator.backupNow(context)
                        lastBackup = "${cloudBackupDate(entry.uploadedAt)} · ${entry.billCount}笔账单"
                        message = "备份成功，已保存在服务器"
                    } }, modifier = Modifier.fillMaxWidth())
                InsetGroup { ListRow(title = "历史备份", subtitle = "只显示这台设备的备份", chevron = true,
                    onClick = { if (!busy && verified) { onOpenHistory() } }) }
                if (!verified && !busy) SecondaryButton(text = "重试连接", onClick = { operate { refreshConnected() } }, modifier = Modifier.fillMaxWidth())
            }
            // Below the primary flow, right-aligned, and never gated on `busy`: the local backup
            // file screen and connection management stay reachable while the server is unreachable.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { menuExpanded = true }) { Text("更多") }
            }
            if (menuExpanded) {
                AppActionSheet(
                    title = "更多",
                    actions = listOf(
                        AppAction("连接管理") { onOpenConnection() },
                        AppAction("本地备份文件") { onOpenLocalFiles() },
                    ),
                    onDismiss = { menuExpanded = false },
                )
            }
        }
        if ((destination == CloudBackupDestination.HOME || destination == CloudBackupDestination.CONNECTION) && needsAuthorization && enrollment == null) {
            SecondaryButton(text = "扫码连接", enabled = !busy, onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth())
            InsetGroup { InputRow(label = "连接码", value = code, onValueChange = { code = it }, placeholder = "粘贴服务器生成的完整连接码") }
            SecondaryButton(text = "粘贴连接码", enabled = !busy, onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) message = "剪贴板中没有连接码" else code = text
            }, modifier = Modifier.fillMaxWidth())
            PrimaryButton(text = "连接", busy = busy, enabled = !busy && code.isNotBlank(), onClick = { operate { doConnect(code) } }, modifier = Modifier.fillMaxWidth())
            GroupFooter("扫码或粘贴服务器管理页生成的连接码即可连接。普通断网无需重新配对，重新安装可接替原设备并保留历史。")
        }
        enrollment?.let { pending ->
            if (enrollmentLocked) {
                InsetGroup { ListRow(title = "正在确认原连接请求", subtitle =
                    "设备名称：$newName\n" + (submittedSource?.let { "接替来源：${it.name} · ${it.deviceId.take(8)}" } ?: "添加新设备") +
                    "\n请求已提交，确认结果前不能修改名称或接替目标。") }
                PrimaryButton(text = "重试确认连接", enabled = !busy, onClick = { operate { finishConnection(submittedSource) } }, modifier = Modifier.fillMaxWidth())
            } else {
                GroupHeader("这是新设备，还是重新安装？")
                InsetGroup { InputRow(label = "设备名称", value = newName, onValueChange = { if (!busy) newName = it.take(80) }, placeholder = "我的手机") }
                PrimaryButton(text = "添加新设备", enabled = !busy, onClick = { operate { finishConnection(null) } }, modifier = Modifier.fillMaxWidth())
                GroupHeader("接替原设备")
                InsetGroup {
                    pending.sources.forEachIndexed { index, source ->
                        ListRow(title = source.name, subtitle = "${source.deviceId.take(8)} · 最近使用 ${source.lastSeenAt?.let(::cloudBackupDate) ?: "未知"}" +
                            (if (source.lastBackupAt != null) "\n最近备份 ${cloudBackupDate(source.lastBackupAt)}" else "\n尚无备份") +
                            (if (source.revoked) " · 已退出" else "") + (if (source.archived) " · 已归档" else ""),
                            dividerAbove = index > 0, chevron = true, onClick = { if (!busy) sourceToReplace = source })
                    }
                }
                TextButton(enabled = !busy, onClick = { operate { CloudBackupCoordinator.cancelEnrollment(context); enrollment = null; needsAuthorization = true } }) { Text("取消连接") }
            }
        }
        if (destination == CloudBackupDestination.CONNECTION) {
            if (config.paired) {
                if (!needsAuthorization) {
                InsetGroup { InputRow(label = "设备名称", value = newName, onValueChange = { newName = it.take(80) }) }
                SecondaryButton(text = "保存名称", enabled = !busy && newName.isNotBlank(), onClick = { operate {
                    CloudBackupCoordinator.rename(context, newName); message = "设备名称已更新"
                } }, modifier = Modifier.fillMaxWidth())
                }
                InsetGroup { ListRow(title = "退出连接", subtitle = "撤销本安装访问，服务器历史备份保留", chevron = true,
                    onClick = { if (!busy) showSignOut = true }) }
            }
            InsetGroup {
                // Offline entries stay enabled: a failed connection must not lock the user out
                // of the local backup file screen or diagnostics.
                ListRow(title = "本地备份文件", subtitle = "导出备份或从文件恢复", chevron = true, onClick = onOpenLocalFiles)
                ListRow(title = "诊断信息", subtitle = if (showDiagnostics) diagnostics else "连接异常时查看", dividerAbove = true,
                    chevron = true, onClick = {
                        showDiagnostics = !showDiagnostics
                        if (showDiagnostics) operate {
                            diagnostics = withContext(Dispatchers.IO) { "服务：${config.serverUrl}\n设备：${config.deviceId.take(8)}\n网络节点：${TsnetSession.status(context)}" }
                        }
                    })
            }
        }
        if (destination == CloudBackupDestination.HISTORY) {
            GroupFooter("只有这台设备的历史备份。恢复会先保存当前账本，确认后才替换。")
            if (historyLoaded && entries.isEmpty()) InsetGroup { ListRow(title = "尚无备份", subtitle = "返回云备份页面，点击立即备份") }
            if (entries.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (managing) TextButton(enabled = !busy, onClick = {
                        val all = entries.map { it.id }.toSet()
                        selected = if (selected.size == all.size) emptySet() else all.take(LedgerBackupApi.DELETE_BATCH).toSet()
                        if (all.size > LedgerBackupApi.DELETE_BATCH) message = "一次最多删除${LedgerBackupApi.DELETE_BATCH}份，已选择前${LedgerBackupApi.DELETE_BATCH}份"
                    }) { Text("全选") }
                    if (verified) TextButton(enabled = !busy, onClick = {
                        managing = !managing; if (!managing) selected = emptySet()
                    }) { Text(if (managing) "完成" else "管理") }
                }
            }
            InsetGroup { entries.forEachIndexed { index, entry ->
                val picked = entry.id in selected
                ListRow(title = cloudBackupDate(entry.snapshotAt), subtitle = "${entry.billCount}笔 · ${cloudBackupSize(entry.sizeBytes)}\n服务器接收：${cloudBackupDate(entry.uploadedAt)}",
                    dividerAbove = index > 0, chevron = !managing,
                    trailing = if (picked) { { Text("已选") } } else null,
                    onClick = {
                        if (managing) {
                            selected = if (picked) selected - entry.id
                            else if (selected.size >= LedgerBackupApi.DELETE_BATCH) {
                                message = "一次最多删除${LedgerBackupApi.DELETE_BATCH}份备份"; selected
                            } else selected + entry.id
                        } else if (!busy) {
                            // AA1: open metadata-only detail; no download happens here.
                            detailEntry = entry
                        }
                    })
            } }
            if (hasMore) SecondaryButton(text = "加载更多", enabled = !busy, onClick = { operate(CloudBackupBusy.HISTORY) { loadHistory(true) } }, modifier = Modifier.fillMaxWidth())
            if (!historyLoaded && !busy) SecondaryButton(text = "重试读取", onClick = { operate(CloudBackupBusy.HISTORY) { loadHistory() } }, modifier = Modifier.fillMaxWidth())
            if (managing) {
                PrimaryButton(text = if (selected.isEmpty()) "删除所选" else "删除所选（${selected.size}）",
                    enabled = !busy && selected.isNotEmpty(),
                    onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth())
                GroupFooter("只删除服务器上的备份文件，本机账本和已下载的恢复前快照不受影响。")
            }
        }
        if (message.isNotBlank()) InsetGroup { ListRow(title = "状态", subtitle = message) }
    }
    sourceToReplace?.let { source -> AppAlertDialog(
        title = "接替“${source.name}”？",
        message = "原安装将失去访问权限，历史备份完整保留。若原设备仍在使用，请确认没有选错。",
        onDismiss = { if (!busy) sourceToReplace = null },
        confirmLabel = if (enrollmentLocked) "重试确认连接" else "确认接替",
        onConfirm = { operate { finishConnection(if (enrollmentLocked) submittedSource else source) } },
        destructive = true,
        busy = busy,
    ) }
    if (showSignOut) AppAlertDialog(
        title = "退出云备份连接？",
        message = "撤销此安装的访问权限，服务器上的历史备份保留。再次连接需要授权接替原设备。",
        onDismiss = { if (!busy) showSignOut = false },
        confirmLabel = "退出连接",
        onConfirm = {
            operate {
                CloudBackupCoordinator.disconnect(context); showSignOut = false; verified = false; needsAuthorization = true
                lastBackup = "尚未备份"; entries = emptyList(); message = "已退出，历史备份保留"
            }
        },
        destructive = true,
        busy = busy,
    )
    if (confirmDelete) {
        val chosen = entries.filter { it.id in selected }
        val totalBytes = chosen.sumOf { it.sizeBytes }
        AppAlertDialog(
            title = "删除 ${chosen.size} 份服务器备份？",
            message = "将删除服务器上的 ${chosen.size} 份备份，共约 ${cloudBackupSize(totalBytes)}。删除不可恢复，也不影响本机账本与恢复前快照。",
            onDismiss = { if (!busy) confirmDelete = false },
            confirmLabel = "确认删除",
            onConfirm = {
                val ids = chosen.map { it.id }
                confirmDelete = false
                operate(CloudBackupBusy.HISTORY) {
                    val result = LedgerBackupCoordinator.delete(context, UUID.randomUUID().toString(), ids)
                    entries = entries.filterNot { it.id in ids }
                    selected = selected - ids.toSet()
                    if (entries.isEmpty() && hasMore) loadHistory()
                    message = buildString {
                        append("已删除 ${result.deletedCount} 份备份")
                        if (result.recyclePending) append("，服务器稍后回收 ${result.pendingBytes / 1024} KiB 文件")
                        else append("，已释放 ${result.recycledBytes / 1024} KiB")
                        append("；本机账本未改动")
                    }
                }
            },
            destructive = true,
            busy = busy,
            busyLabel = "正在删除…",
        )
    }
    // AA1 step 1: metadata-only detail. Nothing is downloaded until "恢复此备份".
    detailEntry?.let { entry ->
        val preparing = preparePhase != PreparePhase.IDLE
        AppDialog(title = "备份详情", onDismiss = { if (!busy && !preparing) detailEntry = null }) {
            Text("${cloudBackupDate(entry.snapshotAt)}\n账单 ${entry.billCount}笔 · ${cloudBackupSize(entry.sizeBytes)}\n服务器接收：${cloudBackupDate(entry.uploadedAt)}")
            when (preparePhase) {
                PreparePhase.DOWNLOADING -> {
                    val pct = if (progressTotal > 0) (progressReceived * 100 / progressTotal).toInt() else 0
                    Text("正在下载备份… ${cloudBackupSize(progressReceived)} / ${cloudBackupSize(progressTotal)}（$pct%）")
                }
                PreparePhase.VERIFYING -> Text("正在校验…")
                PreparePhase.PARSING -> Text("正在解析…")
                else -> Text("恢复前只读取这份备份，不会改动本机账本。")
            }
            if (preparing) {
                SecondaryButton("取消下载", onClick = { cancelPrepare() }, modifier = Modifier.fillMaxWidth())
            } else {
                AppDialogActions(
                    confirmLabel = "恢复此备份",
                    onConfirm = { val target = entry; detailEntry = null; startPrepare(target) },
                    dismissLabel = "取消",
                    onDismiss = { detailEntry = null },
                    busy = busy,
                )
            }
        }
    }
    // AA1 step 2/3: content preview after download, then final restore confirmation.
    preview?.let { current -> AppDialog(
        title = if (confirmRestore) "确认恢复此备份？" else "内容预览",
        onDismiss = { if (!busy) { preview = null; confirmRestore = false } },
    ) {
        Text("${cloudBackupDate(current.entry.snapshotAt)}\n账单 ${current.snapshot.bills.size}笔，识别记录 ${current.snapshot.history?.size ?: 0}条，账户 ${current.snapshot.assets?.accounts?.size ?: 0}个。" +
            (if (current.fromCache) "\n（本机缓存副本）" else "") + "\n\n" +
            if (confirmRestore) "将替换当前账单；备份含资产/识别记录时也会替换对应内容，不含时保留本机内容。删除日志按事件合并，配置和连接保留。会先保存恢复前快照，保存失败则停止恢复。"
            else "请核对日期和内容，下一步仍需确认。设备连接与权限不会随备份替换。")
        AppDialogActions(
            confirmLabel = if (confirmRestore) "确认恢复" else "继续恢复",
            onConfirm = {
                if (!confirmRestore) confirmRestore = true
                else operate(CloudBackupBusy.BACKUP) {
                    val restored = LedgerBackupCoordinator.restore(context, current, BackupStore.Mode.REPLACE)
                    preview = null; confirmRestore = false
                    message = "恢复完成。恢复前快照已保留，可在本地备份文件中导出。${restored.stats.assetWarning.orEmpty()}"
                }
            },
            dismissLabel = "取消",
            onDismiss = { preview = null; confirmRestore = false },
            busy = busy,
            busyLabel = "正在恢复…",
        )
    } }
}

/** 小备份也用 KiB 说人话，别把 3 KiB 说成「1 MiB」。 */
private fun cloudBackupSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes + 512 * 1024) / (1024 * 1024)} MiB"
    bytes >= 1024L -> "${(bytes + 512) / 1024} KiB"
    else -> "$bytes B"
}

private fun cloudBackupDate(raw: String): String = runCatching {
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()))
}.getOrDefault(raw)
