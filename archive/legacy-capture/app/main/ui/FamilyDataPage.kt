package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.rulesync.FamilyInvite
import io.github.mymoney.data.rulesync.PendingInvite
import io.github.mymoney.data.rulesync.RuleSyncCoordinator
import io.github.mymoney.data.rulesync.RuleSyncSettings
import io.github.mymoney.data.ledgersync.LedgerImport
import io.github.mymoney.data.ledgersync.LedgerImportCoordinator
import io.github.mymoney.data.ledgersync.LedgerImportSource
import io.github.mymoney.data.ledgersync.LedgerSyncCoordinator
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.EmptyState
import io.github.mymoney.ui.design.GroupFooter
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.InlineMessage
import io.github.mymoney.ui.design.InputRow
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SecondaryButton
import io.github.mymoney.ui.design.SettingsIconBadge
import io.github.mymoney.ui.design.StatusLabel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家庭数据（W 阶段，对齐真 App 设计系统）。
 *
 * 语义边界（见 docs/FAMILY_DATA_UX_PLAN.md）：
 * - 本机账本 / 共享规则 / 历史备份：接现有能力（规则同步 + 快照备份）。
 * - 「从其他设备导入」：W3 按需导入。列出同空间家人设备 → 拉冻结快照预览 → 确认后
 *   原子导入本机（新 syncId + 来源导入身份，重复幂等、双边变更保留冲突，不建立订阅）。
 * - 未连接态只需粘贴一个「完整连接码」（MMJOIN2）：一次完成「设地址 + 网络授权 +
 *   业务配对」；若粘贴的是旧 MMJOIN1 或裸服务地址，退回单独填配对码的高级页。
 */
