package io.github.mymoney.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通道心跳：每条采集通道「最近一次成功」的时间。
 *
 * 为什么需要它：进程活着、状态是 ready、没有异常，都不等于这条通道还在产出。
 * 真机见过一次 dumpsys 轮询静默停了 47 分钟——进程没崩、`RuntimeState.error` 一直是 null，
 * 只是再也不产出证据（见 `RootShell` 的注释）。只按「有没有报错」判健康，这类故障永远看不见。
 *
 * 只放内存、不落盘：心跳回答的是「本进程内这条通道还活着吗」，进程重启后重新建立即可；
 * 落盘会把上一次运行的旧时间当成现在的健康证明，反而制造假阳性。
 *
 * 写入按「状态变化」去抖：同一毫秒内重复 record 不产生新值，避免每 5 秒一轮的轮询
 * 把 UI 状态流打满。
 */
object ChannelHeartbeat {

    enum class Channel {
        /** 通知使用权通道（系统回调），事件驱动，没有轮询心跳。 */
        LISTENER,

        /** root 读通知（dumpsys 轮询），有固定节拍，最需要心跳。 */
        DUMPSYS,

        /** LSPosed 凭证通道，事件驱动。 */
        HOOK,

        /** 无障碍页面通道，事件驱动。 */
        PAGE,
    }

    data class Beat(val lastSuccessAt: Long? = null, val consecutiveFailures: Int = 0)

    /**
     * 判 stale 的默认阈值：dumpsys 每 5 秒一轮，取三个周期。
     * 比一个周期宽，是为了不把一次慢读当成故障；比十个周期窄，是为了故障能被及时看见。
     */
    const val STALE_AFTER_MS = 15_000L

    private val mutable = MutableStateFlow<Map<Channel, Beat>>(emptyMap())
    val beats = mutable.asStateFlow()

    @Synchronized
    fun record(channel: Channel, at: Long = System.currentTimeMillis()) {
        val current = mutable.value[channel]
        if (current?.lastSuccessAt == at && current.consecutiveFailures == 0) return
        mutable.value = mutable.value + (channel to Beat(at, 0))
    }

    @Synchronized
    fun failure(channel: Channel) {
        val current = mutable.value[channel] ?: Beat()
        mutable.value = mutable.value + (channel to current.copy(consecutiveFailures = current.consecutiveFailures + 1))
    }

    @Synchronized
    fun reset() {
        mutable.value = emptyMap()
    }

    /**
     * 是否已经超过阈值没有成功。
     *
     * 从未成功过时返回 false：那是「还没就绪」，由 readiness 状态负责，
     * 不属于心跳要报的「本来好好的、突然不产出了」。
     */
    fun isStale(beat: Beat?, now: Long, thresholdMs: Long = STALE_AFTER_MS): Boolean {
        val last = beat?.lastSuccessAt ?: return false
        return now - last > thresholdMs
    }

    /** 给界面用的相对时间描述；没有成功记录时返回 null，由调用方决定显示什么。 */
    fun describe(beat: Beat?, now: Long): String? {
        val last = beat?.lastSuccessAt ?: return null
        val seconds = ((now - last) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> "上次成功读取 ${seconds} 秒前"
            seconds < 3600L -> "上次成功读取 ${seconds / 60} 分钟前"
            else -> "上次成功读取 ${seconds / 3600} 小时前"
        }
    }
}
