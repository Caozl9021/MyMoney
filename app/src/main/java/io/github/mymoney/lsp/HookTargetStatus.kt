package io.github.mymoney.lsp

import io.github.mymoney.data.MiniJson

enum class HookLoadState { NOT_CHECKED, CHECKING, READY, UNSUPPORTED, OLD_VERSION, NO_REPLY }

/** A registration acknowledgement and a received transaction are deliberately independent facts. */
data class HookTargetStatus(
    val state: HookLoadState = HookLoadState.NOT_CHECKED,
    val version: String? = null,
    val count: Int = 0,
    val lastReceiptAt: Long = 0L,
    val checkedAt: Long = 0L,
    val error: String? = null,
)

/** No Android classes or transaction data: this state machine can be tested on the JVM. */
class HookTargetRegistry(
    private val targets: Set<String>,
    receipts: Map<String, Long> = emptyMap(),
) {
    private data class Pending(val nonce: String, val sentAt: Long, val expectedVersion: String)
    private val pending = mutableMapOf<String, Pending>()
    private val states = targets.associateWith { app ->
        HookTargetStatus(lastReceiptAt = receipts[app]?.takeIf { it > 0L } ?: 0L)
    }.toMutableMap()

    fun snapshot(): Map<String, HookTargetStatus> = states.toMap()

    fun begin(app: String, nonce: String, expectedVersion: String, now: Long): Boolean {
        if (app !in targets || !validNonce(nonce) || !validVersion(expectedVersion) || now < 0L) return false
        pending[app] = Pending(nonce, now, expectedVersion)
        states[app] = HookTargetStatus(HookLoadState.CHECKING, lastReceiptAt = states.getValue(app).lastReceiptAt)
        return true
    }

    fun accept(app: String, nonce: String, version: String, count: Int, error: String?,
               callerPackages: Set<String>, now: Long): Boolean {
        if (!acceptsCaller(app, callerPackages, targets) || !validNonce(nonce) || !validVersion(version) ||
            count !in 0..MAX_REGISTERED || error !in ALLOWED_ERRORS) return false
        val request = pending[app] ?: return false
        if (nonce != request.nonce || now < request.sentAt || now - request.sentAt > TIMEOUT_MS) return false
        pending.remove(app) // A consumed acknowledgement cannot be replayed.
        val kind = when {
            version == UNKNOWN_VERSION || version != request.expectedVersion -> HookLoadState.OLD_VERSION
            count == 0 || error != null -> HookLoadState.UNSUPPORTED
            else -> HookLoadState.READY
        }
        states[app] = HookTargetStatus(kind, version, count, states.getValue(app).lastReceiptAt, now, error)
        return true
    }

    fun expire(app: String, nonce: String, now: Long): Boolean {
        val request = pending[app] ?: return false
        if (request.nonce != nonce || now < request.sentAt || now - request.sentAt <= TIMEOUT_MS) return false
        pending.remove(app)
        states[app] = HookTargetStatus(HookLoadState.NO_REPLY, lastReceiptAt = states.getValue(app).lastReceiptAt,
            checkedAt = now)
        return true
    }

    fun received(app: String, at: Long): Boolean {
        val previous = states[app] ?: return false
        if (at <= previous.lastReceiptAt || at <= 0L) return false
        states[app] = previous.copy(lastReceiptAt = at)
        return true
    }

    fun receiptTimes(): Map<String, Long> = states.mapValues { it.value.lastReceiptAt }.filterValues { it > 0L }

    companion object {
        const val TIMEOUT_MS = 4_000L
        const val MAX_REGISTERED = 32
        const val UNKNOWN_VERSION = "unknown"
        private val ALLOWED_ERRORS = setOf(null, "NO_METHOD", "VERSION_UNKNOWN", "INSTALL_FAILED")
        private val VERSION = Regex("[0-9]{1,9}(\\.[0-9]{1,9}){1,3}/[0-9]{1,18}")

        fun acceptsCaller(app: String?, callerPackages: Set<String>, targets: Set<String>): Boolean =
            app != null && app in targets && app in callerPackages

        private fun validNonce(nonce: String): Boolean = nonce.length in 16..100 &&
            nonce.all { it.isLetterOrDigit() || it == '-' }

        private fun validVersion(version: String): Boolean = version == UNKNOWN_VERSION || VERSION.matches(version)
    }
}

/** Only package names and timestamps are persisted; a registration is rechecked after host restart. */
object HookReceiptCodec {
    fun encode(receipts: Map<String, Long>): String = MiniJson.encode(receipts.filterValues { it > 0L })

    fun decode(raw: String?, targets: Set<String>): Map<String, Long> {
        if (raw == null || raw.length > 4_096) return emptyMap()
        val parsed = runCatching { MiniJson.decode(raw) as? Map<*, *> }.getOrNull() ?: return emptyMap()
        return parsed.mapNotNull { (key, value) ->
            val app = key as? String ?: return@mapNotNull null
            val at = when (value) { is Long -> value; is Int -> value.toLong(); else -> return@mapNotNull null }
            if (app in targets && at > 0L) app to at else null
        }.toMap()
    }
}
