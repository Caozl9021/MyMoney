package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.BillType
import kotlin.math.abs

/**
 * 记账前「查账本」：拿一次新采集的草稿（时间+金额+付款账户）对照账单库，找出疑似已经记过的账单。
 *
 * 只**提示**，不阻断——命中返回疑似那笔（可多条），由用户在确认卡上自己决定保存还是忽略。
 * 时间窗 ±120000ms（±2 分钟），账户走 [AccountNormalizer] 模糊匹配。
 *
 * 候选集先用 `bills.duplicateCandidates`（timeMillis 索引 + 金额 + 类型 + LIMIT）收敛，
 * 再在内存里做账户比对，数据量大也不会全表扫。
 */
object DuplicateProbe {

    /** 与用户拍板一致：±2 分钟。 */
    const val TIME_TOLERANCE_MS = 120_000L

    /** 窗口内最多取多少条候选做账户比对（防止异常时间字段或批量同秒记录撑爆）。 */
    const val MAX_CANDIDATES = 200

    data class Hit(
        val billId: Long,
        val timeMillis: Long,
        val priceCents: Long,
        val type: String,
        val account: String?,
        val merchant: String?,
        val timeDeltaMs: Long,
    )

    /**
     * 返回疑似重复的账单（按时间距离由近到远）。空 = 没有命中。
     * 转账/普通支出都按「金额 + 类型」比较；同一类型才可能重复。
     */
    suspend fun findSuspects(db: AppDb, draft: BillDraft): List<Hit> {
        if (draft.priceCents <= 0L) return emptyList()
        val center = draft.timeMillis
        val lo = (center - TIME_TOLERANCE_MS).coerceAtLeast(0L)
        val hi = center + TIME_TOLERANCE_MS
        val candidates = db.bills().duplicateCandidates(
            draft.priceCents, draft.type.name, lo, hi, center, MAX_CANDIDATES,
        )
        return evaluate(candidates, draft)
    }

    /**
     * 纯函数：在一批候选账单上做「时间容差 + 金额 + 类型 + 账户模糊」判定，返回疑似重复。
     * 从 [findSuspects] 抽出，便于 JVM 单测（不依赖 Room）。
     */
    fun evaluate(candidates: List<BillEntity>, draft: BillDraft): List<Hit> {
        if (draft.priceCents <= 0L) return emptyList()
        val center = draft.timeMillis
        return candidates
            .filter { it.priceCents == draft.priceCents && it.type == draft.type.name }
            .filter { abs(it.timeMillis - center) <= TIME_TOLERANCE_MS }
            .filter { AccountNormalizer.sameAccount(it.account, draft.account) }
            .map { bill ->
                Hit(
                    billId = bill.id,
                    timeMillis = bill.timeMillis,
                    priceCents = bill.priceCents,
                    type = bill.type,
                    account = bill.account,
                    merchant = bill.merchant,
                    timeDeltaMs = abs(bill.timeMillis - center),
                )
            }
            .sortedBy { it.timeDeltaMs }
    }
}
