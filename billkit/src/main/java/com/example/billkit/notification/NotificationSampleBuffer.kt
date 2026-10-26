package com.example.billkit.notification

/** Volatile samples for explicit diagnostics, never a transaction queue or a trust registry. */
data class NotificationSampleEntry(val id: String, val observation: NotificationObservation,
    val outcome: String, val diagnosticOnly: Boolean)

class NotificationSampleBuffer(private val maxEntries: Int = 10, private val ttlMillis: Long = 300_000) {
    private val samples = LinkedHashMap<String, NotificationSampleEntry>()
    init { require(maxEntries > 0 && ttlMillis > 0) }

    @Synchronized
    fun add(observation: NotificationObservation, outcome: String, diagnosticOnly: Boolean = false,
        now: Long = observation.receivedAt): Boolean {
        prune(now, observation.configurationToken)
        if (observation.packageName !in NotificationParser.SUPPORTED_PACKAGES || (observation.isGroupSummary && !diagnosticOnly) ||
            observation.isMessagingStyle || observation.category == "msg" || observation.isOngoing || observation.truncated ||
            (observation.isReconnectBaseline && !diagnosticOnly) ||
            listOf(observation.title, observation.text, observation.bigText).all { it.isNullOrBlank() } ||
            now - observation.receivedAt > ttlMillis || observation.receivedAt - now > 5_000) return false
        val id = observation.observationKey
        if (observation.isGroupSummary && samples[id]?.observation?.isGroupSummary == false) return false
        samples.remove(id)
        samples[id] = NotificationSampleEntry(id, observation, outcome, diagnosticOnly)
        while (samples.size > maxEntries) {
            val oldest = samples.values.minByOrNull { it.observation.receivedAt } ?: break
            samples.remove(oldest.id)
        }
        return true
    }

    @Synchronized fun entries(now: Long, configurationToken: String): List<NotificationSampleEntry> {
        prune(now, configurationToken)
        return samples.values.sortedByDescending { it.observation.receivedAt }
    }
    @Synchronized fun clear() = samples.clear()
    private fun prune(now: Long, token: String) {
        samples.entries.removeAll { (_, entry) ->
            entry.observation.configurationToken != token || now - entry.observation.receivedAt > ttlMillis ||
                entry.observation.receivedAt - now > 5_000
        }
    }
}
