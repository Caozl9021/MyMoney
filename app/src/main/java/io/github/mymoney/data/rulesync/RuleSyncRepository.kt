package io.github.mymoney.data.rulesync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.billkit.spec.PersonalRule
import com.example.billkit.spec.PersonalRuleCodec
import com.example.billkit.spec.RulePackageFiles
import io.github.mymoney.data.AppDb
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.MerchantNoteRule
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.NoteFillCodec
import io.github.mymoney.data.NoteFillConfig
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local authority for the four synced rule kinds. It deliberately lives in its own
 * Room database so the accounting Room9 schema and JSON8 backups stay untouched.
 */
object RuleSyncRepository {
    private const val TAG = "RuleSyncRepository"
    private val mutex = Mutex()

    @Volatile private var db: RuleSyncDb? = null
    @Volatile private var snapshotState = RuleSyncSnapshot()
    @Volatile private var ready = false

    fun isReady(): Boolean = ready
    fun snapshot(): RuleSyncSnapshot = snapshotState



    /**
     * Aligns local items that already exist with identical content on the family
     * server before uploading. Identical note_defaults/official switches must not
     * be pushed with baseRevision=0, otherwise a fresh second device creates a
     * fake conflict during its first merge.
     */
    suspend fun prepareInitialMerge(remote: List<RuleSyncRemoteItem>): Set<String> {
        val database = database()
        val skip = mutableSetOf<String>()
        remote.filter { it.deletedAt == null }.forEach { remoteItem ->
            val local = database.rules().findById(remoteItem.itemId) ?: return@forEach
            if (local.deletedAt == null && RuleSyncCodec.samePayload(local.payload, remoteItem.payload)) {
                database.rules().updateServerRevision(local.id, remoteItem.revision, remoteItem.contentUpdatedAt)
                skip += local.id
            }
        }
        snapshotState = load(database)
        return skip
    }

    suspend fun enqueueAllForInitialSync(skipIds: Set<String> = emptySet()): Int {
        val database = database()
        val pendingIds = database.outbox().byStatus(SyncOutboxEntity.STATUS_PENDING).map { it.itemId }.toSet()
        var count = 0
        database.rules().activeItems().forEach { item ->
            if (item.id !in pendingIds && item.id !in skipIds) {
                writeItem(database, item, "put")
                count++
            }
        }
        return count
    }

    suspend fun pendingForItem(itemId: String): Boolean =
        database().outbox().byStatus(SyncOutboxEntity.STATUS_PENDING).any { it.itemId == itemId }

    suspend fun conflicts(): List<SyncConflictEntity> = database().conflicts().all()

    suspend fun pendingOutbox(): List<SyncOutboxEntity> = database().outbox().byStatus(SyncOutboxEntity.STATUS_PENDING)

    suspend fun outboxCount(): Int = database().outbox().total()

    suspend fun markOutboxSynced(operationId: String) {
        database().outbox().delete(operationId)
    }

    suspend fun markOutboxFailed(operationId: String, error: String) {
        database().outbox().updateStatus(operationId, SyncOutboxEntity.STATUS_FAILED, error.take(500))
    }

    suspend fun updateItemServerRevision(id: String, revision: Long, contentUpdatedAt: Long?) {
        database().rules().updateServerRevision(id, revision, contentUpdatedAt)
        snapshotState = load(database())
    }

    suspend fun currentState(): SyncStateEntity = database().state().get() ?: SyncStateEntity()

    suspend fun bindSpace(spaceId: String, epoch: String, enqueue: Boolean = true) {
        val database = database()
        val state = database.state().get() ?: SyncStateEntity()
        val fresh = state.spaceId == null
        val changed = state.spaceId != null && (state.spaceId != spaceId || state.epoch != epoch)
        if (changed) {
            // A different space/epoch must never inherit the previous space's queue or revisions.
            database.outbox().clearAll()
            database.conflicts().clearAll()
            database.rules().resetServerRevisions()
        }
        database.state().upsert(state.copy(spaceId = spaceId, epoch = epoch, cursor = 0, lastPulledRevision = 0))
        snapshotState = load(database)
        if ((fresh || changed) && enqueue) enqueueAllForInitialSync()
    }

