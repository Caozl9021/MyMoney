package com.example.billkit.root

import com.example.billkit.spec.SpecDedupKey

/**
 * 用 root 直接读「当前通知列表」，绕过 NotificationListenerService 的授权与解绑问题。
 *
 * 为什么值得做（与 SPEC §0 的关系）：通知是 SPEC 里唯一免 hook、免 root 的采集源，
 * 但它挂在 `NotificationListenerService` 上——厂商 ROM 会在省电时静默解绑，
 * 用户也可能根本不给「通知使用权」。root 模式本来就有常驻前台服务，
 * `dumpsys notification --noredact` 能拿到同一批通知的标题与正文，
 * 于是「通知通道」在 root 模式下不再依赖任何运行时授权。
 *
 * 边界（必须写清楚，否则会被当成万能方案）：
 * - `dumpsys` 输出不是公开 API，各 ROM / 各 Android 版本格式都在改。解析器认不出就返回空表。
 * - `--noredact` 只在部分版本可用；不给时正文可能被打码，表现为规则不命中，不会误判。
 * - 这里读的是**通知栏**内容，不是微信/支付宝的数据库；不需要解密、不需要读第三方私有目录。
 * - 与 LSPosed 通道是两条独立的路：hook 拿到的是凭证原文（更准），dumpsys 拿到的是通知文案（更广）。
 *
 * 采集层去重放在这里（SPEC §7.4 第一层：TTL 15 分钟）：root 每轮都会把整份通知列表重读一遍，
 * 同一条通知会在连续多轮里反复出现，不去重就是反复落库。
 */
object RootNotificationSource {

    /** `--noredact` 拿未打码正文；老版本不认这个参数时 dumpsys 仍会输出，只是正文被打码 */
    private const val COMMAND = "dumpsys notification --noredact"

    data class ReadResult(
        val notifications: List<NotificationDumpParser.Dumped>,
        val error: String? = null,
    ) {
        val successful: Boolean get() = error == null
    }

    /** No raw output is exposed through health state; a valid empty notification list is successful. */
    fun read(timeoutMs: Long = 8_000L): ReadResult = fromShellResult(RootShell.exec(COMMAND, timeoutMs))

    fun dump(timeoutMs: Long = 8_000L): List<NotificationDumpParser.Dumped> = read(timeoutMs).notifications

    /** Pure classification keeps a permission error or unrecognised dump from looking like a healthy empty list. */
    fun fromShellResult(result: RootShell.Result): ReadResult {
        val output = result.output
        val denied = output.contains("permission denied", ignoreCase = true) ||
            output.contains("not allowed", ignoreCase = true) || output.contains("access denied", ignoreCase = true)
        if (denied) return ReadResult(emptyList(), "Root 未授权或通知读取被拒绝，请检查 Root 授权。")
        if (!result.ok) return ReadResult(emptyList(), "通知读取失败或超时，请检查 Root 后重试。")
        if (!output.contains("Notification List:")) return ReadResult(emptyList(), "暂不支持本机通知读取格式，请重试或改用普通模式。")
        return ReadResult(NotificationDumpParser.parse(output))
    }

}

/**
 * 采集层去重（SPEC §7.4 第一层）：同一条通知只交出去一次。
 *
 * 键优先取**通知槽位** `key=<user>|<pkg>|<id>|<tag>|<uid>`：它标识「这是哪个通知位」。
 * 同一条通知被系统重新渲染时（微信把付款通知并入 `[2条]` 分组、正文被更新），
 * `when` 与 `text` 都会变，只有槽位不变。真机教训：只按 `postTime + text` 做键时，
 * 一笔 ¥0.01 扫码付款在 11 分钟后因为 `text` 多了 `[2条]微信支付: ` 前缀、
 * `when` 被改成重新触发时刻，被当成第二条新通知又记了一笔账。
 *
 * **槽位即身份，内容变化不构成新通知**——这是有意的：同一个通知位上的正文更新（归组、
 * 补字段、改措辞）远比重用同一个位发第二笔更常见。代价是「同一槽位复用发第二笔」会被压掉，
 * 目前没有真机证据表明微信/支付宝会这样发（第二笔通常是新的子通知，槽位不同）。
 * 该假设若被真机证实，应改为「同槽位内容变化 → 待核对」而不是直接放行，见 OpenSpec
 * `decouple-capture-channels` 的 tasks 备注。
 *
 * [ttlMs] 的语义是**记住多久**，不是**抑制多久**：条目每轮被读到就续命。
 * 旧语义下同一条通知在通知栏里挂满 15 分钟会被当成新通知重放一次——这正是本类要避免的。
 * 现在只有消失超过 [ttlMs] 后再次出现，才算新通知。
 *
 * 槽位取不到（老 ROM 没这个字段）时退回 `包名|发布时间|归一化内容` 的哈希：
 * - 不能只用正文——两个不同应用可能发一模一样的营销文案；
 * - 不能只用发布时间——同一毫秒内多条通知（分组汇总 + 子通知）很常见；
 * - 不含「第几次读到」，否则每轮都会变；
 * - 内容先经 [normalize] 抹掉分组前缀，避免老 ROM 上同一笔因归组重复记账。
 *
 * 与 [com.example.billkit.spec.SpecDedup] 的区别：那个是**跨通道**判断「两条观测是不是同一笔交易」，
 * 这个是**同一通道内**判断「这条通知我是不是已经处理过了」。两件事，不能合并。
 */