@Composable
fun FamilyDataPage(
    onBack: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RuleSyncCoordinator.state.collectAsStateWithLifecycle()
    val ledger by LedgerSyncCoordinator.state.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(RuleSyncSettings.load(context)) }
    var connectCode by remember { mutableStateOf("") }
    var localMessage by remember { mutableStateOf("") }
    val paired = config.paired
    val p = LocalAbPalette.current

    // 进入页面即刷新账本同步状态（待上传/最近同步），已连接时顺带监听。
    LaunchedEffect(paired) {
        if (paired) runCatching { LedgerSyncCoordinator.refreshStatus(context) }
    }

    // 连接码 → 落地址+授权，并在带业务码时立即配对。返回 true 表示连接码已处理。
    fun connectWithCode(text: String) {
        val invite = runCatching { FamilyInvite.parse(text) }.getOrElse {
            localMessage = it.message ?: "连接码无法识别"
            return
        }
        // 先把网络授权与服务地址写进内存邀请，节点起来时取用。
        PendingInvite.put(invite)
        val next = RuleSyncSettings.load(context).copy(
            serverUrl = invite.serverUrl,
            ctrlUrl = invite.ctrlUrl,
            useEmbedded = !invite.direct,
        )
        RuleSyncSettings.save(context, next)
        config = next
        val deviceName = invite.deviceName.ifBlank { config.deviceName }
        if (FamilyInvite.isConnectCode(invite)) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    RuleSyncCoordinator.pair(context, invite.serverUrl, invite.pairCode, deviceName)
                }.onSuccess {
                    config = RuleSyncSettings.load(context)
                    connectCode = ""
                    localMessage = "已连接，请确认首次合并。"
                }.onFailure { localMessage = it.message ?: "配对失败" }.let {
                    // 配对失败多半是连接码过期/失效，保留输入让用户换码重试。
                }
            }
        } else {
            // 只有网络授权没有业务配对码（旧邀请/裸地址）：转到高级页单独填配对码。
            connectCode = ""
            localMessage = "连接码缺少配对凭证，请在高级设置填写配对码。"
            onOpenRules()
        }
    }

    SettingsContentPage("家庭数据", onBack) {
        val busy = ledger.busy
        val failed = paired && (ledger.message.contains("失败") || ledger.message.contains("不可达"))
        val statusText = when {
            !paired -> "未连接"
            busy -> "同步中…"
            failed -> "同步失败"
            else -> "已同步"
        }
        val lastLine = ledger.lastSyncAt
            ?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            ?: "尚未同步"

        // ── 本机账本主卡：纯白、无渐变、无边框，靠留白分层 ──
        InsetGroup {
            Column(Modifier.fillMaxWidth().padding(AbSpacing.l)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本机账本",
                        style = AbType.footnote.copy(fontWeight = FontWeight.Medium),
                        color = p.secondaryLabel,
                        modifier = Modifier.weight(1f),
                    )
                    StatusLabel(text = statusText, emphasized = paired && !failed && !busy)
                }
                Spacer(Modifier.height(AbSpacing.s))
                Text(
                    if (failed) "服务暂不可达" else if (!paired) "未连接家庭服务" else lastLine,
                    style = AbType.title2,
                    color = p.label,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (paired) "待同步 ${ledger.pending} 条 · 仅同步这台设备的账本" else "连接后可同步这台设备的账本，但不写进家人设备",
                    style = AbType.footnote,
                    color = p.secondaryLabel,
                )
                Spacer(Modifier.height(AbSpacing.m))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    if (paired) {
                        PrimaryButton(
                            text = if (failed) "重试同步" else "立即同步",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { LedgerSyncCoordinator.syncNow(context) }
                                    runCatching { LedgerSyncCoordinator.refreshStatus(context) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            busy = busy,
                        )
                        SecondaryButton("同步详情", onOpenDetail, Modifier.weight(1f))
                    }
                }
            }
        }

        // ── 未连接：只需粘贴一个完整连接码 ──
        if (!paired) {
            InsetGroup {
                InputRow(
                    label = "连接码",
                    value = connectCode,
                    onValueChange = { connectCode = it; localMessage = "" },
                    placeholder = "MMJOIN2.…",
                )
            }
            PrimaryButton(
                text = if (busy) "正在连接…" else "连接",
                onClick = { connectWithCode(connectCode) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && connectCode.isNotBlank(),
                busy = busy,
            )
            GroupFooter("一个连接码即可完成连接；没有完整码时可在「同步详情」里手动填写地址与配对码。")
        }

        if (localMessage.isNotBlank()) {
            ListRow(title = "状态", subtitle = localMessage)
        }

        GroupHeader("其他数据")
        InsetGroup {
            ListRow(
                title = "共享规则",
                subtitle = if (state.conflicts > 0) "有 ${state.conflicts} 处差异待处理" else "个人规则、备注规则与官方启停",
                leading = { SettingsIconBadge("规则文件", AbIcons.Layers) },
                chevron = true,
                onClick = onOpenRules,
            )
            ListRow(
                title = "从其他设备导入",
                subtitle = "按需从家人设备导入账单",
                leading = { SettingsIconBadge("导出", AbIcons.Export) },
                chevron = true,
                dividerAbove = true,
                onClick = onOpenImport,
            )
            ListRow(
                title = "历史备份",
                subtitle = "某一刻的完整副本，用于误删 / 换机恢复",
                leading = { SettingsIconBadge("备份", AbIcons.Clock) },
                chevron = true,
                dividerAbove = true,
                onClick = onOpenBackups,
            )
        }

        // 连接维护（单码 / 断开 / 内嵌）沿用原家庭同步页，从主卡"同步详情"进入。
        if (paired) GroupFooter("同步只上传本机的规则与账本副本，不会写进家人的设备。")
    }
}

/**
 * 「从其他设备导入」：W3 按需导入。列出同空间可导入的家人设备 → 选中后拉冻结快照预览 →
 * 用户确认后原子导入到本机（新 syncId + 来源导入身份，重复导入幂等、双边变更保留冲突）。
 * 只在显式操作时发生，不建立订阅。
 */
