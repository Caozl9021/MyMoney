package io.github.mymoney

import io.github.mymoney.data.ledgersync.RealtimeSyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 实时触发器的时间策略：后台 8 秒防抖、前台 15 秒闲置；前台防抖更长，合并更多连续操作。 */
class RealtimeSyncPolicyTest {

    @Test
    fun backgroundCaptureDebouncesEightSeconds() {
        assertEquals(8_000L, RealtimeSyncTrigger.BACKGROUND_DEBOUNCE_MS)
    }

    @Test
    fun foregroundEditingWaitsFifteenSeconds() {
        assertEquals(15_000L, RealtimeSyncTrigger.FOREGROUND_IDLE_MS)
        assertTrue(RealtimeSyncTrigger.FOREGROUND_IDLE_MS > RealtimeSyncTrigger.BACKGROUND_DEBOUNCE_MS)
    }

    @Test
    fun workNameIsStableForUniqueWork() {
        assertEquals("mymoney_realtime_sync", RealtimeSyncTrigger.WORK_NAME)
        assertFalse(RealtimeSyncTrigger.WORK_NAME.isBlank())
    }
}
