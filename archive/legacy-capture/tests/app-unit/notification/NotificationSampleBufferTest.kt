package io.github.mymoney.notification

import com.example.billkit.notification.NotificationObservation
import com.example.billkit.notification.NotificationSampleBuffer
import org.junit.Assert.*
import org.junit.Test

/** Regression for the ephemeral, explicitly previewed sample cache; no transaction parser is invoked. */
class NotificationSampleBufferTest {
    private val now = 1_000_000L
    private val token = "capture-generation-a"

    private fun child(
        pkg: String = "com.tencent.mm",
        key: String = "child-1",
        receivedAt: Long = now,
        postTime: Long = now,
    ) = NotificationObservation(
        eventId = "event-$key-$receivedAt",
        packageName = pkg,
        deviceScope = "device-a",
        notificationKey = key,
        notificationId = 1,
        notificationTag = null,
        userScope = "personal-0",
        postTime = postTime,
        receivedAt = receivedAt,
        title = "支付消息",
        text = "付款成功 ¥19.99",
        bigText = null,
        channelId = "payment-channel",
        category = null,
        template = "android.app.Notification\$BigTextStyle",
        configurationToken = token,
    )

    @Test fun childThenSameInstanceSummaryKeepsUsableChild() {
        val buffer = NotificationSampleBuffer()
        val original = child()
        assertTrue(buffer.add(original, "unverified-template"))
        assertFalse(buffer.add(original.copy(isGroupSummary = true, text = "共2条消息", receivedAt = now + 1), "group-summary"))
        val saved = buffer.entries(now + 1, token).single()
        assertEquals(original.observationKey, saved.id)
        assertEquals(original, saved.observation)
        assertFalse(saved.diagnosticOnly)
    }

    @Test fun summaryThenChildAcceptsChildRatherThanBlockingTheInstance() {
        val buffer = NotificationSampleBuffer()
        val original = child()
        assertFalse(buffer.add(original.copy(isGroupSummary = true), "group-summary"))
        assertTrue(buffer.add(original.copy(receivedAt = now + 1), "unverified-template"))
        assertEquals(now + 1, buffer.entries(now + 1, token).single().observation.receivedAt)
    }

    @Test fun ignoredRedPacketSuffixIsStillAvailableForExplicitReview() {
        val buffer = NotificationSampleBuffer()
        val original = child().copy(text = "付款成功 ¥19.99，领取红包")
        assertTrue(buffer.add(original, "marketing-or-red-packet"))
        val saved = buffer.entries(now, token).single()
        assertEquals("marketing-or-red-packet", saved.outcome)
        assertEquals(original.text, saved.observation.text)
    }

    @Test fun nonUsableCallbacksNeverEraseExistingUsableSample() {
        val buffer = NotificationSampleBuffer()
        val original = child()
        buffer.add(original, "unverified-template")
        val rejected = listOf(
            original.copy(isMessagingStyle = true),
            original.copy(category = "msg"),
            original.copy(title = " ", text = "\n", bigText = null),
            original.copy(truncated = true),
            original.copy(isOngoing = true),
            original.copy(isGroupSummary = true),
            original.copy(isReconnectBaseline = true),
            original.copy(packageName = "com.example.unrelated"),
        )
        rejected.forEach { observation ->
            assertFalse("Unusable callback must not replace the cache: $observation", buffer.add(observation, "ignored"))
            assertEquals(original, buffer.entries(now, token).single().observation)
        }
    }

    @Test fun nonEmptyBigTextAloneIsEnoughToCaptureAnOtherwiseUsableSample() {
        val buffer = NotificationSampleBuffer()
        val original = child().copy(title = null, text = null, bigText = "交易成功 ¥19.99")
        assertTrue(buffer.add(original, "unverified-template"))
        assertEquals(original, buffer.entries(now, token).single().observation)
    }

    @Test fun explicitManualBaselineIsDiagnosticOnly() {
        val buffer = NotificationSampleBuffer()
        val baseline = child().copy(isReconnectBaseline = true)
        assertFalse(buffer.add(baseline, "reconnect-baseline"))
        assertTrue(buffer.add(baseline, "manual-baseline", diagnosticOnly = true))
        val saved = buffer.entries(now, token).single()
        assertTrue(saved.diagnosticOnly)
        assertEquals("manual-baseline", saved.outcome)
        assertTrue(saved.observation.isReconnectBaseline)
    }

    @Test fun manualDiagnosticsAllowSeparateSummaryButDoNotBypassChatOrUsabilityGuards() {
        val buffer = NotificationSampleBuffer()
        val baseline = child().copy(isReconnectBaseline = true)
        val rejected = listOf(
            baseline.copy(isMessagingStyle = true),
            baseline.copy(category = "msg"),
            baseline.copy(isOngoing = true),
            baseline.copy(title = " ", text = "\n", bigText = null),
            baseline.copy(truncated = true),
        )
        rejected.forEach { assertFalse(buffer.add(it, "manual", diagnosticOnly = true)) }
        assertTrue(buffer.entries(now, token).isEmpty())

        val original = child()
        assertTrue(buffer.add(original, "unverified-template"))
        val summary = child(key = "manual-summary", receivedAt = now + 1)
            .copy(isReconnectBaseline = true, isGroupSummary = true, text = "共2条支付消息")
        assertTrue(buffer.add(summary, "manual-summary", diagnosticOnly = true))
        val saved = buffer.entries(now + 1, token)
        assertEquals(2, saved.size)
        val diagnostic = saved.single { it.id == summary.observationKey }
        assertTrue(diagnostic.diagnosticOnly)
        assertTrue(diagnostic.observation.isGroupSummary)
        assertTrue(diagnostic.observation.isReconnectBaseline)
        assertEquals(summary, diagnostic.observation)
        assertEquals(original, saved.single { it.id == original.observationKey }.observation)
    }

