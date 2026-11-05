package com.example.billkit.visual

import com.example.billkit.BillDraft
import com.example.billkit.BillType

/** Coordinates are in the bitmap actually submitted to the recognizer. Null score means unavailable. */
data class VisualLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val confidence: Float? = null,
)

data class VisualText(
    val width: Int,
    val height: Int,
    val lines: List<VisualLine>,
    val engine: String,
    val elapsedMs: Long = 0,
)

/** Candidates only: no account inference, transaction identity or authoritative refund arithmetic. */
data class VisualFields(
    val merchant: String? = null,
    val method: String? = null,
    val productName: String? = null,
    val timeMillis: Long? = null,
    val orderCandidate: String? = null,
)

data class VisualBillResult(
    val draft: BillDraft? = null,
    val issue: String? = null,
    val explicitTime: Boolean = false,
    val fields: VisualFields = VisualFields(),
    val amountCents: Long? = null,
    val type: BillType? = null,
    val warnings: List<String> = emptyList(),
)
