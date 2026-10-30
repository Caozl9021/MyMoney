package io.github.mymoney.capture

import io.github.mymoney.data.SpecRuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「规则没就绪时到达的凭证」该被怎么对待。
 *
 * 这组用例守的是一条容易被后来的重构改坏的性质：**凭证只能被用户的明确意图丢掉**，
 * 不能因为「规则还没初始化完」这种内部时序而丢。三个分支任意一个写反，
 * 表现都是「某一笔付款没有记账」，而这类静默丢失在设备上几乎无法复现。
 */
class PendingReplayTest {

    @Test
    fun `通道被关掉时清标记，不再重试`() {
        assertEquals(ReplayAction.CLEAR, replayAction(usable = false, ready = true))
        assertEquals(ReplayAction.CLEAR, replayAction(usable = false, ready = false))
    }

    @Test
    fun `规则又没就绪时保留，不能当成已处理`() {
        assertEquals(ReplayAction.KEEP, replayAction(usable = true, ready = false))
    }

    @Test
    fun `通道可用且规则就绪时才真正重放`() {
        assertEquals(ReplayAction.PROCESS, replayAction(usable = true, ready = true))
    }

    @Test
    fun `只有钩子凭证留 pending`() {
        assertTrue(SpecRuleStore.shouldPersistPending("DATA"))
        // 通知与页面通道有别的恢复路径，落 pending 只会攒出没人重放的行。
        assertFalse(SpecRuleStore.shouldPersistPending("NOTICE"))
        assertFalse(SpecRuleStore.shouldPersistPending("SMS"))
        assertFalse(SpecRuleStore.shouldPersistPending(""))
    }
}
