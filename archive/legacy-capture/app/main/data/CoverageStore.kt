package io.github.mymoney.data

import android.content.Context
import com.example.billkit.DebugBus
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Contains only day, package and a finite result vocabulary: never text/merchant/amount/activity. */
data class CoverageCounter(val day: String, val packageName: String, val outcome: String, val count: Long)

class CoverageAccumulator {
    private val counters = linkedMapOf<Triple<String, String, String>, Long>()
    @Synchronized fun record(packageName: String, outcome: String, atMillis: Long, now: Long = atMillis) {
        val day = date(atMillis); val today = date(now)
        prune(today)
        if (day < today.minusDays(6) || day > today) return
        val pkg = packageName.take(160).replace(Regex("[^A-Za-z0-9_.]"), "_")
        val result = if (outcome.startsWith("draft:")) "draft" else {
            val key = outcome.substringBefore(' ').split(':').take(2).joinToString(":")
            if (key in setOf("skip:not-a-bill-page", "skip:failed", "skip:cancelled", "skip:pending", "skip:no-status",
                    "skip:no-amount", "skip:zero-amount", "skip:throttled", "skip:duplicate")) key else "other"
        }
        val key = Triple(day.toString(), pkg, result)
        if (key !in counters && counters.size >= 512) counters.remove(counters.keys.first())
        counters[key] = ((counters[key] ?: 0) + 1).coerceAtMost(1_000_000_000L)
    }
    @Synchronized fun rows(now: Long = System.currentTimeMillis()): List<CoverageCounter> {
        prune(date(now))
        return counters.map { (k, v) -> CoverageCounter(k.first, k.second, k.third, v) }
            .sortedWith(compareBy<CoverageCounter> { !it.outcome.startsWith("skip:") }.thenByDescending { it.count })
    }
    @Synchronized fun clear() = counters.clear()
    @Synchronized fun encode(now: Long = System.currentTimeMillis()) = MiniJson.encode(rows(now).map {
        listOf(it.day, it.packageName, it.outcome, it.count)
    })
    @Synchronized fun restore(text: String, now: Long = System.currentTimeMillis()) {
        require(text.toByteArray().size <= 131_072)
        // Restoring only our shallow format is bounded before recursive decoding.
        require(text.count { it == '[' } <= 513 && text.count { it == '{' } == 0)
        val data = MiniJson.decode(text) as? List<*> ?: error("统计格式无效")
        require(data.size <= 512)
        val restored = linkedMapOf<Triple<String, String, String>, Long>()
        data.forEach { value ->
            val row = value as? List<*> ?: error("统计格式无效")
            require(row.size == 4)
            val day = LocalDate.parse(row[0] as String)
            val pkg = row[1] as String; val outcome = row[2] as String
            require(pkg.length <= 160 && pkg.matches(Regex("[A-Za-z0-9_.]*")))
            require(outcome == "draft" || outcome == "other" || outcome.matches(Regex("skip:[a-z-]{1,30}")))
            restored[Triple(day.toString(), pkg, outcome)] = (row[3] as Number).toLong().coerceIn(0, 1_000_000_000)
        }
        counters.clear(); counters.putAll(restored); prune(date(now))
    }
    private fun prune(today: LocalDate) { counters.keys.removeAll { LocalDate.parse(it.first) !in today.minusDays(6)..today } }
    private fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}

object CoverageStore {
    private val accumulator = CoverageAccumulator()
    private var file: File? = null
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "capture-coverage-writer").apply { isDaemon = true }
    }
    private var pendingWrite = false
    private fun scheduleWrite() {
        synchronized(this) {
            if (pendingWrite || file == null) return
            pendingWrite = true
        }
        // At most one queued write. Events update memory immediately and coalesce for one second.
        writer.schedule({
            val snapshot = synchronized(this) {
                pendingWrite = false
                file!! to accumulator.encode()
            }
            runCatching { atomicText(snapshot.first, snapshot.second) }
        }, 1, TimeUnit.SECONDS)
    }
    @Synchronized fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "capture_coverage.json")
        file!!.takeIf { it.exists() && it.length() <= 131_072 }?.let { runCatching { accumulator.restore(it.readText()) } }
        DebugBus.publicationListener = { state ->
            accumulator.record(state.packageName, state.outcome, state.atMillis)
            scheduleWrite()
        }
    }
    @Synchronized fun rows(): List<CoverageCounter> = accumulator.rows()
    fun clear() {
        accumulator.clear()
        // Same serial executor: an earlier snapshot always finishes before the cleared snapshot.
        scheduleWrite()
    }
}
