package com.example.billkit.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真机抓下来的 dump 片段**锁住格式。
 *
 * 为什么要单独一个类：`NotificationDumpParserTest` 里的样本是我按假想格式手写的，
 * 结果和 Android 17 的真机输出对不上——真机上既没有 `posttime=`（改成 `when=a/b`），
 * 也没有 `android.bigText=` 的字符串形态（缺值时渲染成字面量 `null`）。
 * 手写样本会随我的想当然漂移，真机片段不会。所以这里直接放一份
 * `dumpsys notification --noredact` 的原始片段：
 * Android 17 (SDK 37) 真机抓取，只截取「Notification List:」前两条完整记录
 * + 一个顶层分区行。商户名、金额与地点已替换为示例值，结构与格式保持原样。
 */
class NotificationDumpParserDeviceTest {

    private val raw: String by lazy {
        javaClass.classLoader!!
            .getResourceAsStream("root/dumpsys-notification-android17.txt")!!
            .bufferedReader()
            .readText()
    }

    @Test
    fun `真机 dump 能解析出通知`() {
        val list = NotificationDumpParser.parse(raw)
        assertTrue("真机 dump 不该解析出空表", list.isNotEmpty())
        assertEquals(2, list.size)
    }

    @Test
    fun `发布时间取自 when 而不是消失的 posttime`() {
        val list = NotificationDumpParser.parse(raw)
        val shell = list.first { it.packageName == "com.android.shell" }
        assertEquals(1_793_098_105_530L, shell.postTime)
        // 三条都应有真实时间，不能是 0（0 会被调用方当成「现在」，把旧通知记成今天）
        assertTrue(list.all { it.postTime > 0L })
    }

    @Test
    fun `示例月付标题与正文原样取出`() {
        val list = NotificationDumpParser.parse(raw)
        val shell = list.first { it.packageName == "com.android.shell" }
        assertEquals("【示例月付】成功支付12.34元", shell.title)
        assertEquals("查看示例使用详情>", shell.text)
    }

    @Test
    fun `bigText 为字面量 null 时退到 text 而不是把 null 当正文`() {
        // 真机上 android.bigText=null 是「没有 bigText」，不是正文 "null"。
        // 回归点：早期实现直接取值，body 变成字符串 "null"，规则全部匹配不上。
        val list = NotificationDumpParser.parse(raw)
        assertTrue(list.none { it.text == "null" })
        assertEquals("查看示例使用详情>", list.first { it.packageName == "com.android.shell" }.text)
    }

    @Test
    fun `碰到下一个顶层分区就收工`() {
        // fixture 末尾追加了 `  Notification attention state:`，
        // 那里面的东西不该被当成通知记录。
        val list = NotificationDumpParser.parse(raw)
        assertTrue(list.none { it.packageName.isEmpty() })
        assertEquals(2, list.size)
    }

    @Test
    fun `真实dump省略可选类别可解析但不能伪换包名准入`() {
        val shell = NotificationDumpParser.parse(raw).first { it.packageName == "com.android.shell" }
        assertEquals("com.android.shell", shell.packageName)
        assertTrue(shell.envelopeMetadataKnown)
        assertEquals("unsupported-package", com.example.billkit.notification.NotificationEnvelopeGuard.rejection(
            shell.observation(1_793_098_105_600L)!!))
        val meituan = NotificationDumpParser.parse(raw).first { it.packageName == "com.sankuai.meituan" }
        assertTrue(meituan.envelopeMetadataKnown)
        assertEquals(null, meituan.category)
        assertEquals(null, meituan.template)
    }
}