class NotificationDumpDedup(
    private val ttlMs: Long = 15 * 60 * 1000L,
    private val maxEntries: Int = 256,
) {
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > maxEntries
    }

    /**
     * @return true 表示这条通知是新的、应当继续处理；false 表示已经处理过
     */
    @Synchronized
    fun accept(notification: NotificationDumpParser.Dumped, now: Long = System.currentTimeMillis()): Boolean {
        val key = keyOf(notification)
        val previous = seen[key]
        if (previous != null && now - previous < ttlMs) {
            // 命中即续命：还在通知栏里就继续记住它，避免 TTL 到期后被当成新通知重放。
            seen[key] = now
            return false
        }
        seen[key] = now
        if (seen.size > maxEntries * 3 / 4) {
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value >= ttlMs) iterator.remove()
            }
        }
        return true
    }

    @Synchronized
    fun clear() = seen.clear()

    @Synchronized
    fun size(): Int = seen.size

    companion object {
        fun keyOf(notification: NotificationDumpParser.Dumped): String {
            // 有槽位就以槽位为准：它标识「这是哪一条通知」，与内容怎么改无关。
            val slot = notification.slot
            if (slot.isNotBlank()) return "slot:" + slot
            // 没有槽位时内容就是身份，先归一化再哈希，避免归组前缀把同一笔变成两条。
            return SpecDedupKey.of(
                listOf(
                    notification.packageName,
                    notification.postTime.toString(),
                    normalize(notification.title, notification.text),
                ).joinToString("|"),
            )
        }

        /** 分组计数前缀：`[2条]` / `【3条消息】` / `(2条)`，可能带后随冒号与空白。 */
        private val GROUP_PREFIX = Regex("^\\s*[\\[【(（]\\s*\\d+\\s*条[^\\]】)）]*[\\]】)）]\\s*[:：]?\\s*")

        /** 空白差异不影响内容：全角空格、连续空格、首尾空白。 */
        private val WHITESPACE = Regex("[\\s\\u3000]+")

        /**
         * 抹掉「同一笔通知被重新渲染」时会变、但不改变业务含义的装饰：
         * - 分组计数前缀（`[2条]` 等）；
         * - 分组后加在正文前的会话标题（正文以标题 + 分隔符开头时剥掉）；
         * - 空白差异。
         *
         * 明确**不**处理：相对时间（「刚刚」「3 分钟前」）、未读计数后缀等尚未在真机见过的形态。
         * 漏归一化的代价是多记一条待核对，过度归一化的代价是吞掉真实交易——两者不对称。
         */
        fun normalize(title: String, text: String): String {
            val cleanTitle = WHITESPACE.replace(title, " ").trim()
            var body = WHITESPACE.replace(text, " ").trim()
            // 两轮足够：实测最多「分组前缀 + 会话标题前缀」两层，再多就是内容本身了。
            repeat(2) {
                body = GROUP_PREFIX.replace(body, "")
                if (cleanTitle.isNotEmpty() && body.length > cleanTitle.length && body.startsWith(cleanTitle)) {
                    val rest = body.substring(cleanTitle.length).trimStart(':', '：', '-', '|', '｜', ' ')
                    // 只在确实剥掉了分隔符时才采纳，避免把「标题本身就是正文开头」改成空串。
                    if (rest.length < body.length - cleanTitle.length) body = rest
                }
            }
            return cleanTitle + "\u0000" + body
        }
    }
}
