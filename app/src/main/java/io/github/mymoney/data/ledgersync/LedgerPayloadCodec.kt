package io.github.mymoney.data.ledgersync

import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AssetEntry
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.Reimburse

/**
 * W2 账本同步条目的 payload 编解码。payload 是同步单元，独立于备份格式（JSON9/10），
 * 字段全量携带、以 syncId 为稳定身份。所有条目都必须带 syncId（服务端强校验）。
 */
object LedgerPayloadCodec {

    // ---------------- 账单 ----------------
    fun encodeBill(b: BillEntity): Map<String, Any?> {
        require(b.syncId.isNotBlank()) { "账单缺少 syncId" }
        return linkedMapOf(
            "syncId" to b.syncId,
            "priceCents" to b.priceCents,
            "type" to b.type,
            "timeMillis" to b.timeMillis,
            "merchant" to b.merchant,
            "method" to b.method,
            "orderTail" to b.orderTail,
            "sourcePkg" to b.sourcePkg,
            "strategy" to b.strategy,
            "fingerprint" to b.fingerprint,
            "createdAt" to b.createdAt,
            "category" to b.category,
            "subCategory" to b.subCategory,
            "account" to b.account,
            "note" to b.note,
            "recognitionId" to b.recognitionId,
            "accountId" to b.accountId,
            "transferAccountId" to b.transferAccountId,
            "assetLinked" to b.assetLinked,
            "assetKey" to b.assetKey,
            "transferFeeCents" to b.transferFeeCents,
            "feeParentKey" to b.feeParentKey,
            "transferAccount" to b.transferAccount,
            "productName" to b.productName,
            "productSource" to b.productSource,
            "noteOrigin" to b.noteOrigin,
            "noteTemplateSnapshot" to b.noteTemplateSnapshot,
            "updatedAt" to b.updatedAt,
            "deletedAt" to b.deletedAt,
            "reimburseStatus" to b.reimburseStatus,
            "reimbursedAt" to b.reimbursedAt,
            "reimburseAccountId" to b.reimburseAccountId,
        )
    }

    fun decodeBill(payload: Map<*, *>, fallbackUpdatedAt: Long): BillEntity {
        val syncId = str(payload["syncId"]) ?: throw IllegalArgumentException("账单缺少 syncId")
        return BillEntity(
            id = 0,
            priceCents = num(payload["priceCents"]),
            type = str(payload["type"]) ?: "EXPENSE",
            timeMillis = num(payload["timeMillis"]),
            merchant = str(payload["merchant"]),
            method = str(payload["method"]),
            orderTail = str(payload["orderTail"]),
            sourcePkg = str(payload["sourcePkg"]) ?: "",
            strategy = str(payload["strategy"]) ?: "sync",
            fingerprint = str(payload["fingerprint"]) ?: "",
            createdAt = num(payload["createdAt"]).let { if (it == 0L) num(payload["timeMillis"]) else it },
            category = str(payload["category"]),
            subCategory = str(payload["subCategory"]),
            account = str(payload["account"]),
            note = str(payload["note"]),
            recognitionId = str(payload["recognitionId"]),
            accountId = str(payload["accountId"]),
            transferAccountId = str(payload["transferAccountId"]),
            assetLinked = bool(payload["assetLinked"]),
            assetKey = str(payload["assetKey"]) ?: "",
            transferFeeCents = num(payload["transferFeeCents"]),
            feeParentKey = str(payload["feeParentKey"]),
            transferAccount = str(payload["transferAccount"]),
            productName = str(payload["productName"]),
            productSource = str(payload["productSource"]),
            noteOrigin = str(payload["noteOrigin"]),
            noteTemplateSnapshot = str(payload["noteTemplateSnapshot"]),
            syncId = syncId,
            updatedAt = (payload["updatedAt"] as? Number)?.toLong() ?: fallbackUpdatedAt,
            deletedAt = (payload["deletedAt"] as? Number)?.toLong(),
            reimburseStatus = Reimburse.normalize(str(payload["reimburseStatus"])),
            reimbursedAt = (payload["reimbursedAt"] as? Number)?.toLong(),
            reimburseAccountId = str(payload["reimburseAccountId"])
                ?: str(payload["accountId"])?.takeIf { Reimburse.normalize(str(payload["reimburseStatus"])) == Reimburse.REIMBURSED },
        )
    }

    // ---------------- 账户 ----------------
    fun encodeAccount(a: AssetAccount): Map<String, Any?> = linkedMapOf(
        "syncId" to a.id,  // 账户 id 本身即稳定 UUID，用它作 syncId
        "name" to a.name,
        "kind" to a.kind,
        "aliases" to a.aliases,
        "sortOrder" to a.sortOrder,
        "archived" to a.archived,
        "included" to a.included,
        "initialized" to a.initialized,
        "createdAt" to a.createdAt,
        "updatedAt" to a.updatedAt,
    )

    fun decodeAccount(payload: Map<*, *>): AssetAccount = AssetAccount(
        id = str(payload["syncId"]) ?: throw IllegalArgumentException("账户缺少 syncId"),
        name = str(payload["name"]) ?: "",
        kind = str(payload["kind"]) ?: "PAYMENT",
        aliases = str(payload["aliases"]) ?: "[]",
        sortOrder = num(payload["sortOrder"]).toInt(),
        archived = bool(payload["archived"]),
        included = bool(payload["included"]),
        initialized = bool(payload["initialized"]),
        createdAt = num(payload["createdAt"]),
        updatedAt = num(payload["updatedAt"]),
    )

    // ---------------- 流水 ----------------
    fun encodeEntry(e: AssetEntry): Map<String, Any?> = linkedMapOf(
        "syncId" to e.id,
        "operationId" to e.operationId,
        "accountId" to e.accountId,
        "deltaCents" to e.deltaCents,
        "kind" to e.kind,
        "note" to e.note,
        "createdAt" to e.createdAt,
        "billKey" to e.billKey,
        "reversalOf" to e.reversalOf,
        "updatedAt" to e.updatedAt,
        "deletedAt" to e.deletedAt,
    )

    fun decodeEntry(payload: Map<*, *>): AssetEntry = AssetEntry(
        id = str(payload["syncId"]) ?: throw IllegalArgumentException("流水缺少 syncId"),
        operationId = str(payload["operationId"]) ?: "",
        accountId = str(payload["accountId"]) ?: "",
        deltaCents = num(payload["deltaCents"]),
        kind = str(payload["kind"]) ?: "BILL",
        note = str(payload["note"]) ?: "",
        createdAt = num(payload["createdAt"]),
        billKey = str(payload["billKey"]),
        reversalOf = str(payload["reversalOf"]),
        updatedAt = (payload["updatedAt"] as? Number)?.toLong() ?: 0,
        deletedAt = (payload["deletedAt"] as? Number)?.toLong(),
    )

    fun decodeBillPayload(payloadJson: String): Map<*, *> =
        MiniJson.decode(payloadJson) as? Map<*, *> ?: throw IllegalArgumentException("账本条目内容不是对象")

    private fun str(v: Any?): String? = v as? String
    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0
    private fun bool(v: Any?): Boolean = v as? Boolean ?: false
}
