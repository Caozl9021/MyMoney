package com.example.billkit.root

/**
 * 解析 `dumpsys notification --noredact` 的文本，取出「当前通知列表」里的标题与正文。
 *
 * 为什么需要它：通知通道是 SPEC §0 里唯一「不需要 root 也不需要 hook」的采集源，
 * 但它依赖 NotificationListenerService —— 被系统解绑、被厂商 ROM 掐掉时是哑的，
 * 而 root 模式本来就有常驻前台服务，可以顺手把同一批通知读出来。
 *
 * **这是启发式解析，不是契约**：`dumpsys` 的输出格式不是公开 API，各 ROM / 各版本
 * 都在改。所以这里只认「块以 NotificationRecord( 开头、块内出现 android.title= /
 * android.text= / android.bigText= / posttime=」这一种最常见形态；
 * 认不出来就返回空表，不抛异常、不猜。调用方据此什么都不做即可。
 *
 * 与 SPEC §5.1 的关系：产出与通知通道完全相同的 `{title, text, t}` 语义，
 * 因此复用同一批 24 条 NOTICE 规则，不需要第二套判定。
 */
object NotificationDumpParser {

    /** 一条从 dumpsys 里捞出来的通知 */
    data class Dumped(
        val packageName: String,
        val title: String,
        val text: String,
        val postTime: Long,
        /**
         * 通知槽位标识，取自 dumpsys 行内的 `key=<user>|<pkg>|<id>|<tag>|<uid>`。
         *
         * 为什么需要它：同一条通知被系统重新渲染时（微信把付款通知并入 `[2条]` 分组、
         * 通知被更新正文），`when` 与 `text` 都会变，只有这个 key 不变。
         * 采集层去重若只看 `postTime + text`，同一笔付款在归组后会被当成新通知再记一次账。
         * 取不到（老 ROM 格式不同）时为空串，去重退回内容哈希。
         */
        val slot: String = "",
    )

    private val PACKAGE = Regex("""\bpkg=([A-Za-z0-9_.]+)""")

    /**
     * 通知槽位：`key=0|com.tencent.mm|-656511598|null|10299`。
     *
     * 用户 0 / 包名 / 通知 id / tag / uid，五段一起才是全局唯一。
     * 用 `(null|[^|]*)` 而不是 `\w*`：tag 允许是字面量 `null`。
     */
    private val SLOT = Regex("""\bkey=([^|\s]+\|[^|\s]+\|[^|\s]+\|[^|\s]+\|[^|\s]+)""")

    /**
     * 发布时间。
     *
     * 老版本 dumpsys 有 `posttime=<毫秒>`，Android 14 之后这块改成 `when=<when>/<creationTime>`：
     * 实测 Android 17 的 dump 里**一个 `posttime=` 都没有**，只有 `when=` 和 stats 里的
     * `posttimeElapsedMs=`（后者是开机以来的相对毫秒，不是墙钟，不能当发布时间用）。
     * 两个都认，谁在就用谁；`when=` 取两个数字里较大的那个——`when=0` 表示
     * `setShowWhen(false)`，此时第二个数才是真正的创建时间。
     */
    private val POST_TIME = Regex("""\bposttime=(\d{10,})""")
    private val WHEN = Regex("""\bwhen=(\d{10,})/(\d{10,})""")
    private val WHEN_SINGLE = Regex("""\bwhen=(\d{10,})""")
    private const val MARKER = "NotificationRecord("

    /** 当前通知栏所在分区的标题（去掉缩进后比较）。 */
    private const val LIST_HEADER = "Notification List:"

    /** dumpsys 把没有值的字段渲染成字面量 `null`，不是空串。 */
    private const val NULL_LITERAL = "null"

    /**
     * 顶层分区行：固定 2 空格缩进 + 冒号结尾（`  Notification attention state:`）。
     *
     * 实测 Android 17 的 dump 里，Notification List 区间内没有任何一行符合这个形状，
     * 所以不会误判成「提前收工」。用「恰好 2 空格」而不是「≥2 空格」是关键：
     * 记录内部的字段缩进 6 空格、extras 缩进 12 空格，都带冒号或等号。
     */
    private fun isTopLevelSection(line: String): Boolean {
        if (line.length < 3) return false
        if (!line.startsWith("  ")) return false
        if (line[2] == ' ') return false
        return line.trimEnd().endsWith(":")
    }

