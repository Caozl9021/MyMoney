package io.github.mymoney.lsp

import org.junit.Assert.*
import org.junit.Test

class HookTargetRegistryTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val targets = setOf(wechat, alipay)
    private val current = "0.23.1/81"
    private val nonce = "12345678-1234-1234-1234-123456789abc"
    private val otherNonce = "87654321-4321-4321-4321-cba987654321"

    private fun started(receipts: Map<String, Long> = emptyMap()) = HookTargetRegistry(targets, receipts).apply {
        assertTrue(begin(wechat, nonce, current, 100L))
    }

    private fun accept(registry: HookTargetRegistry, app: String = wechat, receivedNonce: String = nonce,
                       version: String = current, count: Int = 2, error: String? = null,
                       callers: Set<String> = setOf(wechat), now: Long = 200L): Boolean =
        registry.accept(app, receivedNonce, version, count, error, callers, now)

    @Test fun registrationAcknowledgementIsReadyWithoutInventingTransaction() {
        val registry = started()
        assertTrue(accept(registry))
        val status = registry.snapshot().getValue(wechat)
        assertEquals(HookLoadState.READY, status.state)
        assertEquals(current, status.version)
        assertEquals(2, status.count)
        assertEquals(0L, status.lastReceiptAt)
        assertTrue(registry.receiptTimes().isEmpty())
        assertEquals(HookLoadState.NOT_CHECKED, registry.snapshot().getValue(alipay).state)
    }

    @Test fun callerIdentityBindsTheDeclaredAppRatherThanAnyHookedPackage() {
        assertFalse(HookTargetRegistry.acceptsCaller(alipay, setOf(wechat), targets))
        assertFalse(HookTargetRegistry.acceptsCaller(wechat, setOf("com.attacker.app"), targets))
        assertFalse(HookTargetRegistry.acceptsCaller("com.attacker.app", setOf("com.attacker.app"), targets))
        assertFalse(HookTargetRegistry.acceptsCaller(null, targets, targets))
        val registry = started()
        assertFalse(accept(registry, callers = setOf(alipay)))
        assertFalse(accept(registry, callers = emptySet()))
        assertTrue(accept(registry)) // Invalid callers cannot consume another process's nonce.
    }

    @Test fun independentTargetNoncesCannotBeSwapped() {
        val registry = started()
        assertTrue(registry.begin(alipay, otherNonce, current, 100L))
        assertFalse(accept(registry, app = alipay, callers = setOf(alipay)))
        assertTrue(accept(registry))
        assertEquals(HookLoadState.CHECKING, registry.snapshot().getValue(alipay).state)
        assertTrue(accept(registry, app = alipay, receivedNonce = otherNonce, callers = setOf(alipay), count = 1))
        assertEquals(1, registry.snapshot().getValue(alipay).count)
    }

    @Test fun wrongExpiredFutureAndReplayedAcknowledgementsAreRejected() {
        assertFalse(accept(started(), receivedNonce = otherNonce))
        assertFalse(accept(started(), now = 99L))
        assertTrue(accept(started(), now = 4100L))
        assertFalse(accept(started(), now = 4101L))
        val registry = started()
        assertTrue(accept(registry))
        assertFalse(accept(registry))
        assertFalse(registry.expire(wechat, nonce, 5000L))
        assertEquals(HookLoadState.READY, registry.snapshot().getValue(wechat).state)
    }

    @Test fun supersededProbeTimeoutCannotOverwriteNewRegistration() {
        val registry = started()
        assertTrue(registry.begin(wechat, otherNonce, current, 500L))
        assertFalse(accept(registry))
        assertFalse(registry.expire(wechat, nonce, 5000L))
        assertTrue(accept(registry, receivedNonce = otherNonce, now = 600L))
        assertFalse(registry.expire(wechat, otherNonce, 5001L))
        assertEquals(HookLoadState.READY, registry.snapshot().getValue(wechat).state)
    }

    @Test fun timeoutIsUnknownRegistrationRatherThanDisabledModule() {
        val registry = started(mapOf(wechat to 50L))
        assertFalse(registry.expire(wechat, nonce, 4100L))
        assertTrue(registry.expire(wechat, nonce, 4101L))
        val status = registry.snapshot().getValue(wechat)
        assertEquals(HookLoadState.NO_REPLY, status.state)
        assertNull(status.version)
        assertEquals(50L, status.lastReceiptAt)
        assertEquals(HookLoadState.NOT_CHECKED, registry.snapshot().getValue(alipay).state)
    }

    @Test fun oldOrUnknownVersionNeverBecomesCurrentReady() {
        val old = started()
        assertTrue(accept(old, version = "0.23.0/80"))
        assertEquals(HookLoadState.OLD_VERSION, old.snapshot().getValue(wechat).state)
        val unknown = started()
        assertTrue(accept(unknown, version = "unknown", error = "VERSION_UNKNOWN"))
        assertEquals(HookLoadState.OLD_VERSION, unknown.snapshot().getValue(wechat).state)
        val hostUnknown = HookTargetRegistry(targets)
        assertTrue(hostUnknown.begin(wechat, nonce, "unknown", 100L))
        assertTrue(accept(hostUnknown, version = "unknown", error = "VERSION_UNKNOWN"))
        assertNotEquals(HookLoadState.READY, hostUnknown.snapshot().getValue(wechat).state)
    }

    @Test fun failedRegistrationHasItsOwnStateDespiteAuthenticatedModuleLoad() {
        val noMethods = started()
        assertTrue(accept(noMethods, count = 0, error = "NO_METHOD"))
        assertEquals(HookLoadState.UNSUPPORTED, noMethods.snapshot().getValue(wechat).state)
        val failed = started()
        assertTrue(accept(failed, count = 0, error = "INSTALL_FAILED"))
        assertEquals(HookLoadState.UNSUPPORTED, failed.snapshot().getValue(wechat).state)
        assertFalse(accept(started(), count = -1))
        assertFalse(accept(started(), count = 33))
        assertFalse(accept(started(), error = "raw-message-or-unrecognised-code"))
        assertFalse(accept(started(), version = "current"))
    }

    @Test fun receiptAndRegistrationEvolveIndependentlyAndSurviveHostReconstruction() {
        val registry = started()
        assertTrue(registry.received(wechat, 1_700_000_000_000L))
        assertEquals(HookLoadState.CHECKING, registry.snapshot().getValue(wechat).state)
        assertTrue(accept(registry))
        val timestamp = registry.snapshot().getValue(wechat).lastReceiptAt
        assertTrue(registry.begin(wechat, otherNonce, current, 1_000_000L))
        assertTrue(registry.expire(wechat, otherNonce, 1_004_001L))
        assertEquals(timestamp, registry.snapshot().getValue(wechat).lastReceiptAt)
        val persisted = HookReceiptCodec.encode(registry.receiptTimes())
        val recreated = HookTargetRegistry(targets, HookReceiptCodec.decode(persisted, targets))
        assertEquals(timestamp, recreated.snapshot().getValue(wechat).lastReceiptAt)
        assertEquals(HookLoadState.NOT_CHECKED, recreated.snapshot().getValue(wechat).state)
        assertEquals(0L, recreated.snapshot().getValue(alipay).lastReceiptAt)
        assertFalse(recreated.received(wechat, timestamp - 1L))
        assertFalse(recreated.received("com.attacker.app", timestamp))
    }

    @Test fun receiptCodecDropsMalformedForeignAndNonIntegralTimes() {
        assertTrue(HookReceiptCodec.decode("not-json", targets).isEmpty())
        assertTrue(HookReceiptCodec.decode(null, targets).isEmpty())
        val raw = """{"com.tencent.mm":42,"com.eg.android.AlipayGphone":1.5,"foreign":99}"""
        assertEquals(mapOf(wechat to 42L), HookReceiptCodec.decode(raw, targets))
        assertTrue(HookReceiptCodec.decode("""{"com.tencent.mm":-1}""", targets).isEmpty())
        assertTrue(HookReceiptCodec.decode(" ".repeat(4097), targets).isEmpty())
    }
}
