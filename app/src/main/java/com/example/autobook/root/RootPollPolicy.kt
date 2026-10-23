package com.example.autobook.root

/** Explicit outcome for every poll; failures and unchanged pages back off too. */
object RootPollPolicy {
    enum class Tick { ACTIVE, IDLE, ASLEEP }

    fun delayMs(tick: Tick, intervalMs: Long = 1_200L): Long = when (tick) {
        Tick.ACTIVE -> intervalMs.coerceAtLeast(1_200L)
        Tick.IDLE -> intervalMs.coerceAtLeast(3_000L)
        Tick.ASLEEP -> 30_000L
    }
}
