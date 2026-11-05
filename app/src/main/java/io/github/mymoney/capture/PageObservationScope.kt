package io.github.mymoney.capture

/** Ephemeral page lease. Never persisted/restored as an authorized observation. */
class PageObservationScope {
    private data class Lease(val id: String, val key: String)
    private var key: String? = null
    private val leases = ArrayDeque<Lease>()
    @Synchronized fun observe(id: String, detailKey: String) {
        if (key != detailKey) clear()
        key = detailKey
        leases.addLast(Lease(id, detailKey))
        while (leases.size > 8) leases.removeFirst()
    }
    @Synchronized fun allows(id: String?): Boolean = id != null && leases.any { it.id == id && it.key == key }
    @Synchronized fun clear() { key = null; leases.clear() }
}
