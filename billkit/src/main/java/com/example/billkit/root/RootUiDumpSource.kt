package com.example.billkit.root

import com.example.billkit.UiSnapshot
import com.example.billkit.CollectedText
import com.example.billkit.TextCaptureBudget
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 用 root 权限抓当前界面，产出与无障碍路径完全相同的 UiSnapshot。
 *
 * 流程：
 *   dumpsys window  → 当前前台包名/Activity
 *   uiautomator dump → /data/local/tmp/ab_ui.xml（完整 UI 层级，含 text / content-desc / bounds）
 *   su cat            → 读回 XML，解析、排序
 *
 * 相比无障碍的优势：不受"服务被杀 / 厂商限制 / 授权被收回"影响；
 * 劣势：慢（单次 200~500ms）、需要轮询、且**不能拿 root 去读别人 App 的私有文件**（那是另一回事，不做）。
 */
class RootUiDumpSource(
    private val maxItems: Int = 200,
    private val maxChars: Int = 2000,
    private val rowBucketPx: Int = 24,
    private val maxVisited: Int = 5_000,
) {

    data class Foreground(val packageName: String, val activityClass: String?)

    private val focusRegex = Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")

    fun foreground(): Foreground? {
        // 有的 ROM 没有 grep，先试 grep 版（快），失败则退化成全量解析
        val quick = RootShell.exec("""dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -n 4""")
        val text = quick.output.ifBlank { RootShell.exec("dumpsys window").output }

        val m = focusRegex.find(text) ?: return null
        val pkg = m.groupValues[1]
        if (pkg == "android" || pkg.startsWith("com.android.systemui")) return null
        return Foreground(pkg, m.groupValues[2])
    }

    /**
     * 抓当前界面。调用方若刚跑过 [foreground]（轮询场景每轮一次），
     * 一定要把结果传进来 —— 否则每轮会跑两遍 `dumpsys window`（白花 50~100ms）。
     */
    fun dump(fg: Foreground): UiSnapshot? {
        // dump + 读回合成一条 su 命令：每次 su 都要 fork 一个 shell（~30~80ms），
        // 一条命令比两条省一次进程创建，是轮询里最划算的一处优化。
        // 先删旧文件：dump 失败时 cat 不能把上一屏的 XML 当成这一屏。
        val xml = RootShell.exec(
            "rm -f /data/local/tmp/ab_ui.xml; " +
                "uiautomator dump /data/local/tmp/ab_ui.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/ab_ui.xml",
            timeoutMs = 10_000,
        ).output
        if (xml.isBlank() || !xml.trimStart().startsWith("<?xml")) return null

        val collected = parseResult(xml)
        val items = collected.items
        if (items.isEmpty()) return null

        return UiSnapshot(
            packageName = fg.packageName,
            activityClass = fg.activityClass,
            items = items,
            truncated = collected.truncated,
            source = UiSnapshot.Source.ROOT_DUMP,
        )
    }

    /** 单次抓取（自己查前台）。轮询里请改用 [dump] 传入已查好的前台，省一次 dumpsys。 */
    fun dump(): UiSnapshot? = foreground()?.let { dump(it) }

    fun cleanup() {
        RootShell.exec("rm -f /data/local/tmp/ab_ui.xml")
    }

    // ---------- XML 解析 ----------

    private class Row(val text: String, val top: Int, val left: Int, val order: Int)

    /** Pure XML parsing entry point, also useful for saved capture regression fixtures. */
    fun parseResult(xml: String): CollectedText {
        val rows = ArrayList<Row>(64)
        val budget = TextCaptureBudget(maxItems, maxChars)
        var visited = 0
        var editableDepth = -1
        var order = 0

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == editableDepth) editableDepth = -1
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                if (visited++ >= maxVisited) {
                    budget.markTruncated()
                    break
                }
                val cls = parser.getAttributeValue(null, "class").orEmpty()
                val editable = cls.endsWith("EditText") || parser.getAttributeValue(null, "editable") == "true"
                if (editable && editableDepth == -1) editableDepth = parser.depth

                if (editableDepth == -1) {
                    val text = parser.getAttributeValue(null, "text")?.trim().orEmpty()
                    val desc = parser.getAttributeValue(null, "content-desc")?.trim().orEmpty()
                    val value = text.ifEmpty { desc }

                    if (value.length > 120) budget.markTruncated()
                    if (value.isNotEmpty() && value.length <= 120) {
                        val (top, left) = parseBounds(parser.getAttributeValue(null, "bounds"))
                        if (rows.lastOrNull()?.text != value) {
                            if (!budget.add(value)) break
                            rows += Row(value, top, left, order++)
                        }
                    }
                }
            }
            event = parser.next()
        }

        return CollectedText(
            rows.sortedWith(compareBy({ it.top / rowBucketPx }, { it.left }, { it.order })).map { it.text },
            budget.truncated,
        )
    }

    /** "[0,120][1080,260]" -> (120, 0) */
    private fun parseBounds(raw: String?): Pair<Int, Int> {
        if (raw.isNullOrBlank()) return 0 to 0
        val digits = raw.split('[', ']', ',')
            .mapNotNull { it.trim().toIntOrNull() }
        return if (digits.size >= 4) digits[1] to digits[0] else 0 to 0
    }
}
