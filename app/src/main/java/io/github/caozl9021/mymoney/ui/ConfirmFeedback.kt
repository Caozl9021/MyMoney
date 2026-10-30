package io.github.caozl9021.mymoney.ui

/** Feedback describes local storage and the share launch separately; it never claims external import. */
internal object ConfirmFeedback {
    fun closeAfterSave(result: io.github.caozl9021.mymoney.data.BillWriter.SaveResult): Boolean = result.inserted && result.bill != null

    fun saved(inserted: Boolean): String = if (inserted) "已记一笔" else "这笔已经记过了"

    fun resultDetail(result: io.github.caozl9021.mymoney.data.BillWriter.SaveResult): String? = when {
        result.bill == null -> "这条识别记录已处理，但关联账单已删除或不存在，未重新记账。"
        !result.inserted -> "未重复记账，可查看原账单。"
        result.bill.type == "REFUND" -> "已保存到本地；iCost 暂不支持退款，无法分享。"
        else -> "已保存到本地，可查看账单或分享至 iCost。"
    }

    fun shared(inserted: Boolean, launched: Boolean): String {
        val local = if (inserted) "已保存到本地" else "这笔已经记过了"
        return if (launched) "$local，已发起分享，请在目标 App 确认导入"
        else "$local，分享失败，可重试"
    }
}
