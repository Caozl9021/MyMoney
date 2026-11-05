package io.github.mymoney.data

import com.example.billkit.BillDraft
import com.example.billkit.visual.VisualBillResult
import com.example.billkit.spec.SpecAccountMapper

/** A visual candidate is subordinate to the identified transaction and to every human edit. */
object OcrEvidencePolicy {
    data class Decision(val row: RecognitionEntity, val outcome: String, val changedFields: List<String> = emptyList())

    fun apply(row: RecognitionEntity, expected: BillDraft, result: VisualBillResult,
              touchedFields: Long = 0L, resolvedAccount: String? = null): Decision {
        if (row.status != RecognitionStatus.PENDING || row.reviewOf != null) return Decision(row, "terminal")
        if (row.sourcePkg != expected.sourcePkg || row.priceCents != expected.priceCents || row.type != expected.type.name ||
            expected.historyId?.let { it != row.id } == true ||
            (!expected.transactionKey.isNullOrBlank() && !row.transactionKey.isNullOrBlank() && expected.transactionKey != row.transactionKey)) {
            return Decision(row, "conflict")
        }
        if (result.issue != null) return Decision(row, "unusable")
        if (result.amountCents != row.priceCents || result.type != expected.type) return Decision(row, "conflict")
        val protected = row.protectedFields or touchedFields or expected.captureTouchedFields
        fun free(flag: Long) = protected and flag == 0L
        fun safe(value: String?, max: Int): String? = value?.trim()?.takeIf {
            it.isNotBlank() && it.length <= max && it.none { char -> char.isISOControl() && char != '\n' }
        }
        fun missing(old: String?, candidate: String?, flag: Long): String? =
            if (free(flag) && old.isNullOrBlank()) candidate ?: old else old
        val fields = result.fields
        val merchant = missing(row.merchant, safe(fields.merchant, 80), CaptureFields.MERCHANT)
        val methodCandidate = safe(fields.method, 80)
        val method = missing(row.method, methodCandidate, CaptureFields.METHOD)
        // A differing existing method is retained; never infer its account from a contradictory OCR tool.
        val account = if (methodCandidate != null && method == methodCandidate)
            missing(row.account, safe(resolvedAccount, 80), CaptureFields.ACCOUNT) else row.account
        val product = if (free(CaptureFields.NOTE) && row.productName.isNullOrBlank())
            safe(fields.productName, 120) ?: row.productName else row.productName
        val newTime = fields.timeMillis?.takeIf { result.explicitTime && it > 0 && !row.hasExplicitTime && free(CaptureFields.TIME) }
        val enriched = row.copy(merchant = merchant, method = method, account = account,
            productName = product,
            productSource = if (product != row.productName) "ocr-field-candidate-v1" else row.productSource,
            timeMillis = newTime ?: row.timeMillis, hasExplicitTime = row.hasExplicitTime || newTime != null)
        val changes = buildList {
            if (merchant != row.merchant) add("merchant")
            if (method != row.method) add("method")
            if (account != row.account) add("account")
            if (product != row.productName) add("product")
            if (newTime != null) add("time")
        }
        // Neither amount/direction nor transactionKey/orderTail/note/category are populated here.
        // In particular OCR digits do not acquire strong identity and a product cannot rewrite notes.
        return Decision(enriched, if (changes.isEmpty()) "no-change" else "added", changes)
    }

    /** Exact names/aliases and explicit wallet synonyms only; no first bank/substring guess. */
    fun resolveAccount(method: String?, accounts: List<AssetAccount>): String? {
        val raw = method?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = SpecAccountMapper.normalize(raw)
        val labels = when (normalized) {
            "零钱", "微信零钱", "微信钱包" -> setOf("零钱", "微信零钱", "微信钱包", "微信")
            "零钱通", "微信零钱通" -> setOf("零钱通", "微信零钱通")
            "支付宝余额", "支付宝" -> setOf("支付宝余额", "支付宝")
            "余额宝" -> setOf("余额宝")
            else -> setOf(raw, normalized)
        }
        return accounts.filterNot { it.archived }.filter { account ->
            val aliases = runCatching { AssetMath.aliases(account) }.getOrDefault(emptyList())
            account.name in labels || aliases.any { it in labels }
        }.singleOrNull()?.name
    }
}
