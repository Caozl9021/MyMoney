package io.github.mymoney.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mymoney.data.rulesync.RuleSyncSettings
import io.github.mymoney.data.ledgersync.LedgerSyncCoordinator
import io.github.mymoney.ui.design.GroupFooter
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InlineMessage
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.PrimaryButton
import io.github.mymoney.ui.design.StatusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 同步详情：只讲「本机账本」的副本同步状态，不掺规则、不掺备份。 */
@Composable
fun LedgerSyncDetailPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LedgerSyncCoordinator.state.collectAsStateWithLifecycle()
    var localMessage by remember { mutableStateOf("") }
    // 配置必须跟着刷新：原来只 remember 一次，已连接也会显示「尚未连接家庭服务」。
    var config by remember { mutableStateOf(RuleSyncSettings.load(context)) }
    // 刷新状态是异步的：刷新回来之前不显示初始占位「尚未连接家庭服务」，避免误导读两页矛盾。
    var refreshed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        config = RuleSyncSettings.load(context)
        LedgerSyncCoordinator.refreshStatus(context)
        refreshed = true
    }

    SettingsContentPage("同步详情", onBack) {
        if (!config.paired) {
            GroupHeader("本机账本")
            InlineMessage("尚未连接家庭服务。请先在「家庭数据」用连接码完成配对，本机账本才能同步到家庭服务里的本机副本。")
            GroupFooter("同步只把本机账本写进家庭服务里的「本机副本」，不会改动家人的设备。")
            return@SettingsContentPage
        }

        GroupHeader("本机账本")
        InsetGroup {
            val lastSync = state.lastSyncAt
            val statusText = when {
                state.busy -> "同步中…"
                state.message.contains("失败") || state.message.contains("不可达") -> "同步失败"
                lastSync != null -> "已同步"
                else -> "待同步"
            }
            ListRow(title = "状态", trailing = {
                StatusLabel(statusText, emphasized = statusText == "已同步")
            })
            ListRow(title = "待上传", subtitle = "${state.pending} 条")
            if (state.conflicts > 0) {
                ListRow(title = "待处理冲突", subtitle = "${state.conflicts} 条", destructive = true)
            }
            if (lastSync != null) {
                ListRow(
                    title = "最近同步",
                    subtitle = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync)),
                )
            }
        }

        if (refreshed && state.message.isNotBlank()) GroupFooter(state.message)
        if (localMessage.isNotBlank()) GroupFooter(localMessage)

        PrimaryButton(
            text = if (state.busy) "同步中…" else "立即同步",
            onClick = {
                if (!state.busy) scope.launch(Dispatchers.IO) {
                    val result = runCatching { LedgerSyncCoordinator.syncNow(context) }
                    val msg = result.exceptionOrNull()?.let { "同步失败：" + (it.message ?: "请稍后再试") } ?: "已同步"
                    withContext(Dispatchers.Main) { localMessage = msg }
                }
            },
            enabled = !state.busy,
            busy = state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
