package com.example.autobook

import com.example.autobook.capture.GuardianPolicy
import com.example.autobook.capture.GuardianPolicy.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianPolicyTest {

    // -------------------- health 判定 --------------------

    @Test
    fun `unchecked in settings is DISABLED`() {
        assertEquals(Health.DISABLED, GuardianPolicy.health(enabledInSettings = false, serviceBound = false))
        // 设置里没勾但服务还连着（刚被用户关掉、还没断）：仍按"被关掉"处理
        assertEquals(Health.DISABLED, GuardianPolicy.health(enabledInSettings = false, serviceBound = true))
    }

    @Test
    fun `checked but unbound is UNBOUND`() {
        // 厂商"假开启"：设置里勾着，服务已经不在跑
        assertEquals(Health.UNBOUND, GuardianPolicy.health(enabledInSettings = true, serviceBound = false))
    }

    @Test
    fun `checked and bound is OK`() {
        assertEquals(Health.OK, GuardianPolicy.health(enabledInSettings = true, serviceBound = true))
    }

    // -------------------- 计数 --------------------

    @Test
    fun `ok resets the miss counter`() {
        assertEquals(0, GuardianPolicy.nextMisses(0, Health.OK))
        assertEquals(0, GuardianPolicy.nextMisses(7, Health.OK))
    }

    @Test
    fun `unhealthy states accumulate misses`() {
        assertEquals(1, GuardianPolicy.nextMisses(0, Health.DISABLED))
        assertEquals(3, GuardianPolicy.nextMisses(2, Health.UNBOUND))
    }

    // -------------------- 报警时机 --------------------

    @Test
    fun `disabled alerts after two misses`() {
        assertFalse(GuardianPolicy.shouldAlert(0, Health.DISABLED))
        assertFalse(GuardianPolicy.shouldAlert(1, Health.DISABLED))
        assertTrue(GuardianPolicy.shouldAlert(2, Health.DISABLED))
    }

    @Test
    fun `unbound waits longer than disabled`() {
        // 冷启动/开机后服务要过一会儿才连上，2 次就报会误报
        assertFalse(GuardianPolicy.shouldAlert(2, Health.UNBOUND))
        assertFalse(GuardianPolicy.shouldAlert(3, Health.UNBOUND))
        assertTrue(GuardianPolicy.shouldAlert(4, Health.UNBOUND))
    }

    @Test
    fun `ok never alerts`() {
        for (misses in 0..100) {
            assertFalse(GuardianPolicy.shouldAlert(misses, Health.OK))
        }
    }

    @Test
    fun `alert repeats only every realert window`() {
        val window = GuardianPolicy.REALERT_EVERY_MISSES
        val threshold = GuardianPolicy.alertThreshold(Health.DISABLED)
        assertTrue(GuardianPolicy.shouldAlert(threshold, Health.DISABLED))
        for (extra in 1 until window) {
            assertFalse(GuardianPolicy.shouldAlert(threshold + extra, Health.DISABLED))
        }
        assertTrue(GuardianPolicy.shouldAlert(threshold + window, Health.DISABLED))
        assertTrue(GuardianPolicy.shouldAlert(threshold + window * 2, Health.DISABLED))
    }

    @Test
    fun `alert survives a service that started while accessibility was already off`() {
        // 关键回归：旧实现首检永远不报警（lastCheckEnabled 初值 false）
        var misses = 0
        misses = GuardianPolicy.nextMisses(misses, Health.DISABLED)
        misses = GuardianPolicy.nextMisses(misses, Health.DISABLED)
        assertTrue(GuardianPolicy.shouldAlert(misses, Health.DISABLED))
    }

    @Test
    fun `miss counter is capped instead of growing forever`() {
        var misses = 0
        repeat(20_000) {
            misses = GuardianPolicy.nextMisses(misses, Health.DISABLED)
        }
        assertEquals(10_000, misses)
        assertTrue(misses > 0)
    }

    @Test
    fun `messages distinguish disabled from unbound`() {
        // 两种情况用户要做的动作一样（去设置里重开），但"设置里看着是开的"必须说清楚
        val disabled = GuardianPolicy.message(Health.DISABLED)
        val unbound = GuardianPolicy.message(Health.UNBOUND)
        assertTrue(disabled.isNotBlank())
        assertTrue(unbound.isNotBlank())
        assertNotEquals(disabled, unbound)
        assertTrue(unbound.contains("解绑"))
    }

    private fun assertNotEquals(a: String, b: String) {
        assertFalse("两个状态不该给同一句提示：$a", a == b)
    }
}
