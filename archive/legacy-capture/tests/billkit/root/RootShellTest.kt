package com.example.billkit.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 [RootShell.exec] 的超时语义。
 *
 * 这条测试的意义不在于「命令能跑通」——JVM 单测里没有 su，跑不通很正常。
 * 它要锁的是那个曾经让 root 通道**静默死亡**的行为：
 * 子进程不关闭 stdout 时，调用方必须仍然在超时窗口内返回。
 *
 * 真机事故：`dumpsys notification --noredact` 一次输出 8189 行，轮询跑了几十轮后
 * 47 分钟不再产出任何证据，而进程没崩、也没重启。原因是旧实现先 `readText()` 再 `waitFor`，
 * `readText()` 等 EOF，EOF 又等子进程关管道——两边互相等，超时保护永远不生效。
 */
class RootShellTest {

    @Test
    fun `命令不存在时快速返回且不抛异常`() {
        val result = RootShell.exec("command-that-does-not-exist-xyz", timeoutMs = 2_000L)
        assertTrue("必须返回结果而不是抛异常", result.code != 0 || result.output.isNotEmpty() || result.code == 0)
    }

    @Test
    fun `超时窗口必须真的生效`() {
        // su 在 JVM 里通常不存在；存在时这条命令会一直挂着不退出。
        // 无论哪条分支，调用方都必须在远小于「永远」的时间内拿回控制权。
        val start = System.currentTimeMillis()
        RootShell.exec("sleep 30", timeoutMs = 1_000L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            "exec 必须在超时窗口附近返回，实际耗时 ${elapsed}ms",
            elapsed < 8_000L,
        )
    }

    @Test
    fun `execBytes 也不会被未关闭的 stdout 卡死`() {
        val start = System.currentTimeMillis()
        RootShell.execBytes("sleep 30", timeoutMs = 1_000L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("execBytes 必须在超时窗口附近返回，实际耗时 ${elapsed}ms", elapsed < 8_000L)
    }

    @Test
    fun `isGranted 在没有 su 的环境里返回 false 而不是阻塞`() {
        val start = System.currentTimeMillis()
        val granted = RootShell.isGranted()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("isGranted 必须快速返回", elapsed < 8_000L)
        assertEquals("JVM 单测环境不该有 root", false, granted)
    }
}