    private val FIELDS = listOf(
        "android.bigText=" to "bigText",
        "android.title=" to "title",
        "android.text=" to "text",
    )

    /**
     * @param raw `dumpsys notification --noredact` 的完整输出
     * @return 按出现顺序排列的通知；正文优先取 bigText，缺失才退到 text
     */
    fun parse(raw: String?): List<Dumped> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Dumped>()
        var packageName: String? = null
        var postTime = 0L
        var title = ""
        var text = ""
        var bigText = ""
        var slot = ""
        // 有的 ROM / 老版本 dump 没有分区标题，整段就是记录列表。
        // 认不出分区标题时按「全文都是通知列表」处理，而不是直接返回空表。
        var inList = raw.lineSequence().none { it.trim() == LIST_HEADER }

        fun flush() {
            val pkg = packageName
            if (pkg != null && (title.isNotBlank() || bigText.isNotBlank() || text.isNotBlank())) {
                val body = bigText.ifBlank { text }
                out += Dumped(pkg, title.trim(), body.trim(), postTime, slot)
            }
            packageName = null
            postTime = 0L
            title = ""
            text = ""
            bigText = ""
            slot = ""
        }

        for (line in raw.lineSequence()) {
            // 只认「Notification List:」这一段：后面的 Group summaries / Usage Stats
            // 里也会出现 NotificationRecord(，那些是统计快照，不是当前通知栏。
            if (line.trim() == LIST_HEADER) {
                flush()
                inList = true
                continue
            }
            if (!inList) continue
            if (isTopLevelSection(line)) {
                // 下一个顶层分区（固定 2 空格缩进 + 冒号结尾），收工。
                flush()
                inList = false
                continue
            }
            if (line.contains(MARKER)) {
                flush()
                packageName = PACKAGE.find(line)?.groupValues?.get(1)
                postTime = timeOf(line)
                slot = SLOT.find(line)?.groupValues?.get(1).orEmpty()
                continue
            }
            if (packageName == null) continue
            for ((prefix, name) in FIELDS) {
                val index = line.indexOf(prefix)
                if (index < 0) continue
                val value = valueOf(line.substring(index + prefix.length))
                when (name) {
                    "title" -> if (title.isBlank()) title = value
                    "text" -> if (text.isBlank()) text = value
                    "bigText" -> if (bigText.isBlank()) bigText = value
                }
                break
            }
            if (postTime == 0L) postTime = timeOf(line)
        }
        flush()
        return out
    }

    /**
     * 从一行里取发布时间：先认老版 `posttime=`，再认 `when=a/b`（取较大者），
     * 最后认单值 `when=`。都取不到返回 0，调用方会退到「现在」。
     */
    private fun timeOf(line: String): Long {
        POST_TIME.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        WHEN.find(line)?.let { match ->
            val a = match.groupValues[1].toLongOrNull() ?: 0L
            val b = match.groupValues[2].toLongOrNull() ?: 0L
            val pick = maxOf(a, b)
            if (pick > 0L) return pick
        }
        WHEN_SINGLE.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { if (it > 0L) return it }
        return 0L
    }

    /**
     * dumpsys 把字符串渲染成 `String (正文)` / `SpannableString (正文)`，没有值就渲染成 `null`。
     * 只剥最外层那对括号；正文里本身带括号时保留（否则商户名会被啃掉半截）。
     */
    private fun valueOf(rendered: String): String {
        val trimmed = rendered.trim()
        if (trimmed == NULL_LITERAL || trimmed.isEmpty()) return ""
        val open = trimmed.indexOf('(')
        if (open < 0) return trimmed
        val close = trimmed.lastIndexOf(')')
        if (close <= open) return trimmed.substring(open + 1).trim()
        return trimmed.substring(open + 1, close).trim()
    }
}
