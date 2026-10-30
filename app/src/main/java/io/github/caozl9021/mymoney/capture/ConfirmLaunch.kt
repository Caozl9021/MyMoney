package io.github.caozl9021.mymoney.capture

import java.util.concurrent.atomic.AtomicReference

/**
 * 确认页启动的「回执」。
 *
 * 为什么需要：Android 10+ 的后台启动限制（BAL）**拦截时不抛异常**，
 * `startActivity` 照样正常返回。于是 [ConfirmDelivery] 过去只能"猜"自己成功，
 * 猜错的后果就是用户看到「识别到了」但屏幕上什么都没有 —— 这是最坑的一种失败。
 *
 * 现在改成：拉起前登记本次指纹，[io.github.caozl9021.mymoney.ui.ConfirmActivity] 在
 * `onCreate` 里回报"我真的起来了"，投递方等一小会儿再下结论。
 * 没等到回执 → 一定是被系统拦了，立刻落待确认队列 + 通知，绝不静默。
 */
object ConfirmLaunch {

    private val requested = AtomicReference<String?>(null)
    private val launched = AtomicReference<String?>(null)

    /** 拉起前调用。 */
    fun begin(fingerprint: String) {
        requested.set(fingerprint)
        launched.set(null)
    }

    /** 由 ConfirmActivity.onCreate 调用。 */
    fun markLaunched(fingerprint: String) {
        launched.set(fingerprint)
    }

    /**
     * 等待确认页真正创建。超时返回 false 表示这次启动被系统吞了。
     *
     * 只在后台采集线程（或用户点击的主线程）调用，阻塞时间上限 [timeoutMs]。
     */
    fun awaitLaunched(fingerprint: String, timeoutMs: Long = 1_200L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (launched.get() == fingerprint) return true
            try {
                Thread.sleep(25L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return launched.get() == fingerprint
    }

    fun requestedFingerprint(): String? = requested.get()

    fun clear() {
        requested.set(null)
        launched.set(null)
    }
}
