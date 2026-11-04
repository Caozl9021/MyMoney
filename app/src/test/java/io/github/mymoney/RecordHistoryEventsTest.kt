package io.github.mymoney

import io.github.mymoney.data.*
import io.github.mymoney.ui.recordHistoryEvents
import org.junit.Assert.*
import org.junit.Test

class RecordHistoryEventsTest {
    private fun recognition(id: String, at: Long, status: String) = RecognitionEntity(
        id = id, identityKey = "test:$id", priceCents = 100, type = "EXPENSE", timeMillis = 1,
        merchant = "商户", method = null, orderTail = null, sourcePkg = "test", strategy = "test",
        fingerprint = id, category = null, subCategory = null, account = null, note = null,
        transactionKey = null, hasExplicitTime = true, captureSessionId = null,
        recognizedAt = at, updatedAt = at, status = status,
    )
    private fun deletion(id: String, at: Long) = BillDeletionLog(
        eventId = id, deletedAt = at, origin = "DETAIL", principalId = 1, recognitionId = "same",
        groupId = "group:$id", snapshotVersion = 1, snapshotJson = "{}",
    )

    @Test fun allKeepsRecognitionAndDeletionAsDistinctEventsWithStableTimeOrder() {
        val records = listOf(recognition("same", 100, RecognitionStatus.RECORDED),
            recognition("pending", 200, RecognitionStatus.PENDING))
        val deleted = listOf(deletion("same", 300), deletion("other", 200))
        val result = recordHistoryEvents(records, deleted, "ALL")
        assertEquals(listOf("deletion:same", "deletion:other", "recognition:pending", "recognition:same"), result.map { it.key })
        assertEquals(4, result.size)
        assertEquals(RecognitionStatus.RECORDED, records.first().status)
    }

    @Test fun deletionAndRecognitionFiltersNeverCrossKinds() {
        val records = listOf(recognition("pending", 1, RecognitionStatus.PENDING),
            recognition("recorded", 2, RecognitionStatus.RECORDED),
            recognition("ignored", 3, RecognitionStatus.IGNORED))
        val deleted = listOf(deletion("deleted", 4))
        assertEquals(listOf("deletion:deleted"), recordHistoryEvents(records, deleted, "DELETED").map { it.key })
        for ((status, id) in listOf(RecognitionStatus.PENDING to "pending", RecognitionStatus.RECORDED to "recorded", RecognitionStatus.IGNORED to "ignored")) {
            assertEquals(listOf("recognition:$id"), recordHistoryEvents(records, deleted, status).map { it.key })
        }
        assertTrue(recordHistoryEvents(emptyList(), emptyList(), "ALL").isEmpty())
    }
}