    /** Leave pairing state without changing any local rule content. */
    suspend fun unbindSpace() {
        val database = database()
        database.outbox().clearAll()
        database.conflicts().clearAll()
        database.rules().resetServerRevisions()
        val state = database.state().get() ?: SyncStateEntity()
        database.state().upsert(state.copy(spaceId = null, epoch = null, cursor = 0, lastPulledRevision = 0, lastSyncAt = null))
        snapshotState = load(database)
    }

    suspend fun activeItems(): List<LocalRuleItemEntity> = database().rules().activeItems()

    suspend fun setCursor(cursor: Long, lastPulledRevision: Long, lastSyncAt: Long) {
        database().state().updateCursor(cursor, lastPulledRevision, lastSyncAt)
        snapshotState = load(database())
    }

    suspend fun saveConflict(item: SyncConflictEntity) {
        database().conflicts().upsert(item)
    }

    suspend fun clearConflict(itemId: String) {
        database().conflicts().delete(itemId)
    }

    suspend fun localItem(id: String): LocalRuleItemEntity? = database().rules().findById(id)

    /**
     * Older 1.6.0/119 builds could record a conflict when both sides already had
     * the same content. Drop those duplicates without asking the user to choose.
     */
    suspend fun repairIdenticalConflicts(): Int {
        val database = database()
        var repaired = 0
        database.conflicts().all().forEach { conflict ->
            val local = database.rules().findById(conflict.itemId) ?: return@forEach
            val remoteMap = runCatching { MiniJson.decode(conflict.remotePayload) as? Map<*, *> }.getOrNull()
                ?: return@forEach
            val raw = remoteMap["payload"] ?: return@forEach
            val remotePayload = if (raw is String) raw else MiniJson.encode(raw)
            if (local.deletedAt == null && RuleSyncCodec.samePayload(local.payload, remotePayload)) {
                database.withTransaction {
                    database.rules().updateServerRevision(local.id, conflict.remoteRevision, local.contentUpdatedAt)
                    database.outbox().deleteByItem(local.id)
                    database.conflicts().delete(local.id)
                }
                repaired++
            }
        }
        if (repaired > 0) snapshotState = load(database)
        return repaired
    }

    suspend fun clearOutboxForItem(itemId: String) {
        database().outbox().deleteByItem(itemId)
    }

    /** Re-submit the local candidate against the latest remote revision. */
    suspend fun resolveConflictKeepLocal(itemId: String): Boolean {
        val database = database()
        val conflict = database.conflicts().all().firstOrNull { it.itemId == itemId } ?: return false
        val local = database.rules().findById(itemId) ?: return false
        val payload = conflict.localPayload.ifBlank { local.payload }
        database.outbox().deleteByItem(itemId)
        writeItem(database, local.copy(payload = payload, serverRevision = conflict.remoteRevision), "put")
        database.conflicts().delete(itemId)
        return true
    }

    /** Apply the family-side snapshot and discard the local candidate. */
    suspend fun resolveConflictKeepRemote(itemId: String): Boolean {
        val database = database()
        val conflict = database.conflicts().all().firstOrNull { it.itemId == itemId } ?: return false
        val map = runCatching { MiniJson.decode(conflict.remotePayload) as? Map<*, *> }.getOrNull() ?: return false
        val payloadValue = map["payload"]
        val payload = when (payloadValue) {
            null -> "{}"
            is String -> payloadValue
            else -> MiniJson.encode(payloadValue)
        }
        val remote = RuleSyncRemoteItem(
            itemId = map["itemId"] as? String ?: itemId,
            kind = map["kind"] as? String ?: return false,
            schemaVersion = 1,
            payload = payload,
            naturalKey = null,
            sortKey = 0,
            revision = conflict.remoteRevision,
            contentUpdatedAt = null,
            deletedAt = null,
        )
        database.outbox().deleteByItem(itemId)
        applyRemote(remote)
        database.conflicts().delete(itemId)
        return true
    }

