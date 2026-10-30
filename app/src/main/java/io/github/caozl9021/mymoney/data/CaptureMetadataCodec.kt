package io.github.caozl9021.mymoney.data

/** Portable evidence, not a queue of work to resume after restoring a backup. */
data class CaptureSnapshot(
    val aliases: List<CaptureAlias> = emptyList(),
    val evidence: List<CaptureEvidence> = emptyList(),
    val deliveries: List<CaptureDelivery> = emptyList(),
)

/** Strict local codec. Notification text is deliberately absent from this schema. */
object CaptureMetadataCodec {
    private val deliveryStates = CaptureDeliveryState.all
    private fun localReviewHint(key: String): Boolean = key.startsWith("review-slot:")

    fun portable(snapshot: CaptureSnapshot): CaptureSnapshot = snapshot.copy(
        aliases = snapshot.aliases.filterNot { localReviewHint(it.aliasKey) },
        evidence = snapshot.evidence.filter { it.recognitionId != null },
    )

    fun encode(snapshot: CaptureSnapshot, history: List<RecognitionEntity>): Map<String, Any?> {
        val rows = portable(snapshot)
        validate(rows, history)
        return linkedMapOf(
            "aliases" to rows.aliases.map { linkedMapOf("aliasKey" to it.aliasKey, "recognitionId" to it.recognitionId) },
            "evidence" to rows.evidence.map { linkedMapOf(
                "eventId" to it.eventId, "recognitionId" to it.recognitionId, "source" to it.source,
                "packageName" to it.packageName, "receivedAt" to it.receivedAt,
                "outcome" to it.outcome, "detail" to it.detail,
            ) },
            "deliveries" to rows.deliveries.map { linkedMapOf(
                "recognitionId" to it.recognitionId, "state" to it.state,
                "attemptId" to it.attemptId, "updatedAt" to it.updatedAt,
            ) },
        )
    }

    fun decode(raw: Any?, history: List<RecognitionEntity>): CaptureSnapshot {
        val root = raw as? Map<*, *> ?: invalid("采集来源段必须为对象")
        return CaptureSnapshot(
            aliases = root.rows("aliases").mapNotNull { value -> value.obj().let {
                val key = it.text("aliasKey")
                // Older snapshots exported this mutable local hint. It is not a portable
                // identity; even its former local target may no longer exist after restore.
                if (localReviewHint(key)) null else CaptureAlias(key, it.text("recognitionId"))
            } },
            evidence = root.rows("evidence").map { value -> value.obj().let {
                CaptureEvidence(it.text("eventId"), it.text("recognitionId"), it.text("source"),
                    it.text("packageName"), it.integer("receivedAt"), it.text("outcome"), it.text("detail", allowEmpty = true))
            } },
            deliveries = root.rows("deliveries").map { value -> value.obj().let {
                CaptureDelivery(it.text("recognitionId"), it.text("state"), it.text("attemptId", allowEmpty = true), it.integer("updatedAt"))
            } },
        ).also { validate(it, history) }
    }

    fun validate(snapshot: CaptureSnapshot, history: List<RecognitionEntity>) {
        val historyIds = history.map { it.id }.toSet()
        require(historyIds.size == history.size) { "识别历史ID重复" }
        val aliases = snapshot.aliases.filterNot { localReviewHint(it.aliasKey) }
        require(aliases.map { it.aliasKey }.distinct().size == aliases.size) { "采集别名重复" }
        require(snapshot.evidence.map { it.eventId }.distinct().size == snapshot.evidence.size) { "采集事件ID重复" }
        require(snapshot.deliveries.map { it.recognitionId }.distinct().size == snapshot.deliveries.size) { "投递记录重复" }
        aliases.forEach {
            require(it.aliasKey.isNotBlank() && it.aliasKey.length <= 1024) { "采集别名无效" }
            require(it.recognitionId in historyIds) { "采集别名引用不存在的识别记录" }
        }
        snapshot.evidence.forEach {
            require(it.eventId.isNotBlank() && it.eventId.length <= 1024 && it.source.isNotBlank() && it.packageName.isNotBlank()) { "采集事件身份无效" }
            require(it.recognitionId != null && it.recognitionId in historyIds) { "采集事件引用不存在的识别记录" }
            require(it.receivedAt >= 0 && it.outcome.isNotBlank() && it.detail.length <= 4096) { "采集事件资料无效" }
        }
        snapshot.deliveries.forEach {
            require(it.recognitionId in historyIds && it.state in deliveryStates && it.updatedAt >= 0 && it.attemptId.length <= 1024) { "投递资料或状态无效" }
        }
        history.forEach {
            require(it.reviewOf == null || (it.reviewOf != it.id && it.reviewOf in historyIds)) { "待核对关联引用不存在或自身" }
        }
    }

    /** Resolve history aliases before writing metadata; restored attempts never become runnable. */
    fun remapForRestore(snapshot: CaptureSnapshot, canonical: Map<String, String>): CaptureSnapshot {
        fun mapped(id: String): String = canonical[id] ?: invalid("采集元数据缺少识别记录映射")
        val aliases = snapshot.aliases.filterNot { localReviewHint(it.aliasKey) }
            .map { it.copy(recognitionId = mapped(it.recognitionId)) }
        val evidence = snapshot.evidence.map { it.copy(recognitionId = mapped(it.recognitionId ?: invalid("采集事件缺少识别记录"))) }
        val deliveries = snapshot.deliveries.map { it.copy(recognitionId = mapped(it.recognitionId), state = "RESTORED") }
        return CaptureSnapshot(aliases, evidence, deliveries)
    }

    /** Pure merge policy also used by Room restore, so a conflict aborts the whole transaction. */
    fun merge(local: CaptureSnapshot, incoming: CaptureSnapshot): CaptureSnapshot {
        val aliases = local.aliases.associateBy { it.aliasKey }.toMutableMap()
        incoming.aliases.filterNot { localReviewHint(it.aliasKey) }.forEach { row ->
            val old = aliases[row.aliasKey]
            require(old == null || old.recognitionId == row.recognitionId) { "采集别名指向不同识别记录，未恢复" }
            if (old == null) aliases[row.aliasKey] = row
        }
        val evidence = local.evidence.associateBy { it.eventId }.toMutableMap()
        incoming.evidence.forEach { row ->
            val old = evidence[row.eventId]
            require(old == null || old == row) { "采集事件身份内容冲突，未恢复" }
            if (old == null) evidence[row.eventId] = row
        }
        val deliveries = local.deliveries.associateBy { it.recognitionId }.toMutableMap()
        incoming.deliveries.forEach { row ->
            // Never replace a local live/acknowledged attempt with a restored remote task.
            if (row.recognitionId !in deliveries) deliveries[row.recognitionId] = row.copy(state = "RESTORED")
        }
        return CaptureSnapshot(aliases.values.toList(), evidence.values.toList(), deliveries.values.toList())
    }

    private fun Any?.obj(): Map<*, *> = this as? Map<*, *> ?: invalid("采集资料必须为对象")
    private fun Map<*, *>.rows(key: String): List<*> = this[key] as? List<*> ?: invalid("采集${key}必须为数组")
    private fun Map<*, *>.text(key: String, allowEmpty: Boolean = false): String =
        (this[key] as? String)?.takeIf { allowEmpty || it.isNotBlank() } ?: invalid("采集${key}必须为文字")
    private fun Map<*, *>.integer(key: String): Long = when (val value = this[key]) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalid("采集${key}必须为整数")
    }
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
