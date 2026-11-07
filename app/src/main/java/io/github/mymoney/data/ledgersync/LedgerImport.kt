package io.github.mymoney.data.ledgersync

import android.content.Context
import androidx.room.withTransaction
import io.github.mymoney.data.AssetAccount
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.AssetMath
import io.github.mymoney.data.AssetStore
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.ImportIdentity
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.withSyncIdentity
import java.security.MessageDigest
import java.util.UUID

/**
 * W3 按需导入：把「同空间另一台设备 A 的账本冻结快照」按用户选择导入到本机。
 *
 * 语义（见 docs/FAMILY_DATA_UX_PLAN.md §3/§5）：
 * - 只在用户显式「从其他设备导入」时发生，不建立订阅、不自动拉取。
 * - 每条导入的账单都分配**本机新 syncId**（不是 A 的 syncId），来源通过 ImportIdentity 记录：
 *   sourceKey = "ledger:<来源ledger前8位>:<sha256(来源ledgerId\\0来源syncId)>"，
 *   contentHash = sha256(账单业务字段的规范 JSON)。
 * - 重复导入：sourceKey 已存在且 contentHash 相同 → 视为已导入（DUPLICATE，无新增）；
 *   已存在但 contentHash 不同 → CONFLICT（双边修改，交用户核对，不覆盖）。
 * - A 删除不会带走 B 已有副本：A 的墓碑条目在导入预览里直接跳过，不影响本机已导入记录。
 * - 转账/手续费成组：来源账单里 feeParentKey 关联的手续费在导入时一并成组处理，余额只按规范流水一次计入。
 */
object LedgerImport {

    enum class State { READY, DUPLICATE, CONFLICT, DELETED }

    data class Item(
        val sourceSyncId: String,
        val state: State,
        val bill: BillEntity,
        val fee: BillEntity?,
        val identity: ImportIdentity,
    )