    suspend fun applyRemote(item: RuleSyncRemoteItem) {
        val database = database()
        database.withTransaction {
            val state = database.state().get() ?: SyncStateEntity()
            val generation = state.desiredGeneration + 1
            val existing = database.rules().findById(item.itemId)
            if (existing == null) {
                database.rules().upsert(LocalRuleItemEntity(
                    id = item.itemId,
                    kind = item.kind,
                    schemaVersion = item.schemaVersion,
                    payload = item.payload,
                    naturalKey = item.naturalKey,
                    sortKey = item.sortKey,
                    serverRevision = item.revision,
                    contentUpdatedAt = item.contentUpdatedAt,
                    deletedAt = item.deletedAt,
                    localGeneration = generation,
                ))
            } else {
                database.rules().applyRemote(
                    id = item.itemId,
                    payload = item.payload,
                    naturalKey = item.naturalKey,
                    sortKey = item.sortKey,
                    revision = item.revision,
                    contentUpdatedAt = item.contentUpdatedAt,
                    deletedAt = item.deletedAt,
                    generation = generation,
                )
            }
            database.state().upsert(state.copy(desiredGeneration = generation))
        }
        snapshotState = load(database)
    }

    suspend fun init(context: Context): RuleSyncSnapshot = mutex.withLock {
        if (ready) return@withLock snapshotState
        val database = RuleSyncDb.get(context.applicationContext)
        db = database
        if (database.state().get()?.migrationCompleted != true) {
            migrate(context.applicationContext, database)
        }
        snapshotState = load(database)
        ready = true
        snapshotState
    }

    suspend fun reload(): RuleSyncSnapshot = mutex.withLock {
        val database = db ?: error("规则同步库未初始化")
        snapshotState = load(database)
        snapshotState
    }

    suspend fun markProjectionActive(generation: Long) = mutex.withLock {
        val database = db ?: return@withLock
        val state = database.state().get() ?: SyncStateEntity()
        if (state.activeGeneration != generation) {
            database.state().upsert(state.copy(activeGeneration = generation))
        }
        snapshotState = load(database)
    }

    fun personalRules(): List<PersonalRule> = snapshotState.personalRules
    fun officialEnabled(): Map<String, Boolean> = snapshotState.officialEnabled
    fun noteTemplate(): String = snapshotState.noteTemplate
    fun noteConfig(): NoteFillConfig = snapshotState.noteConfig()

    suspend fun savePersonalRule(rule: PersonalRule): PersonalRule {
        rule.validate()
        val database = database()
        val old = database.rules().findById(rule.id)
        val oldRule = old?.let { runCatching { RuleSyncCodec.personalFromPayload(it.payload) }.getOrNull() }
        val sameContent = oldRule != null && oldRule.copy(enabled = rule.enabled, updatedAt = null) == rule.copy(updatedAt = null)
        val saved = rule.copy(updatedAt = if (sameContent) oldRule!!.updatedAt else System.currentTimeMillis())
        val item = LocalRuleItemEntity(
            id = saved.id,
            kind = RuleSyncKind.RECOGNITION_RULE,
            payload = RuleSyncCodec.personalPayload(saved),
            naturalKey = saved.id,
            sortKey = old?.sortKey ?: nextSort(database, RuleSyncKind.RECOGNITION_RULE),
            serverRevision = old?.serverRevision ?: 0,
            contentUpdatedAt = saved.updatedAt,
        )
        writeItem(database, item, "put")
        return saved
    }

    suspend fun deletePersonalRule(id: String) {
        val database = database()
        val old = database.rules().findById(id) ?: return
        writeItem(database, old.copy(deletedAt = System.currentTimeMillis()), "delete")
    }

