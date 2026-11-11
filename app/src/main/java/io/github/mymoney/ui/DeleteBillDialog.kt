package io.github.mymoney.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every deletion entry point uses this snapshot and the same transaction-backed confirmation. */
@Composable
internal fun DeleteBillDialog(billId: Long, origin: String, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var retry by remember { mutableIntStateOf(0) }
    var rows by remember(billId) { mutableStateOf<List<BillEntity>?>(null) }
    var error by remember(billId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val dismissed by rememberUpdatedState(onDismiss)
    val deleted by rememberUpdatedState(onDeleted)
    LaunchedEffect(billId, retry) {
        rows = null; error = null
        try { rows = BillWriter.deletionPreview(app, billId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取账单，请重试" }
    }
    AppDialog("删除这笔账单？", onDismiss = { if (!busy) dismissed() }) {
        val group = rows
        val bill = group?.firstOrNull()
        when {
            bill != null -> {
                Text("${bill.note?.takeIf(String::isNotBlank) ?: bill.merchant ?: bill.category ?: "账单"}\n${formatAmount(bill.priceCents)} · ${when (bill.type) { "INCOME" -> "收入"; "REFUND" -> "退款"; "TRANSFER" -> "转账"; else -> "支出" }}", style = AbType.headline, color = LocalAbPalette.current.label)
                Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(bill.timeMillis).atZone(ZoneId.systemDefault())), style = AbType.subhead, color = LocalAbPalette.current.secondaryLabel)
                Text(if (bill.type == "TRANSFER") "将删除转账本金${if (group.size > 1) "及关联手续费" else ""}，并回滚已联动的账户余额。" else if (bill.assetLinked) "将删除账单，并回滚“${bill.account ?: "关联账户"}”的余额变动。" else "将从账本删除这笔账单，当前账户余额不会改变。", style = AbType.body, color = LocalAbPalette.current.secondaryLabel)
                group.filter { it.reimburseStatus == io.github.mymoney.data.Reimburse.REIMBURSED }.takeIf { it.isNotEmpty() }?.let { returned ->
                    Text("将同时冲回报销退回 ${returned.joinToString("、") { formatAmount(it.priceCents) }}。", style = AbType.body, color = LocalAbPalette.current.secondaryLabel)
                }
                Text("删除记录会保留在“记录历史”，仅供查看，不能恢复账单。", style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
            }
            group != null -> Text("这笔账单已不存在，请返回列表。", color = LocalAbPalette.current.secondaryLabel)
            error == null -> Text("正在核对账单…", color = LocalAbPalette.current.secondaryLabel)
        }
        error?.let { InlineMessage(it, isError = true, announce = true) }
        if (error != null) SecondaryButton("重新核对", enabled = !busy, onClick = { retry++ }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
            SecondaryButton("取消", enabled = !busy, onClick = { dismissed() }, modifier = Modifier.weight(1f))
            DestructiveButton("确认删除", enabled = bill != null && error == null, busy = busy, busyLabel = "正在删除…", modifier = Modifier.weight(1f), onClick = {
                if (!busy && !group.isNullOrEmpty()) {
                    busy = true
                    BillWriter.deleteConfirmed(app, group, origin) { result ->
                        busy = false
                        result.onSuccess { deleted() }.onFailure { error = it.message ?: "删除失败，请重试" }
                    }
                }
            })
        }
    }
}
