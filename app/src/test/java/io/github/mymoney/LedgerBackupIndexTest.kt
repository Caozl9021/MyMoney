package io.github.mymoney

import io.github.mymoney.data.rulesync.LedgerBackupApi
import org.junit.Assert.*
import org.junit.Test

class LedgerBackupIndexTest {
    private fun row(schema: Int) = mapOf(
        "id" to "c9116312-2ed4-478d-a933-3f7945a87c24", "deviceName" to "test", "appVersion" to "1.8.0",
        "sha256" to "a".repeat(64), "sizeBytes" to 20, "billCount" to 0, "backupSchema" to schema,
        "snapshotAt" to "2026-11-04T00:00:00Z", "uploadedAt" to "2026-11-04T00:00:00Z",
    )
    @Test fun readsBothExistingAndNewFamilySnapshotIndexes() {
        assertEquals(8, LedgerBackupApi.entry(row(8)).backupSchema)
        assertEquals(9, LedgerBackupApi.entry(row(9)).backupSchema)
        assertEquals(10, LedgerBackupApi.entry(row(10)).backupSchema)
    }
    @Test fun refusesFutureOrUnsupportedFamilySnapshotFormats() {
        for (schema in listOf(7, 11)) assertTrue(runCatching { LedgerBackupApi.entry(row(schema)) }.isFailure)
    }
}
