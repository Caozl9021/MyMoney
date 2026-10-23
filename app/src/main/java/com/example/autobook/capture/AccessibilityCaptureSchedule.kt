package com.example.autobook.capture

/** Main-thread scheduling policy. All times use monotonic uptime milliseconds. */
class AccessibilityCaptureSchedule {
    enum class Event { WINDOW, CONTENT }
    var packageName: String? = null
        private set
    var deadline: Long? = null
        private set
    var retrying: Boolean = false
        private set
    private var firstContent: Long? = null
    private var windowDeadline: Long? = null

    fun offer(pkg: String, event: Event, now: Long): Long {
        if (packageName != pkg) { clear(); packageName = pkg }
        // Updates arriving during the single root retry are included in that capture;
        // they cannot postpone it or replenish its retry budget.
        if (retrying) return deadline!!
        when (event) {
            Event.WINDOW -> windowDeadline = minOf(windowDeadline ?: Long.MAX_VALUE, now + 120L)
            Event.CONTENT -> if (firstContent == null) firstContent = now
        }
        val contentDeadline = firstContent?.let { minOf(now + 600L, it + 1_000L) } ?: Long.MAX_VALUE
        deadline = minOf(windowDeadline ?: Long.MAX_VALUE, contentDeadline)
        return deadline!!
    }

    /** The current attempt may receive exactly one retry, even under event storms. */
    fun retry(now: Long): Long? {
        if (packageName == null || retrying) return null
        retrying = true
        deadline = now + 400L
        return deadline
    }

    /** Completion, disable, interruption and package changes all discard the old batch. */
    fun clear() {
        packageName = null
        deadline = null
        firstContent = null
        windowDeadline = null
        retrying = false
    }
}
