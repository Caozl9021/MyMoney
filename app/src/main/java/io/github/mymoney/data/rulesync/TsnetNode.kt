package io.github.mymoney.data.rulesync

/**
 * Process-wide single embedded node with task reference counting.
 *
 * The tailnet node identity is one per install: two simultaneous sync/backup tasks must
 * share a single `tsnet` server instance (gomobile allows one running client per process),
 * and the node must not be torn down while a task still holds it. This class owns that
 * lifecycle:
 *   - [acquire] starts the node on the first holder and increments the reference count.
 *   - [release] decrements; reaching zero stops the node and re-seals the state store so no
 *     plaintext node state lingers on disk.
 *   - A later [acquire] unseals the same store, so the node is reused rather than re-created
 *     or re-authenticated.
 *
 * Failures are surfaced to the caller and never leave the counter corrupted. The driver and
 * sealer are injected so the lifecycle is testable without the native library.
 */
class TsnetNode internal constructor(
    private val driver: Driver,
    private val sealer: Sealer,
) {
    /** Starts/stops the native node. Implementations must be idempotent. */
    interface Driver {
        fun start(authKey: String?, ctrlUrl: String)
        fun stop()
        fun isRunning(): Boolean
        fun status(): String
    }

    /** Encrypts the on-disk node state at rest. */
    interface Sealer {
        fun unseal(): Boolean
        fun seal(): Boolean
    }

    /** A held reference; [close] is idempotent so double-close cannot corrupt the count. */
    inner class Handle internal constructor() : AutoCloseable {
        private var released = false
        override fun close() {
            if (released) return
            release()
            released = true
        }
    }

    private var holders = 0

    /** Number of tasks currently holding the node (test/diagnostics only). */
    @Synchronized
    fun holders(): Int = holders

    @Synchronized
    fun acquire(authKey: String?, ctrlUrl: String = ""): Handle {
        if (holders == 0) {
            sealer.unseal()
            driver.start(authKey, ctrlUrl)
        } else if (!driver.isRunning()) {
            // A previous holder stopped the node but the count bookkeeping kept it alive:
            // restart rather than hand out a dead node.
            driver.start(authKey, ctrlUrl)
        }
        holders++
        return Handle()
    }

    @Synchronized
    private fun release() {
        if (holders == 0) return
        if (holders > 1) {
            holders--
            return
        }
        driver.stop()
        check(sealer.seal()) { "节点身份封存失败，原身份文件已保留，请重试" }
        holders = 0
    }

    @Synchronized
    fun status(): String = if (holders == 0) "down" else driver.status()

    /**
     * Restarts a stopped node **without** touching the reference count. Used when the
     * tunnel died underneath a live holder (network change, OS reclaim) and the next
     * request must not run on a dead node.
     */
    @Synchronized
    fun restart(authKey: String?, ctrlUrl: String = "") {
        if (holders == 0) return
        if (driver.isRunning()) return
        driver.start(authKey, ctrlUrl)
    }

    /** Force-stops the node and drops the store (sign-out / wipe). Counters are reset. */
    @Synchronized
    fun shutdownAndWipe() {
        holders = 0
        runCatching { driver.stop() }
        runCatching { sealer.seal() }
    }

    companion object {
        @Volatile private var instance: TsnetNode? = null

        /**
         * The single node for this process. The first call supplies the driver/sealer;
         * later calls return that same instance so concurrent tasks cannot create a
         * second native client (the Go side allows one running client per process).
         * Wiring (context, state dir, peer) belongs to the caller (U4 integration).
         */
        @Synchronized
        fun of(create: () -> TsnetNode): TsnetNode = instance ?: create().also { instance = it }

        /** Publish a shared node only after its first successful native start. */
        @Synchronized
        fun acquireShared(authKey: String?, ctrlUrl: String = "", create: () -> TsnetNode): Pair<TsnetNode, Handle> {
            val node = instance ?: create()
            val handle = node.acquire(authKey, ctrlUrl)
            instance = node
            return node to handle
        }

        /** Drops the cached instance without touching the node itself (config change / disconnect). */
        @Synchronized
        fun forget() { instance = null }

        /** Test/reset hook: same as [forget]. */
        @Synchronized
        fun resetForTest() { instance = null }
    }

}
