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
                val label = billCategoryDisplay(bill.category, bill.subCategory, bill.type)
                    ?: bill.note?.takeIf(String::isNotBlank)
                    ?: bill.merchant
                    ?: "账单"
                Text(
                    "$label · ${formatAmount(bill.priceCents, signed = true, type = bill.type)}",
                    style = AbType.body,
                    color = LocalAbPalette.current.label,
                )
                val reimbursed = group.any { it.reimburseStatus == io.github.mymoney.data.Reimburse.REIMBURSED }
                val accountName = bill.account ?: "关联账户"
                val balanceHint = when {
                    bill.type == "TRANSFER" && bill.assetLinked ->
                        "删除后金额将返回原转出账户${if (group.size > 1) "（含手续费）" else ""}"
                    reimbursed && bill.assetLinked ->
                        "删除后金额将返回「$accountName」（含报销退回部分）"
                    bill.type == "INCOME" && bill.assetLinked ->
                        "删除后「$accountName」余额将返回记账前状态"
                    bill.assetLinked ->
                        "删除后金额将返回「$accountName」"
                    else -> null
                }
                if (balanceHint != null) {
                    Text(balanceHint, style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
                }
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
