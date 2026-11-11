package io.github.mymoney.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.BillWriter
import io.github.mymoney.data.Reimburse
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AppChoiceDialog
import io.github.mymoney.ui.design.AppDialogChoice
import io.github.mymoney.ui.design.LocalAbPalette
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

internal data class ReimburseNotice(val text: String, val undoBillId: Long?)

internal object ReimburseFeedback {
    var notice by mutableStateOf<ReimburseNotice?>(null)
        private set

    fun show(text: String, undoBillId: Long?) {
        notice = ReimburseNotice(text, undoBillId)
    }

    fun clear() {
        notice = null
    }
}

private const val RETURN_PREF = "reimburse_return_account"

internal fun returnCandidates(accounts: List<AssetAccount>): List<AssetAccount> =
    accounts.filter { it.initialized && !it.archived }

internal fun paymentReady(bill: BillEntity, accounts: List<AssetAccount>): Boolean {
    val payment = accounts.firstOrNull { it.id == bill.accountId }
    return bill.assetLinked && payment?.initialized == true
}

internal fun rememberReturnAccount(context: Context, accountId: String) {
    context.getSharedPreferences("mymoney", Context.MODE_PRIVATE).edit().putString(RETURN_PREF, accountId).apply()
}

internal fun preferredReturnId(context: Context, bill: BillEntity, accounts: List<AssetAccount>): String? {
    val candidates = returnCandidates(accounts)
    val saved = context.getSharedPreferences("mymoney", Context.MODE_PRIVATE).getString(RETURN_PREF, null)
    return candidates.firstOrNull { it.id == saved }?.id ?: candidates.firstOrNull()?.id
        ?: candidates.firstOrNull { it.id == bill.accountId }?.id
}

/** 已有有效退回账户就直接核销；否则交给界面选。支付账户没设余额时只改状态。 */
internal fun markReimbursed(context: Context, bill: BillEntity, accounts: List<AssetAccount>, onNeedPicker: () -> Unit) {
    if (!paymentReady(bill, accounts) || returnCandidates(accounts).isEmpty()) {
        requestReimbursement(context, bill, Reimburse.REIMBURSED)
        return
    }
    val kept = returnCandidates(accounts).firstOrNull { it.id == bill.reimburseAccountId }
    if (kept != null) requestReimbursement(context, bill, Reimburse.REIMBURSED, kept.id) else onNeedPicker()
}

internal fun requestReimbursement(context: Context, bill: BillEntity, status: String, reimburseAccountId: String? = null) {
    BillWriter.updateReimbursement(context, bill.id, status, reimburseAccountId) { result ->
        result.onSuccess { text ->
            ReimburseFeedback.show(text, bill.id.takeIf { status == Reimburse.REIMBURSED })
        }
        result.onFailure { failure ->
            ReimburseFeedback.show(failure.message ?: "报销操作失败", null)
        }
    }
}

@Composable
internal fun ReturnAccountDialog(bill: BillEntity, accounts: List<AssetAccount>, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = returnCandidates(accounts)
    AppChoiceDialog(
        title = "退回账户",
        options = options.map { AppDialogChoice(it.id, it.name, if (it.id == bill.accountId) "原支付账户" else null) },
        selectedKey = preferredReturnId(context, bill, accounts),
        onChoose = { id ->
            rememberReturnAccount(context, id)
            requestReimbursement(context, bill, Reimburse.REIMBURSED, id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ReimburseChoiceDialog(bill: BillEntity, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts by AssetStore.accounts.collectAsStateWithLifecycle()
    var picking by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (picking) {
        ReturnAccountDialog(bill, accounts, onDismiss)
        return
    }
    val options = when (bill.reimburseStatus) {
        Reimburse.PENDING -> listOf(
            AppDialogChoice(Reimburse.REIMBURSED, "已报销"),
            AppDialogChoice(Reimburse.NONE, "取消可报销"),
        )
        Reimburse.REIMBURSED -> buildList {
            if (paymentReady(bill, accounts) && returnCandidates(accounts).isNotEmpty()) add(AppDialogChoice("change", "更改退回账户"))
            add(AppDialogChoice(Reimburse.PENDING, "撤销报销"))
        }
        else -> listOf(AppDialogChoice(Reimburse.PENDING, "标为可报销"))
    }
    AppChoiceDialog(
        title = "报销",
        options = options,
        selectedKey = null,
        onChoose = { status ->
            when {
                status == "change" || status == Reimburse.REIMBURSED && paymentReady(bill, accounts) &&
                    returnCandidates(accounts).none { it.id == bill.reimburseAccountId } -> picking = true
                status == Reimburse.REIMBURSED -> {
                    markReimbursed(context, bill, accounts) { picking = true }
                    if (!picking) onDismiss()
                }
                else -> {
                    requestReimbursement(context, bill, status)
                    onDismiss()
                }
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ReimburseNoticeBar(modifier: Modifier = Modifier) {
    val notice = ReimburseFeedback.notice ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalAbPalette.current
    LaunchedEffect(notice) {
        delay(6_000)
        if (ReimburseFeedback.notice == notice) ReimburseFeedback.clear()
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.elevatedBg)
            .padding(start = AbSpacing.l, end = AbSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Text(notice.text, style = AbType.footnote, color = palette.label, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        notice.undoBillId?.let { id ->
            TextButton(onClick = {
                BillWriter.updateReimbursement(context, id, Reimburse.PENDING) { result ->
                    result.onSuccess { ReimburseFeedback.show(it, null) }
                    result.onFailure { ReimburseFeedback.show(it.message ?: "撤销失败", null) }
                }
            }) { Text("撤销") }
        }
    }
}
