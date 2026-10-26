package com.example.autobook.data

import com.example.billkit.BillType

/** Pure codec; fail before any restore transaction if an entry is malformed. */
object RecognitionCodec {
    fun encode(row: RecognitionEntity): Map<String, Any?> = linkedMapOf(
        "id" to row.id, "identityKey" to row.identityKey, "priceCents" to row.priceCents,
        "type" to row.type, "timeMillis" to row.timeMillis, "merchant" to row.merchant,
        "method" to row.method, "orderTail" to row.orderTail, "sourcePkg" to row.sourcePkg,
        "strategy" to row.strategy, "fingerprint" to row.fingerprint, "category" to row.category,
        "subCategory" to row.subCategory, "account" to row.account, "note" to row.note,
        "transactionKey" to row.transactionKey, "hasExplicitTime" to row.hasExplicitTime,
        "captureSessionId" to row.captureSessionId, "recognizedAt" to row.recognizedAt,
        "updatedAt" to row.updatedAt, "status" to row.status, "billId" to row.billId,
        "shareState" to row.shareState, "protectedFields" to row.protectedFields, "reviewOf" to row.reviewOf)
    fun decode(raw: Any?): RecognitionEntity {
        val m = raw as? Map<*, *> ?: error("识别历史不是对象")
        fun string(key: String) = (m[key] as? String)?.takeIf { it.isNotBlank() } ?: error("识别历史缺少$key")
        fun number(key: String): Long {
            val value = m[key] as? Number ?: error("识别历史缺少$key")
            val integer = value.toLong()
            require(value.toDouble().isFinite() && value.toDouble() == integer.toDouble()) { "识别历史${key}必须为整数" }
            return integer
        }
        val protectedFields = if (m.containsKey("protectedFields")) number("protectedFields").also {
            require(it >= 0 && (it and CaptureFields.ALL) == it) { "人工字段保护无效" }
        } else 0L
        val reviewOf = when (val value = m["reviewOf"]) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("待核对关联无效")
            else -> throw IllegalArgumentException("待核对关联必须为文字")
        }
        val type = string("type").also { BillType.valueOf(it) }
        val status = string("status").also { require(it in setOf(RecognitionStatus.PENDING, RecognitionStatus.RECORDED, RecognitionStatus.IGNORED)) { "识别历史状态不支持" } }
        val share = string("shareState").also { require(it in setOf(RecognitionShareState.NOT_REQUESTED, RecognitionShareState.REQUESTED, RecognitionShareState.FAILED)) { "分享状态不支持" } }
        return RecognitionEntity(id = string("id"), identityKey = string("identityKey"),
            priceCents = number("priceCents").also { require(it > 0) }, type = type,
            timeMillis = number("timeMillis"), merchant = m["merchant"] as? String,
            method = m["method"] as? String, orderTail = m["orderTail"] as? String,
            sourcePkg = string("sourcePkg"), strategy = m["strategy"] as? String ?: "backup",
            fingerprint = string("fingerprint"), category = m["category"] as? String,
            subCategory = m["subCategory"] as? String, account = m["account"] as? String,
            note = m["note"] as? String, transactionKey = m["transactionKey"] as? String,
            hasExplicitTime = m["hasExplicitTime"] as? Boolean ?: false,
            captureSessionId = m["captureSessionId"] as? String, recognizedAt = number("recognizedAt"),
            updatedAt = number("updatedAt"), status = status, billId = (m["billId"] as? Number)?.toLong(), shareState = share,
            protectedFields = protectedFields, reviewOf = reviewOf)
    }
}