    suspend fun importPersonalRules(text: String): List<PersonalRule> {
        val incoming = PersonalRuleCodec.decode(text)
        val existing = personalRules().filterNot { old -> incoming.any { it.id == old.id } }
        val merged = incoming + existing
        require(merged.size <= 100) { "合并后个人规则超过100条" }
        incoming.forEach { savePersonalRule(it) }
        return personalRules()
    }

    fun exportPersonalRules(): String = PersonalRuleCodec.export(personalRules())

    suspend fun setOfficialRuleEnabled(ruleId: String, enabled: Boolean) {
        require(ruleId.isNotBlank()) { "规则ID不存在" }
        val database = database()
        val id = RuleSyncCodec.stableOfficialId(ruleId)
        val old = database.rules().findById(id)
        val item = LocalRuleItemEntity(
            id = id,
            kind = RuleSyncKind.OFFICIAL_RULE_SWITCH,
            payload = RuleSyncCodec.officialPayload(enabled),
            naturalKey = ruleId,
            sortKey = old?.sortKey ?: 0,
            serverRevision = old?.serverRevision ?: 0,
            contentUpdatedAt = System.currentTimeMillis(),
        )
        writeItem(database, item, "put")
    }

    suspend fun saveMerchantRule(rule: MerchantNoteRule, originalKey: String? = null): MerchantNoteRule {
        val database = database()
        val old = when {
            rule.id.isNotBlank() -> database.rules().findById(rule.id)
            originalKey != null -> database.rules().findById(originalKey)
                ?: database.rules().findActiveByNaturalKey(RuleSyncKind.MERCHANT_NOTE_RULE, originalKey)
            else -> database.rules().findActiveByNaturalKey(RuleSyncKind.MERCHANT_NOTE_RULE, rule.key)
        }
        val saved = rule.copy(id = old?.id ?: rule.id.ifBlank { RuleSyncCodec.stableMerchantId(rule.key) })
        saved.validate()
        val duplicate = database.rules().findActiveByNaturalKey(RuleSyncKind.MERCHANT_NOTE_RULE, saved.key)
        require(duplicate == null || duplicate.id == saved.id) { "同一商家和交易类型只能有一条备注规则" }
        val item = LocalRuleItemEntity(
            id = saved.id,
            kind = RuleSyncKind.MERCHANT_NOTE_RULE,
            payload = RuleSyncCodec.merchantPayload(saved),
            naturalKey = saved.key,
            sortKey = old?.sortKey ?: nextSort(database, RuleSyncKind.MERCHANT_NOTE_RULE),
            serverRevision = old?.serverRevision ?: 0,
            contentUpdatedAt = System.currentTimeMillis(),
        )
        writeItem(database, item, "put")
        return saved
    }

    suspend fun deleteMerchantRule(key: String) {
        val database = database()
        val old = database.rules().findById(key)
            ?: database.rules().findActiveByNaturalKey(RuleSyncKind.MERCHANT_NOTE_RULE, key)
            ?: return
        writeItem(database, old.copy(deletedAt = System.currentTimeMillis()), "delete")
    }

    suspend fun setProductFirst(productFirst: Boolean) {
        updateNoteDefaults(productFirst = productFirst)
    }

    suspend fun saveNoteTemplate(template: String) {
        require(template.length <= 200) { "备注模板过长" }
        updateNoteDefaults(noteTemplate = template)
    }

    private suspend fun updateNoteDefaults(productFirst: Boolean? = null, noteTemplate: String? = null) {
        val database = database()
        val old = database.rules().findById(NOTE_DEFAULTS_ID)
        val currentTemplate = old?.let { runCatching { RuleSyncCodec.noteDefaultsFromPayload(it.payload).first }.getOrNull() } ?: snapshotState.noteTemplate
        val currentProductFirst = old?.let { runCatching { RuleSyncCodec.noteDefaultsFromPayload(it.payload).second }.getOrNull() } ?: snapshotState.productFirst
        val item = LocalRuleItemEntity(
            id = NOTE_DEFAULTS_ID,
            kind = RuleSyncKind.NOTE_DEFAULTS,
            payload = RuleSyncCodec.noteDefaultsPayload(noteTemplate ?: currentTemplate, productFirst ?: currentProductFirst),
            naturalKey = null,
            sortKey = old?.sortKey ?: 0,
            serverRevision = old?.serverRevision ?: 0,
            contentUpdatedAt = System.currentTimeMillis(),
        )
        writeItem(database, item, "put")
    }

