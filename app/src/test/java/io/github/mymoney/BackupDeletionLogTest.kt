package io.github.mymoney

import io.github.mymoney.data.Backup
import io.github.mymoney.data.BillDeletionCodec
import io.github.mymoney.data.BillDeletionLog
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.MiniJson
import org.junit.Assert.*
import org.junit.Test

class BackupDeletionLogTest {
    private fun bill(id: Long = 41) = BillEntity(id, 1234, "EXPENSE", 1700000000000,
        "商户", "原支付方式", "1234", "simulation", "manual", "original-fingerprint", 1700000000000,
        category = "餐饮", subCategory = "午餐", account = "原账户", note = "人工备注",
        recognitionId = "recognized", accountId = "account", assetLinked = true, assetKey = "stable-key",
        productName = "原商品", productSource = "PRODUCT", noteOrigin = "MANUAL", noteTemplateSnapshot = "模板")
    private fun log(id: String = "c7182af8-8e82-4b14-9bc1-7eeb786d1835", at: Long = 1700000001000) =
        BillDeletionCodec.create(listOf(bill()), "detail", at, id)
    private fun snapshot(logs: List<BillDeletionLog>?) = Backup.Snapshot(1700000002000,
        emptyList(), null, null, deletions = logs)
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected malformed/conflicting audit log to be rejected") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun json9RoundTripKeepsAllFrozenBillFieldsWithoutRevivingBillsOrAssets() {
        val original = snapshot(listOf(log()))
        val encoded = Backup.encode(original)
        // 删除日志自 format 9 引入；当前导出为 format 10（W4 起含稳定 syncId）。
        assertTrue(encoded.contains("\"format\":${Backup.FORMAT}"))
        val restored = Backup.decode(encoded)
        assertEquals(original, restored)
        assertTrue(restored.bills.isEmpty())
        assertNull(restored.assets)
        assertEquals(listOf(bill()), BillDeletionCodec.bills(restored.deletions!!.single()))
    }

    @Test fun allOlderFormatsRemainReadableAndKeepAbsentLogsDistinctFromEmpty() {
        for (format in 1..8) {
            assertNull(Backup.decode("""{"kind":"MyMoneyBackup","format":$format,"bills":[]}""").deletions)
        }
        assertEquals(emptyList<BillDeletionLog>(), Backup.decode(Backup.encode(snapshot(emptyList()))).deletions)
    }

    @Test fun logsCannotBeHiddenInOlderEnvelopeAndNullIsNotAnArray() {
        val encoded = Backup.encode(snapshot(listOf(log())))
        // 删除日志最低要求 format 9；降到 8 必须拒绝（无论当前导出格式是几）。
        reject { Backup.decode(encoded.replace("\"format\":${Backup.FORMAT}", "\"format\":8")) }
        reject { Backup.decode("""{"format":9,"bills":[],"deletions":null}""") }
    }

    @Test fun duplicateEventIdsAreRejectedOnEncodeAndDecode() {
        val row = log()
        reject { Backup.encode(snapshot(listOf(row, row))) }
        val event = BillDeletionCodec.encode(row)
        reject { Backup.decode(MiniJson.encode(mapOf("format" to 9, "bills" to emptyList<Any>(),
            "deletions" to listOf(event, event)))) }
    }

    @Test fun auditMergeRetainsLocalWhenIncomingOldBackupHasNoLogsAndEmptyDoesNotClear() {
        val row = log()
        assertEquals(listOf(row), Backup.mergeDeletionLogs(listOf(row), emptyList()))
    }

    @Test fun auditMergeIsIdempotentAndStableByEventTime() {
        val old = log()
        val newer = log("a7f8a6a0-83b0-44f1-8d8c-f4469c442afd", old.deletedAt + 1)
        val merged = Backup.mergeDeletionLogs(listOf(old), listOf(newer, old))
        assertEquals(listOf(newer, old), merged)
        assertEquals(merged, Backup.mergeDeletionLogs(merged, listOf(newer, old)))
    }

    @Test fun sameEventWithDifferentSnapshotOrMetadataRejectsWholeMerge() {
        val row = log()
        val edited = BillDeletionCodec.create(listOf(bill().copy(note = "不同备注")), row.origin, row.deletedAt, row.eventId)
        reject { Backup.mergeDeletionLogs(listOf(row), listOf(edited)) }
        reject { Backup.mergeDeletionLogs(listOf(row), listOf(row.copy(origin = "ledger"))) }
        reject { Backup.mergeDeletionLogs(listOf(row), listOf(row.copy(deletedAt = row.deletedAt + 1))) }
    }

    @Test fun invalidFrozenGroupCannotEnterBackupEvenThoughItNeverReentersLiveLedger() {
        val row = log()
        reject { Backup.encode(snapshot(listOf(row.copy(snapshotVersion = 2)))) }
        reject { Backup.encode(snapshot(listOf(row.copy(principalId = 999)))) }
        reject { Backup.encode(snapshot(listOf(row.copy(snapshotJson = "{}")))) }
    }
}
