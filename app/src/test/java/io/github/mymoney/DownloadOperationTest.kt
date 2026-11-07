package io.github.mymoney

import io.github.mymoney.data.rulesync.DownloadCancelledException
import io.github.mymoney.data.rulesync.DirectDownloadOperation
import io.github.mymoney.data.rulesync.EmbeddedDownloadOperation
import io.github.mymoney.data.rulesync.throwIfCancelled
import org.junit.Assert.*
import org.junit.Test

/** AA3: cancellable, progress-reporting download operations (pure JVM logic, no Android). */
class DownloadOperationTest {
    @Test fun progressListenerReceivesCumulativeBytes() {
        val op = DirectDownloadOperation()
        val seen = ArrayList<Long>()
        op.progressListener = { seen.add(it) }
        op.reportProgress(10); op.reportProgress(25); op.reportProgress(25)
        assertEquals(listOf(10L, 25L, 25L), seen)
    }

    @Test fun reportProgressWithoutListenerIsSafe() {
        // An unset listener must not throw (report happens on a background thread).
        DirectDownloadOperation().reportProgress(1)
        EmbeddedDownloadOperation().reportProgress(1)
    }

    @Test fun cancelIsIdempotentAndFlipsFlag() {
        val op = DirectDownloadOperation()
        assertFalse(op.isCancelled)
        op.cancel(); assertTrue(op.isCancelled)
        op.cancel(); assertTrue(op.isCancelled) // second cancel is a no-op
    }

    @Test fun distinctIdsByDefault() {
        assertNotEquals(DirectDownloadOperation().id, DirectDownloadOperation().id)
        assertEquals("fixed-id", DirectDownloadOperation(id = "fixed-id").id)
    }

    @Test fun throwIfCancelledMapsCancelledErrorPrefix() {
        val op = DirectDownloadOperation()
        try { throwIfCancelled(op, RuntimeException("cancelled: context canceled")); fail("应抛出取消异常") }
        catch (e: DownloadCancelledException) { /* expected */ }
    }

    @Test fun throwIfCancelledMapsCancelledFlag() {
        val op = DirectDownloadOperation().apply { cancel() }
        try { throwIfCancelled(op, RuntimeException("network timeout")); fail("应抛出取消异常") }
        catch (e: DownloadCancelledException) { /* expected: op was cancelled */ }
    }

    @Test fun throwIfCancelledPassesThroughNormalError() {
        val op = DirectDownloadOperation()
        // A genuine failure that is neither a "cancelled:" prefix nor a cancelled op is left to the caller.
        throwIfCancelled(op, RuntimeException("boom"))
    }
}
