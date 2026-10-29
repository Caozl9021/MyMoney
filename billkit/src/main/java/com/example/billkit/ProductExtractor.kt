package com.example.billkit

/** Optional, conservative enhancement. Never feeds amount/type/identity parsing. */
object ProductExtractor {
    const val RULE = "explicit-product-v1"
    private val label = Regex("^(?:商品说明|商品名称|商品)(?:\\s*[:：]\\s*(.*))?$")
    private val boundary = Regex("^(?:实付|付款|金额|价格|数量|优惠|订单|交易|支付|收款|商户|创建时间|完成时间|商品总额).*")
    /**
     * [truncated] 表示采集本身就不完整（节点上限/字符上限被裁），此时宁可不给商品名，
     * 也不要凭残缺文本猜。OCR 已随 Root 页面轮询删除，因此不再有 ocr 参数。
     */
    fun extract(items: List<String>, truncated: Boolean = false): String? {
        if (truncated) return null
        val candidates = items.mapIndexedNotNull { index, item ->
            val match = label.matchEntire(item.trim()) ?: return@mapIndexedNotNull null
            val value = match.groupValues[1].trim().ifEmpty { items.getOrNull(index + 1)?.trim().orEmpty() }
            value.takeIf { it.isNotBlank() && it.length <= 120 && !boundary.matches(it) &&
                !label.matches(it) && !it.contains("推荐商品") && !Regex("^(?:[¥￥\\d.,\\s]+(?:元|RMB|CNY)?|[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}.*)$").matches(it) }
        }.distinct()
        return candidates.singleOrNull()
    }
}