    /** Used by local JSON8 restore; it replaces merchant rules as a single local edit batch. */
    suspend fun replaceNoteConfig(config: NoteFillConfig) {
        config.validate()
        val database = database()
        val existing = database.rules().activeItems().filter { it.kind == RuleSyncKind.MERCHANT_NOTE_RULE }
        val existingByKey = existing.associateBy { it.naturalKey }
        existing.forEach {
            writeItem(database, it.copy(deletedAt = System.currentTimeMillis()), "delete")
        }
        config.rules.forEachIndexed { index, rule ->
            val id = rule.id.ifBlank { existingByKey[rule.key]?.id ?: RuleSyncCodec.stableMerchantId(rule.key) }
            val saved = rule.copy(id = id)
            saved.validate()
            writeItem(database, LocalRuleItemEntity(
                id = saved.id,
                kind = RuleSyncKind.MERCHANT_NOTE_RULE,
                payload = RuleSyncCodec.merchantPayload(saved),
                naturalKey = saved.key,
                sortKey = index.toLong() * 1000,
                contentUpdatedAt = System.currentTimeMillis(),
            ), "put")
        }
        updateNoteDefaults(productFirst = config.productFirst)
    }

    private fun database(): RuleSyncDb = db ?: error("规则同步库未初始化")

    private suspend fun load(database: RuleSyncDb): RuleSyncSnapshot {
        val state = database.state().get() ?: SyncStateEntity()
        val items = database.rules().activeItems()
        val personal = items.filter { it.kind == RuleSyncKind.RECOGNITION_RULE }.mapNotNull {
            runCatching { RuleSyncCodec.personalFromPayload(it.payload) }
                .onFailure { error -> Log.w(TAG, "个人规则投影损坏：${it.id}", error) }.getOrNull()
        }
        val official = items.filter { it.kind == RuleSyncKind.OFFICIAL_RULE_SWITCH }.mapNotNull { item ->
            item.naturalKey?.let { key -> runCatching { key to RuleSyncCodec.officialFromPayload(item.payload) }.getOrNull() }
        }.toMap()
        val merchant = items.filter { it.kind == RuleSyncKind.MERCHANT_NOTE_RULE }.mapNotNull {
            runCatching { RuleSyncCodec.merchantFromPayload(it.payload) }
                .onFailure { error -> Log.w(TAG, "商家备注规则投影损坏：${it.id}", error) }.getOrNull()
        }
        val defaults = items.firstOrNull { it.kind == RuleSyncKind.NOTE_DEFAULTS }?.let {
            runCatching { RuleSyncCodec.noteDefaultsFromPayload(it.payload) }.getOrNull()
        }
        return RuleSyncSnapshot(
            personalRules = personal,
            officialEnabled = official,
            merchantRules = merchant,
            noteTemplate = defaults?.first ?: "{商户}",
            productFirst = defaults?.second ?: true,
            generation = state.desiredGeneration,
            activeGeneration = state.activeGeneration,
        )
    }