    data class Preview(
        val sourceDeviceName: String,
        val sourceLedgerId: String,
        val sourceRevision: Long,
        val items: List<Item>,
    ) {
        fun count(state: State) = items.count { it.state == state }
        fun readyBills() = items.filter { it.state == State.READY }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sourceKey(sourceLedgerId: String, sourceSyncId: String): String =
        "ledger:" + sourceLedgerId.take(8) + ":" + sha256(sourceLedgerId + "\u0000" + sourceSyncId)

    /** 账单业务内容规范哈希（决定「同来源内容是否变化」）。排除本机自增 id/syncId/updatedAt。 */
    private fun contentHash(bill: BillEntity): String {
        val canonical = linkedMapOf<String, Any?>(
            "priceCents" to bill.priceCents,
            "type" to bill.type,
            "timeMillis" to bill.timeMillis,
            "merchant" to bill.merchant,
            "method" to bill.method,
            "orderTail" to bill.orderTail,
            "category" to bill.category,
            "subCategory" to bill.subCategory,
            "account" to bill.account,
            "note" to bill.note,
            "transferAccount" to bill.transferAccount,
            "transferFeeCents" to bill.transferFeeCents,
            "productName" to bill.productName,
        )
        return sha256(MiniJson.encode(canonical))
    }

    /**
     * 由冻结来源快照构建导入预览：解码账单、按 feeParentKey 成组、按来源身份判 READY/DUPLICATE/CONFLICT/DELETED。
     * 纯函数（existing 只读），提交时会在事务内重新计算。
     */
    fun preview(
        sourceDeviceName: String,
        sourceLedgerId: String,
        sourceRevision: Long,
        remoteItems: List<LedgerRemoteItem>,
        existing: List<ImportIdentity>,
    ): Preview {
        val known = existing.associateBy { it.sourceKey }
        // 先解码全部 non-deleted bill 条目，再按 feeParentKey 把手续费挂到主转账上
        val decoded = remoteItems
            .filter { it.itemKind == LedgerItemKind.BILL }
            .mapNotNull { item ->
                val payload = runCatching { LedgerPayloadCodec.decodeBillPayload(item.payloadJson) }.getOrNull() ?: return@mapNotNull null
                val bill = runCatching { LedgerPayloadCodec.decodeBill(payload, item.contentUpdatedAt ?: 0L) }.getOrNull() ?: return@mapNotNull null
                val tombstone = item.deletedAt != null || bill.deletedAt != null
                Triple(item.itemId.removePrefix("bill:"), bill, tombstone)
            }
        val byParentFee = decoded.filter { !it.third && it.second.feeParentKey != null }
            .groupBy { it.second.feeParentKey!! }
        val feesBySourceSync = decoded.filter { !it.third && it.second.feeParentKey != null }
            .associate { it.first to it.second }
        val feeSourceIds = feesBySourceSync.keys
        val items = ArrayList<Item>()
        decoded.forEach { (sourceSyncId, bill, tombstone) ->
            if (sourceSyncId in feeSourceIds) return@forEach // 手续费单独挂在主转账下，不单列
            val key = sourceKey(sourceLedgerId, sourceSyncId)
            val id = ImportIdentity(key, contentHash(bill))
            val old = known[key]
            val state = when {
                tombstone -> State.DELETED
                old != null && old == id -> State.DUPLICATE
                old != null -> State.CONFLICT
                else -> State.READY
            }
            val fee = bill.assetKey.takeIf { it.isNotBlank() }?.let { key2 ->
                byParentFee[key2]?.firstOrNull()?.let { (feeSync, feeBill, feeTomb) ->
                    if (feeTomb) null else feeBill
                }
            }
            items.add(Item(sourceSyncId, state, bill, fee, id))
        }
        return Preview(sourceDeviceName, sourceLedgerId, sourceRevision, items)
    }

    /** 预览：读本机已有导入身份后计算（只读事务）。 */
    suspend fun previewDatabase(
        db: AppDb, sourceDeviceName: String, sourceLedgerId: String,
        sourceRevision: Long, remoteItems: List<LedgerRemoteItem>,
    ): Preview = db.withTransaction {
        preview(sourceDeviceName, sourceLedgerId, sourceRevision, remoteItems, db.imports().all())
    }

    /**
     * 提交导入：在单个 Room 事务内为每条 READY 账单分配新 syncId、映射/创建账户、用 AssetStore.syncBill
     * 生成资产流水（转账+手续费成组一次计入），并写入来源导入身份。重复/冲突/删除项不写。
     * 返回提交后的预览（含最新状态）。
     */
    suspend fun commit(
        db: AppDb, sourceDeviceName: String, sourceLedgerId: String,
        sourceRevision: Long, remoteItems: List<LedgerRemoteItem>,
    ): Preview = db.withTransaction {
        val report = previewDatabase(db, sourceDeviceName, sourceLedgerId, sourceRevision, remoteItems)
        val accounts = db.assets().accounts()
        fun resolve(list: List<AssetAccount>, name: String?): AssetAccount? {
            if (name.isNullOrBlank()) return null
            val n = name.trim()
            return list.filter { !it.archived && (it.name == n || runCatching { n in AssetMath.aliases(it) }.getOrDefault(false)) }.singleOrNull()
        }
        val now = System.currentTimeMillis()
        val dao = db.bills()
        report.readyBills().forEach { item ->
            val src = item.bill
            val from = resolve(accounts, src.account)
            val to = if (src.type == "TRANSFER") resolve(accounts, src.transferAccount) else null
            require(src.type != "TRANSFER" || from != null) { "转账缺少转出账户对应，未导入" }
            val feeAccount = resolve(accounts, item.fee?.account) ?: from
            val baseKey = UUID.randomUUID().toString()
            val bill = BillEntity(
                priceCents = src.priceCents,
                type = src.type,
                timeMillis = src.timeMillis,
                merchant = src.merchant,
                method = src.method,
                orderTail = src.orderTail,
                sourcePkg = src.sourcePkg,
                strategy = src.strategy,
                fingerprint = src.fingerprint,
                createdAt = src.createdAt,
                category = src.category,
                subCategory = src.subCategory,
                account = src.account,
                note = src.note,
                recognitionId = null, // 不伪造本机识别历史
                accountId = from?.id,
                transferAccountId = to?.id,
                assetLinked = from?.initialized == true,
                assetKey = baseKey,
                transferFeeCents = src.transferFeeCents,
                feeParentKey = null,
                transferAccount = src.transferAccount,
                productName = src.productName,
                productSource = src.productSource,
                noteOrigin = src.noteOrigin,
                noteTemplateSnapshot = src.noteTemplateSnapshot,
            )
            // AssetStore 生成流水并回填 accountId/assetKey/assetLinked（等价于手工/导入的落库路径）
            val linked = AssetStore.syncBill(db, null, bill)!!.withSyncIdentity(now)
            val principal = linked.copy(id = dao.insert(linked))
            // 手续费成组：仅在来源确有费用时导入，一次计入余额
            item.fee?.let { feeSrc ->
                if (feeSrc.priceCents > 0) {
                    val feeBill = BillEntity(
                        priceCents = feeSrc.priceCents, type = "EXPENSE", timeMillis = principal.timeMillis,
                        merchant = feeSrc.merchant ?: "转账手续费", method = null, orderTail = null,
                        sourcePkg = feeSrc.sourcePkg, strategy = feeSrc.strategy, fingerprint = feeSrc.fingerprint,
                        createdAt = feeSrc.createdAt, category = feeSrc.category ?: "其他",
                        account = feeSrc.account, accountId = feeAccount?.id, assetLinked = feeAccount?.initialized == true,
                        assetKey = "$baseKey-fee", feeParentKey = principal.assetKey, note = feeSrc.note,
                    )
                    val linkedFee = AssetStore.syncBill(db, null, feeBill)!!.withSyncIdentity(now)
                    dao.insert(linkedFee)
                }
            }
            db.imports().insert(item.identity)
        }
        report
    }
}
