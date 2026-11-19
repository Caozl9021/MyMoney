package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.NoteFillMode
import io.github.mymoney.data.SpecRuleStore
import io.github.mymoney.data.rulesync.RuleSyncCodec
import io.github.mymoney.data.rulesync.RuleSyncConfig
import io.github.mymoney.data.rulesync.RuleSyncCoordinator
import io.github.mymoney.data.rulesync.RuleSyncRepository
import io.github.mymoney.data.rulesync.RuleSyncKind
import io.github.mymoney.data.rulesync.RuleSyncSettings
import io.github.mymoney.data.rulesync.EmbeddedTsnetTransport
import io.github.mymoney.data.rulesync.FamilyInvite
import io.github.mymoney.data.rulesync.PendingInvite
import io.github.mymoney.data.rulesync.PendingInviteText
import io.github.mymoney.data.rulesync.TsnetIdentity
import io.github.mymoney.data.rulesync.TsnetSession
import io.github.mymoney.data.rulesync.SyncConflictEntity
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSwitch
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.InputRow
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.SecondaryButton
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 家庭规则同步：配对、手动同步与冲突状态。 */
@Composable
fun FamilySyncPage(onBack: () -> Unit, title: String = "家庭同步与备份") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RuleSyncCoordinator.state.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(RuleSyncSettings.load(context)) }
    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var pairingCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(config.deviceName.ifBlank { android.os.Build.MODEL }) }
    var localMessage by remember { mutableStateOf(if (config.paired) "已连接，可立即同步" else "") }
    var showConflicts by remember { mutableStateOf(false) }
    var inviteText by remember { mutableStateOf("") }
    var nodeStatus by remember { mutableStateOf("") }
    val initialPreview = state.initialPreview

    if (showConflicts) {
        SyncConflictPage(
            onBack = {
                showConflicts = false
                scope.launch { RuleSyncCoordinator.refreshStatus() }
            },
        )
        return
    }

    // 邀请串 → 配置。按钮与深链共用同一段，避免两条路径校验不一致。
    fun applyInvite(text: String): Boolean {
        val invite = runCatching { FamilyInvite.parse(text) }.getOrElse {
            localMessage = it.message ?: "邀请无法识别"
            return false
        }
        PendingInvite.put(invite)
        serverUrl = invite.serverUrl
        val next = RuleSyncSettings.load(context).copy(
            serverUrl = invite.serverUrl,
            ctrlUrl = invite.ctrlUrl,
            useEmbedded = true,
        )
        RuleSyncSettings.save(context, next)
        config = next
        if (invite.deviceName.isNotBlank()) deviceName = invite.deviceName
        inviteText = ""
        localMessage = "邀请已导入；授权码只在本次运行的内存里，不会写盘。"
        return true
    }

    LaunchedEffect(Unit) {
        RuleSyncCoordinator.refreshStatus()
        if (RuleSyncSettings.load(context).useEmbedded) {
            nodeStatus = TsnetSession.status(context)
        }
        // 从 `mymoney://join?invite=…` 进来时直接落配置，省掉在手机上粘一串 200 字的麻烦。
        PendingInviteText.consume()?.let { applyInvite(it) }
    }

    // 节点是在配对/同步时才真正起来的；状态行跟着协调器变化重读一次，
    // 否则一直停在打开页面那一刻的"未启动"。
    LaunchedEffect(state.busy, state.message) {
        if (RuleSyncSettings.load(context).useEmbedded) {
            nodeStatus = TsnetSession.status(context)
        }
    }

    SettingsContentPage(title, onBack) {
        InsetGroup {
            ListRow(
                title = if (config.paired) "已连接家庭服务" else "连接家庭服务",
                subtitle = config.serverUrl.ifBlank { "用同一家庭服务连接多台设备；未连接时本地规则照常使用。" },
            )
            if (config.paired) {
                ListRow(title = "待上传", subtitle = "${state.pending} 条", dividerAbove = true)
                ListRow(title = "冲突", subtitle = "${state.conflicts} 条", dividerAbove = true)
                ListRow(
                    title = "最近同步",
                    subtitle = state.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "尚未同步",
                    dividerAbove = true,
                )
            }
        }

        InsetGroup {
            ListRow(
                title = "内嵌连接",
                subtitle = if (config.useEmbedded) "走麦米自己的节点，不依赖系统 VPN，也不需要装 Tailscale。" else "关闭时走系统网络直连家庭服务（与旧版一致）。",
                trailing = {
                    AbSwitch(checked = config.useEmbedded) { checked ->
                        if (checked && !EmbeddedTsnetTransport.isAvailable()) {
                            localMessage = "此设备是 32 位，内嵌连接需要 arm64-v8a 或 x86_64。"
                        } else {
                            val next = RuleSyncSettings.load(context).copy(useEmbedded = checked)
                            RuleSyncSettings.save(context, next)
                            config = next
                            localMessage = if (checked) "已开启内嵌连接：先导入邀请串，再配对。" else "已切回系统直连。"
                            if (!checked) scope.launch {
                                val stopped = withContext(Dispatchers.IO) { runCatching { TsnetSession.shutdown() } }
                                stopped.exceptionOrNull()?.let { localMessage = "关闭内嵌连接失败：${it.message}" }
                            }
                        }
                    }
                },
                dividerAbove = true,
            )
            if (config.useEmbedded && config.paired && !TsnetIdentity.hasIdentity(context)) {
                ListRow(
                    title = "需要重新授权",
                    subtitle = "节点身份已丢失或被清除。重新导入邀请串后再同步即可，本机账本与规则不受影响。",
                    dividerAbove = true,
                )
            }
            if (config.useEmbedded) {
                ListRow(
                    title = "节点状态",
                    subtitle = nodeStatus.ifBlank { if (config.paired) "配对后自动启动" else "未启动" },
                    dividerAbove = true,
                )
            }
        }

        if (config.useEmbedded) {
            InsetGroup {
                InputRow("邀请串", inviteText, { inviteText = it }, placeholder = "MMJOIN1.…（家庭服务生成的一次性邀请）")
            }
            SecondaryButton(
                text = "导入邀请",
                onClick = { applyInvite(inviteText) },
                modifier = Modifier.fillMaxWidth(),
                enabled = inviteText.isNotBlank(),
            )
            ListRow(
                title = "说明",
                subtitle = "邀请只在本机首次入网时用一次；之后节点身份保存在应用私有目录并加密，重启直接复用。",
            )
        }

        if (!config.paired) {
            InsetGroup {
                InputRow("服务地址", serverUrl, { serverUrl = it }, placeholder = "http://<家庭服务地址>")
                InputRow("设备名称", deviceName, { deviceName = it }, placeholder = "手机", dividerAbove = true)
                InputRow("配对码", pairingCode, { pairingCode = it.uppercase() }, placeholder = "服务端显示的一次性配对码", dividerAbove = true)
            }
            ListRow(
                title = "地址说明",
                subtitle = "家庭局域网支持 HTTP；Tailscale 支持 http://<家庭服务地址>；其他地址须使用 HTTPS。",
            )
            SecondaryButton(
                text = "测试连接",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        RuleSyncCoordinator.testConnection(context)
                        nodeStatus = if (RuleSyncSettings.load(context).useEmbedded) TsnetSession.status(context) else nodeStatus
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && serverUrl.isNotBlank(),
            )
            PrimaryButton(
                text = if (state.busy) "处理中…" else "配对并连接",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            RuleSyncCoordinator.pair(context, serverUrl, pairingCode, deviceName)
                        }.onSuccess {
                            config = RuleSyncSettings.load(context)
                            pairingCode = ""
                            localMessage = "配对成功，请确认首次合并。"
                        }.onFailure { localMessage = it.message ?: "配对失败" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && serverUrl.isNotBlank() && pairingCode.isNotBlank(),
            )
        } else if (initialPreview != null || config.initialMergePending) {
            InsetGroup {
                ListRow(
                    title = "首次合并预览",
                    subtitle = initialPreview?.let {
                        "本地 ${it.localCount} 条 · 家庭端 ${it.remoteCount} 条；仅本地 ${it.localOnly} 条，仅家庭端 ${it.remoteOnly} 条，同项不同内容 ${it.different} 条。"
                    } ?: "首次同步尚未确认；确认后才会上传本地规则。",
                )
            }
            PrimaryButton(
                text = "合并并上传本地规则",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            RuleSyncCoordinator.confirmInitialSync(context)
                            RuleSyncCoordinator.syncNow(context)
                        }.onSuccess {
                            config = RuleSyncSettings.load(context)
                            localMessage = "首次同步完成"
                        }.onFailure { localMessage = it.message ?: "首次同步失败" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            SecondaryButton(
                text = "取消连接（保留本地规则）",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        RuleSyncCoordinator.cancelInitialSync(context)
                        config = RuleSyncSettings.load(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PrimaryButton(
                text = if (state.busy) "正在同步…" else "立即同步",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { RuleSyncCoordinator.syncNow(context) }
                            .onSuccess { localMessage = "" }
                            .onFailure { localMessage = it.message ?: "同步失败" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            if (state.conflicts > 0) {
                PrimaryButton(
                    text = "处理同步冲突（${state.conflicts}）",
                    onClick = { showConflicts = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SecondaryButton(
                text = "断开连接（保留本地规则）",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        RuleSyncCoordinator.disconnect(context)
                        config = RuleSyncSettings.load(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (config.paired && !config.initialMergePending) {
            FamilyBackupsPanel()
        }

        val message = localMessage.ifBlank { state.message }
        if (message.isNotBlank()) {
            ListRow(title = "状态", subtitle = message)
        }
    }
}


@Composable
private fun SyncConflictPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var views by remember { mutableStateOf(emptyList<ConflictView>()) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(reloadKey) { views = loadConflictViews() }
    SettingsContentPage("同步冲突", onBack) {
        if (views.isEmpty()) {
            InsetGroup { ListRow(title = "没有待处理冲突", subtitle = "本机与家庭端规则已一致。") }
        } else {
            views.forEach { view ->
                InsetGroup {
                    ListRow(
                        title = view.title,
                        subtitle = "本机：${view.local}\n家庭端：${view.remote}\n家庭端版本 ${view.conflict.remoteRevision}",
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = AbSpacing.m)
                            .padding(top = AbSpacing.s, bottom = AbSpacing.m),
                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
                    ) {
                        PrimaryButton(
                            text = "保留本机版本",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    busy = true
                                    runCatching { RuleSyncRepository.resolveConflictKeepLocal(view.conflict.itemId) }
                                    views = loadConflictViews()
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                        SecondaryButton(
                            text = "使用家庭端版本",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    busy = true
                                    runCatching { RuleSyncRepository.resolveConflictKeepRemote(view.conflict.itemId) }
                                    views = loadConflictViews()
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

private data class ConflictView(
    val conflict: SyncConflictEntity,
    val title: String,
    val local: String,
    val remote: String,
)

private data class PayloadInfo(val title: String, val summary: String)

private data class ParsedRemote(
    val kind: String?,
    val naturalKey: String?,
    val payload: String?,
)

private suspend fun loadConflictViews(): List<ConflictView> {
    val officialNames = SpecRuleStore.status.value.entries.associate { it.systemRuleName to it.name }
    return RuleSyncRepository.conflicts().map { conflict ->
        val remote = parseRemoteConflict(conflict.remotePayload)
        val localItem = RuleSyncRepository.localItem(conflict.itemId)
        val kind = remote.kind ?: inferKind(conflict.itemId) ?: localItem?.kind
        val naturalKey = remote.naturalKey ?: localItem?.naturalKey
        val localInfo = describeConflictPayload(
            kind,
            conflict.localPayload.ifBlank { localItem?.payload.orEmpty() },
            naturalKey,
            officialNames,
        )
        val remoteInfo = describeConflictPayload(kind, remote.payload, naturalKey, officialNames)
        ConflictView(
            conflict = conflict,
            title = localInfo?.title ?: remoteInfo?.title ?: "同步项",
            local = localInfo?.summary ?: "无本地修改",
            remote = remoteInfo?.summary ?: "家庭端内容不可读",
        )
    }
}

private fun parseRemoteConflict(raw: String): ParsedRemote {
    val map = runCatching { MiniJson.decode(raw) as? Map<*, *> }.getOrNull()
    val payloadValue = map?.get("payload")
    val payload = when (payloadValue) {
        is String -> payloadValue
        null -> null
        else -> MiniJson.encode(payloadValue)
    }
    return ParsedRemote(
        kind = map?.get("kind") as? String,
        naturalKey = map?.get("naturalKey") as? String,
        payload = payload,
    )
}

private fun inferKind(itemId: String): String? = when {
    itemId.startsWith("personal_") -> RuleSyncKind.RECOGNITION_RULE
    itemId.startsWith("merchant_") -> RuleSyncKind.MERCHANT_NOTE_RULE
    itemId.startsWith("official_") -> RuleSyncKind.OFFICIAL_RULE_SWITCH
    itemId == "note_defaults" -> RuleSyncKind.NOTE_DEFAULTS
    else -> null
}

private fun describeConflictPayload(
    kind: String?,
    payload: String?,
    naturalKey: String?,
    officialNames: Map<String, String>,
): PayloadInfo? {
    val text = payload?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        when (kind) {
            RuleSyncKind.RECOGNITION_RULE -> {
                val rule = RuleSyncCodec.personalFromPayload(text)
                PayloadInfo(
                    "自定义规则：${rule.name}",
                    "${appLabel(rule.app)} · ${billTypeLabel(rule.billType)} · 关键词「${rule.keyword}」",
                )
            }
            RuleSyncKind.MERCHANT_NOTE_RULE -> {
                val rule = RuleSyncCodec.merchantFromPayload(text)
                val mode = when (rule.mode) {
                    NoteFillMode.PRODUCT -> "使用本次商品说明"
                    NoteFillMode.FIXED -> "固定文字「${rule.text}」"
                    NoteFillMode.NONE -> "不自动填充"
                }
                PayloadInfo(
                    "商家备注：${rule.merchant}",
                    "${appLabel(rule.app)} · ${mode}",
                )
            }
            RuleSyncKind.OFFICIAL_RULE_SWITCH -> {
                val enabled = RuleSyncCodec.officialFromPayload(text)
                val name = naturalKey?.let { officialNames[it] } ?: "官方规则"
                PayloadInfo("官方规则开关", "$name · ${if (enabled) "开启" else "关闭"}")
            }
            RuleSyncKind.NOTE_DEFAULTS -> {
                val (template, productFirst) = RuleSyncCodec.noteDefaultsFromPayload(text)
                PayloadInfo(
                    "备注默认设置",
                    "模板「${template}」 · ${if (productFirst) "商品说明优先" else "固定模板优先"}",
                )
            }
            else -> PayloadInfo("同步项", "无法识别的规则类型")
        }
    }.getOrNull()
}


private fun billTypeLabel(type: String): String = when (type) {
    "Expend" -> "支出"
    "Income" -> "收入"
    "Refund" -> "退款"
    else -> type
}
