package io.github.mymoney.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 通道心跳的判定语义。
 *
 * 为什么需要它：只按「有没有报错」判通道健康时，静默故障（进程活着、状态 ready、
 * 但再也不产出）永远看不见。这组用例钉住的就是「多久没成功才算异常」这条线。
 */
class ChannelHeartbeatTest {

    @Before
    fun reset() = ChannelHeartbeat.reset()

    @Test
    fun `从未成功过不算 stale`() {
        assertFalse(ChannelHeartbeat.isStale(null, now = 1_000_000L))
        assertFalse(ChannelHeartbeat.isStale(ChannelHeartbeat.Beat(), now = 1_000_000L))
    }

    @Test
    fun `阈值内不算 stale，超过阈值才算`() {
        val beat = ChannelHeartbeat.Beat(lastSuccessAt = 1_000_000L)
        val threshold = ChannelHeartbeat.STALE_AFTER_MS
        assertFalse(ChannelHeartbeat.isStale(beat, 1_000_000L + threshold))
        assertTrue(ChannelHeartbeat.isStale(beat, 1_000_000L + threshold + 1))
    }

    @Test
    fun `记录成功后连续失败计数清零`() {
        ChannelHeartbeat.failure(ChannelHeartbeat.Channel.DUMPSYS)
        ChannelHeartbeat.failure(ChannelHeartbeat.Channel.DUMPSYS)
        assertEquals(2, ChannelHeartbeat.beats.value[ChannelHeartbeat.Channel.DUMPSYS]?.consecutiveFailures)

        ChannelHeartbeat.record(ChannelHeartbeat.Channel.DUMPSYS, at = 1_000_000L)
        val beat = ChannelHeartbeat.beats.value[ChannelHeartbeat.Channel.DUMPSYS]
        assertEquals(1_000_000L, beat?.lastSuccessAt)
        assertEquals(0, beat?.consecutiveFailures)
    }

    @Test
    fun `同一时刻重复记录不产生新状态`() {
        ChannelHeartbeat.record(ChannelHeartbeat.Channel.DUMPSYS, at = 1_000_000L)
        val first = ChannelHeartbeat.beats.value
        ChannelHeartbeat.record(ChannelHeartbeat.Channel.DUMPSYS, at = 1_000_000L)
        assertTrue(first === ChannelHeartbeat.beats.value)
    }

    @Test
    fun `各通道互不影响`() {
        ChannelHeartbeat.record(ChannelHeartbeat.Channel.DUMPSYS, at = 1_000_000L)
        assertNull(ChannelHeartbeat.beats.value[ChannelHeartbeat.Channel.HOOK]?.lastSuccessAt)
        assertFalse(ChannelHeartbeat.isStale(ChannelHeartbeat.beats.value[ChannelHeartbeat.Channel.HOOK], 9_000_000L))
    }

    @Test
    fun `相对时间描述按秒分时切换`() {
        val beat = ChannelHeartbeat.Beat(lastSuccessAt = 1_000_000L)
        assertEquals("上次成功读取 5 秒前", ChannelHeartbeat.describe(beat, 1_000_000L + 5_000L))
        assertEquals("上次成功读取 2 分钟前", ChannelHeartbeat.describe(beat, 1_000_000L + 120_000L))
        assertEquals("上次成功读取 3 小时前", ChannelHeartbeat.describe(beat, 1_000_000L + 3 * 3_600_000L))
        assertNull(ChannelHeartbeat.describe(null, 1_000_000L))
    }
}
