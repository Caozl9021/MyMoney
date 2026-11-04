package io.github.mymoney.ui

/** Feedback describes the local storage result, without external sharing. */
internal object ConfirmFeedback {
    fun closeAfterSave(result: io.github.mymoney.data.BillWriter.SaveResult): Boolean = result.inserted && result.bill != null

    fun saved(inserted: Boolean): String = if (inserted) "已记一笔" else "这笔已经记过了"

    fun resultDetail(result: io.github.mymoney.data.BillWriter.SaveResult): String? = when {
        result.bill == null -> "这条识别记录已处理，但关联账单已删除或不存在，未重新记账。"
        !result.inserted -> "未重复记账，可查看原账单。"
        else -> "已保存到本地，可查看账单。"
    }


}
