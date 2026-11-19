package com.example.billkit.root

import org.junit.Assert.*
import org.junit.Test

class RootNotificationAdmissionTest {
    private val now = 1_793_300_001_000L
    private fun complete() = NotificationDumpParser.Dumped(
        packageName = "com.tencent.mm", title = "微信支付", text = "已支付¥19.99",
        postTime = now - 1000, slot = "0|com.tencent.mm|1|null|10000", channelId = "synthetic-payment",
        category = null, template = null, flags = 0L, categoryKnown = true, templateKnown = true,
        extrasComplete = true,
    )

    @Test fun `实时首帧残缺不占槽位补齐后的同槽位正常接收`() {
        val dedup = NotificationDumpDedup()
        val full = complete()
        val partial = full.copy(extrasComplete = false)
        assertEquals(RootNotificationAdmission.Result.Rejected("missing-envelope-metadata"),
            RootNotificationAdmission.admitLive(partial, dedup, now))
        assertEquals(0, dedup.size())
        assertEquals(RootNotificationAdmission.Result.Accepted,
            RootNotificationAdmission.admitLive(full, dedup, now + 5000))
        assertEquals(1, dedup.size())
        assertEquals(RootNotificationAdmission.Result.Duplicate,
            RootNotificationAdmission.admitLive(full.copy(text = "正文更新"), dedup, now + 10000))
    }

    @Test fun `明确禁止信封同样不提前占用槽位`() {
        val dedup = NotificationDumpDedup()
        val full = complete()
        for ((rejected, reason) in listOf(full.copy(flags = 0x200L) to "group-summary",
            full.copy(category = "msg") to "chat-notification", full.copy(truncated = true) to "truncated-content")) {
            assertEquals(RootNotificationAdmission.Result.Rejected(reason),
                RootNotificationAdmission.admitLive(rejected, dedup, now))
        }
        assertEquals(0, dedup.size())
        assertEquals(RootNotificationAdmission.Result.Accepted,
            RootNotificationAdmission.admitLive(full, dedup, now + 5000))
    }

    @Test fun `基线残缺通知仍登记避免补齐后把旧通知当新交易`() {
        val dedup = NotificationDumpDedup()
        val full = complete()
        assertTrue(dedup.accept(full.copy(extrasComplete = false), now))
        assertEquals(RootNotificationAdmission.Result.Duplicate,
            RootNotificationAdmission.admitLive(full, dedup, now + 5000))
    }
}
