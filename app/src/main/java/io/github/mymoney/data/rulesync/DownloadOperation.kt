package io.github.mymoney.data.rulesync

/**
 * A cancellable, progress-reporting download operation (AA3). One instance backs one
 * user-initiated download; [cancel] may be called from any thread and must not block
 * on the download's IO or on a lock it holds. [progressListener] is invoked with the
 * monotonic received-byte count; the transport never touches Compose state directly.
 */
interface DownloadOperation {
    /** Opaque id (safe to log; carries no credentials). */
    val id: String

    /**
     * Requests cancellation. Idempotent; safe before the request starts. Returns
     * once the cancel signal is delivered — it must not wait for the request to
     * unwind (the Go layer closes the body out of band).
     */
    fun cancel()

    /**
     * The progress listener, if any. The callback receives cumulative bytes received
     * so far. Implementations must marshal to the caller's thread themselves (they do
     * not assume main). Assign before starting the download.
     */
    var progressListener: ((received: Long) -> Unit)?

    /** Delivers a progress sample to the listener (no-op if unset). */
    fun reportProgress(received: Long) {
        progressListener?.invoke(received)
    }
}

/** A [DownloadOperation] whose cancellation state can be polled by an in-process loop. */
interface CancellableOperation : DownloadOperation {
    val isCancelled: Boolean
}

/** Thrown when a download was cancelled by the user (distinct from an auth/network failure). */
class DownloadCancelledException(message: String = "已取消下载") : java.io.IOException(message)

/** Thrown by a transport for failures that are not an HTTP status (connection, timeout, policy). */
class FamilyTransportException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