@Composable
fun FamilyImportPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<LedgerImportSource>?>(null) }
    var preview by remember { mutableStateOf<LedgerImport.Preview?>(null) }
    var previewSource by remember { mutableStateOf<LedgerImportSource?>(null) }

    fun loadSources() {
        busy = true; message = "正在读取可导入的设备…"; messageError = false
        scope.launch {
            val result = runCatching { LedgerImportCoordinator.sources(context) }
            busy = false
            result.onSuccess {
                sources = it
                message = if (it.isEmpty()) "暂无可导入的设备" else ""
            }.onFailure {
                sources = emptyList()
                message = it.message ?: "读取失败"; messageError = true
            }
        }
    }

    LaunchedEffect(Unit) { loadSources() }

    fun openPreview(source: LedgerImportSource) {
        busy = true; message = "正在读取“${source.deviceName}”的账本快照…"; messageError = false
        scope.launch {
            val result = runCatching { LedgerImportCoordinator.preview(context, source) }
            busy = false
            result.onSuccess { preview = it; previewSource = source; message = "" }
                .onFailure { message = it.message ?: "预览失败"; messageError = true }
        }
    }

    fun commitImport(source: LedgerImportSource) {
        busy = true; message = "正在导入…"; messageError = false
        scope.launch {
            val result = runCatching { LedgerImportCoordinator.commit(context, source) }
            busy = false
            result.onSuccess {
                val added = it.count(LedgerImport.State.READY)
                preview = null; previewSource = null
                loadSources()
                message = "已导入 $added 条账单"
            }.onFailure { message = it.message ?: "导入失败，本次未写入"; messageError = true }
        }
    }

    val pv = preview
    SettingsContentPage("从其他设备导入", onBack) {
        if (pv == null) {
            if (sources.isNullOrEmpty()) {
                InsetGroup {
                    EmptyState(
                        icon = AbIcons.Export,
                        title = if (busy) "正在读取…" else "暂无可导入的设备",
                        message = "家人设备连接并同步过一次账本后，它的账本快照才会出现在这里。",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                GroupHeader("可导入的设备")
                InsetGroup {
                    sources!!.forEachIndexed { index, source ->
                        ListRow(
                            title = source.deviceName,
                            subtitle = "${source.itemCount} 条账本记录",
                            leading = { SettingsIconBadge(source.deviceName, AbIcons.Home) },
                            chevron = true,
                            dividerAbove = index > 0,
                            onClick = { if (!busy) openPreview(source) },
                        )
                    }
                }
                GroupFooter("导入会把对方账本快照复制到本机。之后双方各自独立，不会互相覆盖；对方的删除也不会删掉你已导入的记录。")
            }
        } else {
            val ready = pv.count(LedgerImport.State.READY)
            val dup = pv.count(LedgerImport.State.DUPLICATE)
            val conflict = pv.count(LedgerImport.State.CONFLICT)
            GroupHeader("将要导入 · ${pv.sourceDeviceName}")
            InsetGroup {
                ListRow(title = "新增账单", trailing = { Text("$ready", style = AbType.body, color = LocalAbPalette.current.accent) })
                if (dup > 0) ListRow(title = "已导入过（跳过）", subtitle = "同一条不会重复导入", dividerAbove = true, trailing = { Text("$dup", style = AbType.body, color = LocalAbPalette.current.secondaryLabel) })
                if (conflict > 0) ListRow(title = "内容有变化（保留现状）", subtitle = "对方改过，本机已导入的不会被覆盖", dividerAbove = true, trailing = { Text("$conflict", style = AbType.body, color = LocalAbPalette.current.secondaryLabel) })
            }
            if (ready == 0) {
                GroupFooter("没有需要新增的记录。")
            } else {
                GroupFooter("导入后本机独立保存，不跟随对方的后续修改。")
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (busy) "正在导入…" else "导入 $ready 条到本机",
                onClick = { previewSource?.let { commitImport(it) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && ready > 0,
                busy = busy,
                busyLabel = "正在导入…",
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "返回",
                onClick = {
                    if (!busy) { preview = null; previewSource = null; message = "" }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        if (message.isNotBlank()) InlineMessage(text = message, isError = messageError)
    }
}