    private suspend fun migrate(context: Context, database: RuleSyncDb) {
        val packageDir = File(context.filesDir, "rule-packages")
        val stateFile = File(packageDir, "state.json")
        // If the old state exists but is unreadable, fail the migration instead of silently
        // dropping the user's rules. App falls back to the old source until this is fixed.
        val packageState = if (stateFile.exists()) RulePackageFiles(packageDir).state() else RulePackageFiles.State()
        val oldRoom = if (stateFile.exists()) emptyMap() else runCatching {
            AppDb.get(context).rules().allRules()
                .filter { it.creator == "maimi" && !it.enabled && it.struct != "maimi:experimental" }
                .associate { it.systemRuleName to false }
        }.getOrElse { emptyMap() }
        val enabled = packageState.enabled + oldRoom
        val legacyPolicy = runCatching { LedgerConfig.noteFillPolicy?.let(NoteFillCodec::decode) }.getOrNull()
            ?: NoteFillConfig()
        val template = runCatching { LedgerConfig.noteTemplate }.getOrDefault("{商户}")
        writeLegacySnapshot(context, packageState, legacyPolicy, template)

        val now = System.currentTimeMillis()
        val items = RuleSyncMigration.plan(
            RuleSyncMigration.Input(
                packageState = packageState,
                legacyPolicy = legacyPolicy,
                noteTemplate = template,
                oldRoomDisabled = oldRoom,
            )
        )
        database.withTransaction {
            database.rules().upsertAll(items)
            val old = database.state().get() ?: SyncStateEntity()
            database.state().upsert(old.copy(
                migrationCompleted = true,
                desiredGeneration = maxOf(old.desiredGeneration, 1L),
                activeGeneration = old.activeGeneration,
            ))
        }
        Log.i(TAG, "旧规则已迁移：个人${packageState.personalRules.size}，官方开关${enabled.size}，备注${legacyPolicy.rules.size}，时间${now}")
    }

    private fun writeLegacySnapshot(
        context: Context,
        packageState: RulePackageFiles.State,
        policy: NoteFillConfig,
        template: String,
    ) {
        runCatching {
            val file = File(context.filesDir, "rule-sync/legacy-snapshot-v1.json")
            file.parentFile?.mkdirs()
            val payload = MiniJson.encode(mapOf(
                "state" to mapOf(
                    "active" to packageState.active,
                    "previous" to packageState.previous,
                    "canRollback" to packageState.canRollback,
                    "enabled" to packageState.enabled,
                    "versions" to packageState.versions,
                    "personalRules" to packageState.personalRules.map { it.json() },
                ),
                "noteFillPolicy" to NoteFillCodec.encode(policy),
                "noteTemplate" to template,
            ))
            file.writeText(payload)
            File(file.parentFile, "legacy-snapshot-v1.sha256").writeText(sha256(payload))
        }.onFailure { Log.w(TAG, "旧规则快照写入失败，不阻塞迁移", it) }
    }

    private suspend fun writeItem(database: RuleSyncDb, item: LocalRuleItemEntity, operation: String) {
        val now = System.currentTimeMillis()
        val opId = UUID.randomUUID().toString()
        val request = MiniJson.encode(mapOf(
            "operationId" to opId,
            "operation" to operation,
            "itemId" to item.id,
            "kind" to item.kind,
            "schemaVersion" to item.schemaVersion,
            "baseRevision" to item.serverRevision,
            "contentUpdatedAt" to item.contentUpdatedAt,
            "sortKey" to item.sortKey.toString(),
            "naturalKey" to item.naturalKey,
            "payload" to runCatching { MiniJson.decode(item.payload) }.getOrNull(),
        ))
        database.withTransaction {
            val state = database.state().get() ?: SyncStateEntity()
            val generation = state.desiredGeneration + 1
            database.rules().upsert(item.copy(localGeneration = generation))
            database.outbox().upsert(SyncOutboxEntity(
                operationId = opId, itemId = item.id, baseRevision = item.serverRevision,
                requestJson = request, createdAt = now,
            ))
            database.state().upsert(state.copy(desiredGeneration = generation))
        }
        snapshotState = load(database)
    }

    private suspend fun nextSort(database: RuleSyncDb, kind: String): Long =
        (database.rules().activeItems().filter { it.kind == kind }.maxOfOrNull { it.sortKey } ?: 0L) + 1000L

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val NOTE_DEFAULTS_ID = "note_defaults"
}
