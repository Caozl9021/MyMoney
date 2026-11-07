package io.github.mymoney.data.rulesync

import famisync.Famisync
import famisync.ProgressCallback
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AA3 download operations. Each instance backs exactly one user-initiated download
 * and carries a stable random [id] (safe to log). [cancel] is idempotent and never
 * blocks on the download's IO or on a lock the download holds.
 *
 * Two implementations:
 *   - [DirectDownloadOperation]: used by the direct HttpURLConnection transport. Cancel
 *     is carried out by disconnecting the live connection from another thread, which
 *     makes the blocked read() throw immediately (not just at the next chunk boundary).
 *   - [EmbeddedDownloadOperation]: used by the embedded tsnet transport. Cancel forwards
 *     to Go's per-operation cancel registry (Famisync.cancelOp), which closes the HTTP
 *     body in Go and exits the copy loop in flight.
 */
abstract class AbstractDownloadOperation(
    override val id: String = UUID.randomUUID().toString(),
) : CancellableOperation {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    override var progressListener: ((received: Long) -> Unit)? = null

    override val isCancelled: Boolean get() = cancelled.get()

    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) onCancel()
    }

    /** Marks cancelled without side effects (used when a phase change retires the op). */
    fun markCancelled() {
        cancelled.set(true)
    }

    /** Hook invoked exactly once when cancel() first flips the flag. */
    protected open fun onCancel() {}
}

/** Direct-HTTP download operation: cancel disconnects the live connection. */
class DirectDownloadOperation(id: String = UUID.randomUUID().toString()) :
    AbstractDownloadOperation(id) {

    private val connection = AtomicReference<HttpURLConnection?>(null)

    /** The transport registers its live connection so cancel can disconnect it. */
    fun attach(conn: HttpURLConnection) {
        connection.set(conn)
        // If cancel raced ahead of attach, disconnect now.
        if (isCancelled) runCatching { conn.disconnect() }
    }

    fun detach(conn: HttpURLConnection) {
        connection.compareAndSet(conn, null)
    }

    override fun onCancel() {
        runCatching { connection.get()?.disconnect() }
    }
}

/**
 * Embedded-tsnet download operation. Cancel forwards to the Go operation registry so
 * the in-flight native copy exits; the JNI call itself is NOT interrupted by a Java
 * Future cancel (that cannot cancel a native call), so we rely on Go-side context
 * cancellation triggered via [Famisync.cancelOp].
 */
class EmbeddedDownloadOperation(id: String = UUID.randomUUID().toString()) :
    AbstractDownloadOperation(id) {

    /** Installs the opaque handle in Go's registry (call before starting the download). */
    fun register() {
        Famisync.newOpHandle(id)
        // Shrink the window where cancel-before-start could be lost: if already
        // cancelled, signal Go now so the pre-start register aborts.
        if (isCancelled) runCatching { Famisync.cancelOp(id) }
    }

    override fun onCancel() {
        runCatching { Famisync.cancelOp(id) }
    }

    /** A ProgressCallback that forwards cumulative bytes to the Kotlin listener. */
    fun progressCallback(onSample: (Long) -> Unit): ProgressCallback =
        object : ProgressCallback {
            override fun onProgress(received: Long) {
                onSample(received)
            }
        }
}

/** Maps a Go "cancelled:"-prefixed error (or a cancelled op after the call) to a
 *  [DownloadCancelledException]; otherwise rethrows as a transport failure. */
internal fun throwIfCancelled(operation: DownloadOperation, err: Throwable?) {
    val msg = err?.message.orEmpty()
    if (msg.startsWith("cancelled:", ignoreCase = true) ||
        (operation as? CancellableOperation)?.isCancelled == true) {
        throw DownloadCancelledException()
    }
}