    @Test fun manualSummaryForExistingChildInstanceCannotOverwriteChild() {
        val buffer = NotificationSampleBuffer()
        val original = child()
        assertTrue(buffer.add(original, "unverified-template"))
        val summary = original.copy(isGroupSummary = true, isReconnectBaseline = true,
            receivedAt = now + 1, text = "汇总更新")
        assertFalse(buffer.add(summary, "manual-summary", diagnosticOnly = true))
        val saved = buffer.entries(now + 1, token).single()
        assertEquals(original, saved.observation)
        assertFalse(saved.diagnosticOnly)
    }

    @Test fun ttlBoundaryIsInclusiveAndExpiredEntriesAreRemoved() {
        val buffer = NotificationSampleBuffer(ttlMillis = 300_000)
        buffer.add(child(), "unverified-template")
        assertEquals(1, buffer.entries(now + 300_000, token).size)
        assertTrue(buffer.entries(now + 300_001, token).isEmpty())
        // Expiration is destructive, not a temporary visibility filter.
        assertTrue(buffer.entries(now, token).isEmpty())
    }

    @Test fun configurationGenerationMismatchInvalidatesCachedBody() {
        val buffer = NotificationSampleBuffer()
        buffer.add(child(), "unverified-template")
        assertTrue(buffer.entries(now, "capture-generation-b").isEmpty())
        assertTrue(buffer.entries(now, token).isEmpty())
    }

    @Test fun clockSkewBeyondFiveSecondsDoesNotExposeFutureSamples() {
        val buffer = NotificationSampleBuffer()
        val tolerated = child(key = "within-skew", receivedAt = now + 5_000)
        assertTrue(buffer.add(tolerated, "unverified-template", now = now))
        assertEquals(tolerated, buffer.entries(now, token).single().observation)
        buffer.clear()
        buffer.add(child(key = "future", receivedAt = now + 5_001), "unverified-template", now = now)
        assertTrue(buffer.entries(now, token).isEmpty())
    }

    @Test fun capacityKeepsTenMostRecentSamplesInDescendingReceiveOrder() {
        val buffer = NotificationSampleBuffer(maxEntries = 10)
        for (index in 0..11) {
            assertTrue(buffer.add(child(key = "child-$index", receivedAt = now + index), "unverified-template", now = now + index))
        }
        val entries = buffer.entries(now + 11, token)
        assertEquals(10, entries.size)
        assertEquals((11 downTo 2).map { now + it }, entries.map { it.observation.receivedAt })
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
    }

    @Test fun repeatedInstanceUpdatesReplaceWithoutGrowingTheBuffer() {
        val buffer = NotificationSampleBuffer()
        val original = child()
        val update = original.copy(eventId = "next-callback", text = "付款成功 ¥20.00", receivedAt = now + 1)
        assertEquals(original.observationKey, update.observationKey)
        buffer.add(original, "initial")
        buffer.add(update, "updated")
        val saved = buffer.entries(now + 1, token).single()
        assertEquals(update.observationKey, saved.id)
        assertEquals(update, saved.observation)
        assertEquals("updated", saved.outcome)
    }

    @Test fun reusedNotificationKeyWithNewPostTimeCreatesANewInstance() {
        val buffer = NotificationSampleBuffer()
        val original = child()
        val next = original.copy(postTime = now + 1, receivedAt = now + 1, eventId = "new-payment")
        assertNotEquals(original.observationKey, next.observationKey)
        buffer.add(original, "first")
        buffer.add(next, "second")
        assertEquals(2, buffer.entries(now + 1, token).size)
    }

    @Test fun alipaySummaryDoesNotRemoveWechatChildAndBothApplicationsCanHaveChildren() {
        val buffer = NotificationSampleBuffer()
        val wechat = child()
        val alipay = child(pkg = "com.eg.android.AlipayGphone", key = "alipay-child", receivedAt = now + 1)
        assertTrue(buffer.add(wechat, "unverified-template"))
        assertFalse(buffer.add(alipay.copy(isGroupSummary = true), "summary"))
        assertEquals(wechat, buffer.entries(now + 1, token).single().observation)
        assertTrue(buffer.add(alipay, "unverified-template"))
        assertEquals(listOf(alipay, wechat), buffer.entries(now + 1, token).map { it.observation })
    }

    @Test fun clearRemovesAllRetainedSamples() {
        val buffer = NotificationSampleBuffer()
        buffer.add(child(), "unverified-template")
        buffer.clear()
        assertTrue(buffer.entries(now, token).isEmpty())
    }
}
