package com.example.mymoney.capture

/**
 * 守护服务的判定逻辑（纯函数，可单测）。
 *
 * 修复的两个旧缺陷：
 * 1. 旧实现"只在从启用变成禁用的那一次才计数"，而 `lastCheckEnabled` 初值是 `false`
 *    —— 服务启动时无障碍**已经**是关着的话，永远走不到报警分支。现在直接累积
 *    "连续异常次数"，不依赖上一次观测值，首检即生效。
 * 2. 旧实现只看 `Settings.Secure` 里那条记录。有些厂商会**只解绑不取消勾选**：
 *    系统设置里还打着勾，实际服务已经不在跑 —— 这种"假开启"旧实现判不出来。
 *    现在把"已勾选"和"已绑定"分开看。
 */
object GuardianPolicy {

    /** 连续多少次（30s 一次）未启用才认定真的失效：2 次 = 约 60s，避开切换瞬间的抖动 */
    const val ALERT_AFTER_MISSES = 2

    /**
     * "已勾选但没绑定"放宽到 4 次（约 2 分钟）：
     * 冷启动/开机后无障碍服务要过一会儿才连上，不能一上来就误报。
     */
    const val UNBOUND_ALERT_AFTER_MISSES = 4

    /** 用户忽略提醒后，再等多少次才重复提醒（20 次 ≈ 10 分钟），避免刷屏 */
    const val REALERT_EVERY_MISSES = 20

    /** 计数封顶：守护服务可能几天不重启，别让它无限增长 */
    private const val MISS_CAP = 10_000

    /** 无障碍的真实健康状态 */
    enum class Health {
        /** 系统里勾着，且服务确实绑上了 */
        OK,

        /** 系统设置里已经没有这一项：被关掉了 */
        DISABLED,

        /** 系统设置里还勾着，但服务没绑上：被系统/厂商解绑了 */
        UNBOUND,
    }

    fun health(enabledInSettings: Boolean, serviceBound: Boolean): Health = when {
        !enabledInSettings -> Health.DISABLED
        !serviceBound -> Health.UNBOUND
        else -> Health.OK
    }

    /** 健康时清零；不健康时累加。 */
    fun nextMisses(current: Int, health: Health): Int =
        if (health == Health.OK) 0 else (current + 1).coerceAtMost(MISS_CAP)

    fun alertThreshold(health: Health): Int =
        if (health == Health.UNBOUND) UNBOUND_ALERT_AFTER_MISSES else ALERT_AFTER_MISSES

    /** 到阈值提醒一次，之后每个 [REALERT_EVERY_MISSES] 窗口再提醒一次。 */
    fun shouldAlert(misses: Int, health: Health): Boolean {
        if (health == Health.OK) return false
        val threshold = alertThreshold(health)
        return misses >= threshold && (misses - threshold) % REALERT_EVERY_MISSES == 0
    }

    /** 给用户看的一句话（通知正文），不带技术名词。 */
    fun message(health: Health): String = when (health) {
        Health.OK -> "无障碍服务正常"
        Health.DISABLED -> "无障碍服务已被关闭，自动记账已停摆"
        Health.UNBOUND -> "无障碍服务被系统解绑了（设置里仍显示开启），自动记账已停摆"
    }
}
