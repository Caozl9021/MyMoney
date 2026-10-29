package com.example.billkit.root

import com.example.billkit.notification.NotificationObservation

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
 * 只解析文本不代表可准入：还须识别完整记录头、flags 与闭合 extras。
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
        val channelId: String? = null,
        val category: String? = null,
        val template: String? = null,
        val flags: Long? = null,
        val categoryKnown: Boolean = false,
        val templateKnown: Boolean = false,
        val truncated: Boolean = false,
        val extrasComplete: Boolean = false,
    ) {
        /** 缺字段不能当作 false 或 null 去证明它不是聊天/汇总/持续通知。 */
        val envelopeMetadataKnown: Boolean get() = flags != null && categoryKnown && templateKnown && extrasComplete && !channelId.isNullOrBlank()

        fun observation(receivedAt: Long): NotificationObservation? {
            if (!envelopeMetadataKnown) return null
            val verifiedFlags = flags ?: return null
            return NotificationObservation(
                eventId = "root:$slot:$postTime", packageName = packageName, deviceScope = "root-notification",
                notificationKey = slot, notificationId = 0, notificationTag = null, userScope = "root",
                postTime = postTime, receivedAt = receivedAt, title = title, text = text, bigText = null,
                channelId = channelId, category = category, template = template,
                isGroupSummary = verifiedFlags and 0x200L != 0L,
                isOngoing = verifiedFlags and 0x2L != 0L,
                isMessagingStyle = template?.contains("MessagingStyle") == true,
                truncated = truncated,
            )
        }
    }

    private val PACKAGE = Regex("""\bpkg=([A-Za-z0-9_.]+)""")
    private val CHANNEL = Regex("""\bNotification\(channel=([^\s)]+)""")
    private val CATEGORY = Regex("""(?:^|\s)category=([^\s)]+)""")
    private val FLAGS = Regex("""(?:^\s*flags=|\bNotification\([^\n]*?\bflags=)([^\s)]+)""")

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
        var channelId: String? = null
        var category: String? = null
        var template: String? = null
        var flags: Long? = null
        var categoryKnown = false
        var templateKnown = false
        var truncated = false
        var extrasIndent: Int? = null
        var extrasComplete = false
        // 有的 ROM / 老版本 dump 没有分区标题，整段就是记录列表。
        // 认不出分区标题时按「全文都是通知列表」处理，而不是直接返回空表。
        var inList = raw.lineSequence().none { it.trim() == LIST_HEADER }

        fun flush() {
            val pkg = packageName
            if (pkg != null && (title.isNotBlank() || bigText.isNotBlank() || text.isNotBlank())) {
                val body = bigText.ifBlank { text }
                out += Dumped(pkg, title.trim(), body.trim(), postTime, slot,
                    channelId, category, template, flags, categoryKnown, templateKnown, truncated, extrasComplete)
            }
            packageName = null
            postTime = 0L
            title = ""
            text = ""
            bigText = ""
            slot = ""
            channelId = null
            category = null
            template = null
            flags = null
            categoryKnown = false
            templateKnown = false
            truncated = false
            extrasIndent = null
            extrasComplete = false
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
                channelId = CHANNEL.find(line)?.groupValues?.get(1)?.takeUnless { it == NULL_LITERAL }
                flags = FLAGS.find(line)?.groupValues?.get(1)?.let(::flagsOf)
                // AOSP Notification.toString 只在非 null 时输出 category；完整已识别的记录头
                // 中缺省即 null。不是从任意短片段/正文推断。
                // https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/app/Notification.java
                categoryKnown = channelId != null && line.trimEnd().endsWith("))")
                CATEGORY.find(line)?.groupValues?.get(1)?.let {
                    categoryKnown = true
                    category = it.takeUnless { value -> value == NULL_LITERAL }
                }
                continue
            }
            if (packageName == null) continue
            if (line.trim() == "extras={" && extrasIndent == null && !extrasComplete) {
                extrasIndent = line.indexOfFirst { !it.isWhitespace() }
            } else if (extrasIndent != null && line.trim() == "}" &&
                line.indexOfFirst { !it.isWhitespace() } == extrasIndent) {
                // NotificationRecord.dump 遍历完整 Bundle；闭合后模板键缺省表示无模板。
                // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/notification/NotificationRecord.java
                extrasComplete = true
                templateKnown = true
                extrasIndent = null
            }
            if (line.trimStart().startsWith("flags=")) {
                FLAGS.find(line)?.groupValues?.get(1)?.let { flags = flagsOf(it) }
            }
            if (line.trimStart().startsWith("category=")) {
                CATEGORY.find(line)?.groupValues?.get(1)?.let {
                    categoryKnown = true
                    category = it.takeUnless { value -> value == NULL_LITERAL }
                }
            }
            val templateIndex = if (line.trimStart().startsWith("android.template=")) line.indexOf("android.template=") else -1
            if (templateIndex >= 0) {
                templateKnown = true
                template = valueOf(line.substring(templateIndex + "android.template=".length)).ifBlank { null }
            }
            for ((prefix, name) in FIELDS) {
                val index = line.indexOf(prefix)
                if (index < 0) continue
                val value = valueOf(line.substring(index + prefix.length))
                val rendered = line.substring(index + prefix.length).trim()
                if ((rendered.contains('(') && !rendered.endsWith(')')) || value.endsWith("…") || value.endsWith("...")) truncated = true
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

    /** Android flags 可为数字或显式枚举；未知枚举不当作零。 */
    private fun flagsOf(value: String): Long? {
        if (value.startsWith("0x")) return value.substring(2).toLongOrNull(16)
        value.toLongOrNull()?.let { return it }
        val known = mapOf("ONGOING_EVENT" to 0x2L, "ONLY_ALERT_ONCE" to 0x8L,
            "AUTO_CANCEL" to 0x10L, "NO_CLEAR" to 0x20L, "FOREGROUND_SERVICE" to 0x40L,
            "HIGH_PRIORITY" to 0x80L, "LOCAL_ONLY" to 0x100L, "GROUP_SUMMARY" to 0x200L,
            "AUTOGROUP_SUMMARY" to 0x400L, "CAN_COLORIZE" to 0x800L,
            "BUBBLE" to 0x1000L, "NO_DISMISS" to 0x2000L, "FSI_REQUESTED_BUT_DENIED" to 0x4000L,
            "SHOW_LIGHTS" to 0x1L, "INSISTENT" to 0x4L)
        var result = 0L
        for (token in value.split('|')) result = result or (known[token.removePrefix("FLAG_")] ?: return null)
        return result
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
