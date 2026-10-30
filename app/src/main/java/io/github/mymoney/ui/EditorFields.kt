package io.github.mymoney.ui

import com.example.billkit.BillDraft

/** Business equality ignores generated defaults/metadata but notices invalid amount input. */
internal fun editorFieldsChanged(before: BillDraft, after: BillDraft, amountText: String): Boolean {
    fun clean(value: String?) = value?.trim().orEmpty()
    val amountChanged = if (amountText.isBlank()) before.priceCents > 0 else parseAmountCents(amountText) != before.priceCents
    return amountChanged || before.type != after.type || before.timeMillis != after.timeMillis ||
        clean(before.merchant) != clean(after.merchant) || clean(before.category) != clean(after.category) ||
        clean(before.subCategory) != clean(after.subCategory) || clean(before.account) != clean(after.account) ||
        before.transferAccountId != after.transferAccountId || clean(before.note) != clean(after.note)
}
