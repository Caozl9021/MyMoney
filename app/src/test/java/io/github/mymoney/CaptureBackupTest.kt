package io.github.mymoney

import io.github.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test

class CaptureBackupTest {
    private fun row(id: String = "history-a", identity: String = "order:a") = RecognitionEntity.fromDraft(
        BillDraft(1999, BillType.EXPENSE, 1700000000000, "商户", "零钱", null, "com.tencent.mm", "fixture"), identity, 1700000001000,
    ).copy(id = id)
    private fun metadata(id: String = "history-a") = CaptureSnapshot(
        aliases = listOf(CaptureAlias("obs:device-a:user-a:notification-instance", id), CaptureAlias("order:provider:order-a:EXPENSE", id)),
        evidence = listOf(CaptureEvidence("event-a", id, "NOTIFICATION", "com.tencent.mm", 1700000000000, "matched", "template:fixture")),
        deliveries = listOf(CaptureDelivery(id, "ACKNOWLEDGED", "attempt-a", 1700000001000)),
    )
    private fun backup(history: List<RecognitionEntity> = listOf(row()), capture: CaptureSnapshot? = metadata()) =
        Backup.Snapshot(1700000002000, emptyList(), null, null, history = history, capture = capture)
    private fun rejects(block: () -> Unit) {
        assertTrue("Malformed backup must fail before restore", runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun format4RoundTripPreservesAliasesEvidenceAndUserProtection() {
        val a = row().copy(protectedFields = 264L)
        val b = row("review-b", "session:b").copy(reviewOf = a.id)
        val snapshot = backup(listOf(a, b))
        val encoded = Backup.encode(snapshot)
        assertTrue(encoded.contains("\"format\":${Backup.FORMAT}"))
        assertEquals(snapshot, Backup.decode(encoded))
    }

    @Test fun previousFormatsHaveNoCaptureAndSafeProtectionDefaults() {
        for (format in 1..3) {
            val raw = """{"format":$format,"bills":[],"history":[]}"""
            assertNull(Backup.decode(raw).capture)
        }
        val oldRow = RecognitionCodec.encode(row()).toMutableMap().apply {
            remove("protectedFields"); remove("reviewOf")
        }
        assertEquals(0L, RecognitionCodec.decode(oldRow).protectedFields)
        assertNull(RecognitionCodec.decode(oldRow).reviewOf)
    }

    @Test fun exportOmitsUnlinkedDiagnosticsAndDoesNotEncodeNotificationBody() {
        val linked = metadata()
        val rawOnly = CaptureEvidence("diagnostic-only", null, "NOTIFICATION", "com.tencent.mm", 1, "unverified-template", "no raw body")
        val encoded = Backup.encode(backup(capture = linked.copy(evidence = linked.evidence + rawOnly)))
        assertFalse(encoded.contains("diagnostic-only"))
        assertFalse(encoded.contains("no raw body"))
        assertEquals(linked, Backup.decode(encoded).capture)
    }

    @Test fun portableBackupExcludesMutableReviewSlotButKeepsDurableIdentities() {
        val durable = metadata()
        val withHints = durable.copy(aliases = durable.aliases +
            CaptureAlias("review-slot:local-notification-slot", "old-local-target-not-in-backup"))
        assertEquals(durable, CaptureMetadataCodec.portable(withHints))
        val encoded = Backup.encode(backup(capture = withHints))
        assertFalse(encoded.contains("review-slot:"))
        assertEquals(durable, Backup.decode(encoded).capture)
    }

    @Test fun legacyReviewSlotInBackupIsIgnoredEvenIfItsLocalTargetNoLongerExists() {
        val encoded = Backup.encode(backup())
        val root = (MiniJson.decode(encoded) as Map<*, *>).toMutableMap()
        val capture = (root["capture"] as Map<*, *>).toMutableMap()
        capture["aliases"] = (capture["aliases"] as List<*>) + mapOf(
            "aliasKey" to "review-slot:old-exported-hint", "recognitionId" to "deleted-local-history",
        )
        root["capture"] = capture
        assertEquals(metadata(), Backup.decode(MiniJson.encode(root)).capture)
        val restored = CaptureMetadataCodec.remapForRestore(
            metadata().copy(aliases = metadata().aliases + CaptureAlias("review-slot:direct-old-hint", "missing")),
            mapOf("history-a" to "local-history"),
        )
        assertEquals(2, restored.aliases.size)
        assertTrue(restored.aliases.all { it.recognitionId == "local-history" })
    }

    @Test fun restoringOldSnapshotKeepsCurrentLocalSlotAndDoesNotRestoreOtherSlots() {
        val current = metadata().copy(aliases = metadata().aliases + CaptureAlias("review-slot:shared-slot", "history-b"))
        val old = metadata().copy(aliases = metadata().aliases +
            CaptureAlias("review-slot:shared-slot", "history-a") + CaptureAlias("review-slot:old-other-slot", "history-a"))
        val merged = CaptureMetadataCodec.merge(current, old)
        assertEquals(current, merged)
        val intoEmpty = CaptureMetadataCodec.merge(CaptureSnapshot(), old)
        assertEquals(metadata().aliases, intoEmpty.aliases)
        assertTrue(intoEmpty.aliases.none { it.aliasKey.startsWith("review-slot:") })
        val conflictingDurable = old.copy(aliases = old.aliases + CaptureAlias("obs:device-a:user-a:notification-instance", "history-b"))
        rejects { CaptureMetadataCodec.merge(current, conflictingDurable) }
    }

    @Test fun danglingAndDuplicateMetadataRejectWholeBackup() {
        rejects { Backup.encode(backup(capture = metadata("missing"))) }
        rejects { Backup.encode(backup(capture = metadata().copy(aliases = metadata().aliases + metadata().aliases.first()))) }
        rejects { Backup.encode(backup(capture = metadata().copy(evidence = metadata().evidence + metadata().evidence.first()))) }
        rejects { Backup.encode(backup(capture = metadata().copy(deliveries = metadata().deliveries + metadata().deliveries.first()))) }
        rejects { Backup.encode(backup(listOf(row().copy(reviewOf = "missing")))) }
        rejects { Backup.encode(Backup.Snapshot(1, emptyList(), null, null, capture = metadata())) }
    }

    @Test fun invalidMetadataIntegersStatusAndReferencesReject() {
        val encoded = Backup.encode(backup())
        rejects { Backup.decode(encoded.replace("\"receivedAt\":1700000000000", "\"receivedAt\":1700000000000.5")) }
        rejects { Backup.decode(encoded.replace("\"state\":\"ACKNOWLEDGED\"", "\"state\":\"UNKNOWN_STATE\"")) }
        rejects { Backup.decode(encoded.replace("\"protectedFields\":0", "\"protectedFields\":512")) }
        rejects { Backup.decode(encoded.replace("\"protectedFields\":0", "\"protectedFields\":0.5")) }
        rejects { Backup.decode(encoded.replace("\"format\":${Backup.FORMAT}", "\"format\":3")) }
    }

    @Test fun restoreRemapsCanonicalIdsAndNeverResumesClaimedWork() {
        val a = metadata().copy(deliveries = listOf(CaptureDelivery("history-a", "CLAIMED", "remote-attempt", 3)))
        val restored = CaptureMetadataCodec.remapForRestore(a, mapOf("history-a" to "local-history"))
        assertTrue(restored.aliases.all { it.recognitionId == "local-history" })
        assertTrue(restored.evidence.all { it.recognitionId == "local-history" })
        assertEquals("RESTORED", restored.deliveries.single().state)
        assertEquals("local-history", restored.deliveries.single().recognitionId)
        rejects { CaptureMetadataCodec.remapForRestore(a, emptyMap()) }
    }

    @Test fun mergeAliasConflictRejectsRatherThanRebindingToOtherHistory() {
        val local = metadata()
        val conflicting = local.copy(aliases = listOf(local.aliases.first().copy(recognitionId = "other")))
        rejects { CaptureMetadataCodec.merge(local, conflicting) }
        val eventConflict = local.copy(evidence = listOf(local.evidence.first().copy(recognitionId = "other")))
        rejects { CaptureMetadataCodec.merge(local, eventConflict) }
        assertEquals(local, CaptureMetadataCodec.merge(local, local))
    }

    @Test fun mergeKeepsLocalDeliveryAndRestoresOnlyNewEntriesAsInert() {
        val local = metadata()
        val incoming = local.copy(deliveries = listOf(local.deliveries.single().copy(state = "CLAIMED", attemptId = "remote"),
            CaptureDelivery("new-history", "CLAIMED", "new-remote", 5)))
        val merged = CaptureMetadataCodec.merge(local, incoming)
        assertEquals(local.deliveries.first(), merged.deliveries.first())
        assertEquals("RESTORED", merged.deliveries.last().state)
    }

    @Test fun newerBackupCannotOverwriteLocalUserEditedFields() {
        val local = row().copy(merchant = "人工商户", note = "人工备注", category = "人工分类", protectedFields = 256)
        val incoming = local.copy(merchant = "远端商户", note = "远端备注", category = "远端分类", protectedFields = 8, updatedAt = local.updatedAt + 1)
        val merged = mergeRecognition(local, incoming)
        assertEquals(local.merchant, merged.merchant)
        assertEquals(local.note, merged.note)
        assertEquals(local.category, merged.category)
        assertEquals(264L, merged.protectedFields)
    }

    @Test fun recordedImportConflictingWithUserDraftRejectsInsteadOfSplittingLedgerAndHistory() {
        val local = row().copy(priceCents = 2999, protectedFields = CaptureFields.AMOUNT)
        val incoming = local.copy(priceCents = 1999, status = RecognitionStatus.RECORDED, billId = 42)
        rejects { mergeRecognition(local, incoming) }
    }

    @Test fun pendingImportCannotReviveIgnoredOrDowngradeRecorded() {
        val ignored = row().copy(status = RecognitionStatus.IGNORED)
        val pending = ignored.copy(status = RecognitionStatus.PENDING, updatedAt = ignored.updatedAt + 10)
        assertEquals(ignored, mergeRecognition(ignored, pending))
        val recorded = row().copy(status = RecognitionStatus.RECORDED, billId = 42)
        assertEquals(recorded, mergeRecognition(recorded, pending.copy(billId = null)))
    }
}
