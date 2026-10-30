package com.example.mymoney.capture

import android.content.Context
import android.content.Intent
import com.example.billkit.BillDraft
import com.example.mymoney.ui.ConfirmActivity

/** 草稿 → ConfirmActivity 的 Intent 只在一个地方构造 */
object ConfirmIntents {

    fun intent(context: Context, draft: BillDraft): Intent =
        Intent(context, ConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ConfirmActivity.EXTRA_PRICE_CENTS, draft.priceCents)
            putExtra(ConfirmActivity.EXTRA_TYPE, draft.type.name)
            putExtra(ConfirmActivity.EXTRA_TIME, draft.timeMillis)
            putExtra(ConfirmActivity.EXTRA_MERCHANT, draft.merchant)
            putExtra(ConfirmActivity.EXTRA_METHOD, draft.method)
            putExtra(ConfirmActivity.EXTRA_ORDER_TAIL, draft.orderTail)
            putExtra(ConfirmActivity.EXTRA_SOURCE_PKG, draft.sourcePkg)
            putExtra(ConfirmActivity.EXTRA_STRATEGY, draft.strategy)
            putExtra(ConfirmActivity.EXTRA_FINGERPRINT, draft.fingerprint)
            putExtra(ConfirmActivity.EXTRA_CATEGORY, draft.category)
            putExtra(ConfirmActivity.EXTRA_SUB_CATEGORY, draft.subCategory)
            putExtra(ConfirmActivity.EXTRA_ACCOUNT, draft.account)
            putExtra(ConfirmActivity.EXTRA_NOTE, draft.note)
            putExtra(ConfirmActivity.EXTRA_HISTORY_ID, draft.historyId)
            putExtra(ConfirmActivity.EXTRA_TRANSACTION_KEY, draft.transactionKey)
            putExtra(ConfirmActivity.EXTRA_EXPLICIT_TIME, draft.hasExplicitTime)
            putExtra(ConfirmActivity.EXTRA_CAPTURE_SESSION, draft.captureSessionId)
            data = android.net.Uri.parse("mymoney://confirm/" + android.net.Uri.encode(draft.historyId ?: draft.fingerprint))
        }
}
