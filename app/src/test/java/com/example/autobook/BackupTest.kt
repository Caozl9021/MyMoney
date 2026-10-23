package com.example.autobook

import com.example.autobook.data.Backup
import com.example.autobook.data.CategoryGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份文件格式的往返与容错。
 *
 * 这里刻意多写"缺字段/老版本/坏文件"的用例：备份是换机唯一的通道，
 * 恢复时崩了或静默丢数据，用户是拿不到第二次机会的。
 */
class BackupTest {

    private fun bill(
        cents: Long = 1999,
        tail: String? = "456789",
        t: Long = 1_700_000_000_000L,
    ) = Backup.Bill(
        priceCents = cents,
        type = "EXPENSE",
        timeMillis = t,
        merchant = "星巴克咖啡",
        method = "零钱",
        orderTail = tail,
        sourcePkg = "com.tencent.mm",
        strategy = "wechat-bill",
        fingerprint = "com.tencent.mm|$cents|${t / 60_000}|$tail",
        createdAt = t + 5_000,
        category = "餐饮",
        subCategory = "咖啡茶饮",
        account = "微信钱包",
        note = "星巴克咖啡·早餐",
    )

    private fun snapshot(bills: List<Backup.Bill>) = Backup.Snapshot(
        exportedAt = 1_700_000_100_000L,
        bills = bills,
        ledger = Backup.Ledger(
            expense = listOf(CategoryGroup("餐饮", listOf("三餐", "外卖"))),
            income = listOf(CategoryGroup("工资")),
            accounts = listOf("微信钱包", "支付宝"),
            noteTemplate = "{商户}·{分类}",
        ),
        settings = Backup.SettingsSnapshot(
            whitelist = listOf("com.tencent.mm", "com.eg.android.AlipayGphone"),
            minIntervalMs = 1_200L,
            ocrFallback = false,
            rootMode = true,
        ),
    )

    @Test
    fun `退款备份往返保留类型正金额及独立指纹`() {
        val draft = com.example.billkit.BillDraft(1999, com.example.billkit.BillType.REFUND,
            1_700_000_000_000L, null, null, "456789", "com.tencent.mm", "refund")
        val original = bill().copy(type = "REFUND", fingerprint = draft.fingerprint)
        assertEquals(original, Backup.decode(Backup.encode(snapshot(listOf(original)))).bills.single())
        val raw = Backup.encode(snapshot(listOf(original))).replace("\"fp\":\"${draft.fingerprint}\",", "")
        assertEquals(draft.fingerprint, Backup.decode(raw).bills.single().fingerprint)
    }

    @Test
    fun `完整快照往返不丢字段`() {
        val src = snapshot(listOf(bill()))
        val back = Backup.decode(Backup.encode(src))

        assertEquals(src.exportedAt, back.exportedAt)
        assertEquals(1, back.bills.size)
        val b = back.bills[0]
        assertEquals(1999L, b.priceCents)
        assertEquals("EXPENSE", b.type)
        assertEquals("星巴克咖啡", b.merchant)
        assertEquals("零钱", b.method)
        assertEquals("456789", b.orderTail)
        assertEquals("com.tencent.mm", b.sourcePkg)
        assertEquals("餐饮", b.category)
        assertEquals("咖啡茶饮", b.subCategory)
        assertEquals("微信钱包", b.account)
        assertEquals("星巴克咖啡·早餐", b.note)
        assertEquals(src.bills[0].fingerprint, b.fingerprint)
        assertEquals(src.bills[0].createdAt, b.createdAt)

        val ledger = back.ledger
        assertNotNull(ledger)
        assertEquals(listOf("三餐", "外卖"), ledger!!.expense[0].subs)
        assertEquals("{商户}·{分类}", ledger.noteTemplate)

        val s = back.settings
        assertNotNull(s)
        assertEquals(1_200L, s!!.minIntervalMs)
        assertEquals(false, s.ocrFallback)
        assertEquals(true, s.rootMode)
        assertEquals(2, s.whitelist.size)
    }

    @Test
    fun `空账单也能往返`() {
        val back = Backup.decode(Backup.encode(snapshot(emptyList())))
        assertTrue(back.bills.isEmpty())
    }

    @Test
    fun `缺指纹时按 BillDraft 规则补一个`() {
        // 老版本备份没有 fp 字段；不补的话恢复进来的记录绕不过落库去重
        val json = """
            {"kind":"AutoBookBackup","format":1,"exportedAt":100,
             "bills":[{"p":1999,"ty":"EXPENSE","t":1700000000000,
                       "sp":"com.tencent.mm","st":"x","ot":"456789"}]}
        """.trimIndent()
        val b = Backup.decode(json).bills.single()
        assertEquals("com.tencent.mm|1999|${1_700_000_000_000L / 60_000}|456789", b.fingerprint)
        // createdAt 缺失时回落到账单时间，避免排序把它甩到 1970
        assertEquals(1_700_000_000_000L, b.createdAt)
    }

    @Test
    fun `缺可选字段按默认值处理而不是抛异常`() {
        val json = """{"kind":"AutoBookBackup","bills":[{"p":500}]}"""
        val snap = Backup.decode(json)
        val b = snap.bills.single()
        assertEquals("EXPENSE", b.type)
        assertEquals("", b.sourcePkg)
        assertEquals("backup", b.strategy)
        assertNull(b.merchant)
        assertNull(snap.ledger)
        assertNull(snap.settings)
    }

    @Test
    fun `列表里的坏元素被跳过而不是整份失败`() {
        val json = """
            {"kind":"AutoBookBackup","bills":[{"p":100},"oops",{"p":200}]}
        """.trimIndent()
        assertEquals(2, Backup.decode(json).bills.size)
    }

    @Test
    fun `不是本 App 的备份要明确报错`() {
        val json = """{"kind":"SomeOtherApp","bills":[]}"""
        try {
            Backup.decode(json)
            fail("应该拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SomeOtherApp"))
        }
    }

    @Test
    fun `备份版本太新要提示升级而不是硬读`() {
        val json = """{"kind":"AutoBookBackup","format":99,"bills":[]}"""
        try {
            Backup.decode(json)
            fail("应该拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test
    fun `没有 bills 字段视为坏文件`() {
        try {
            Backup.decode("""{"kind":"AutoBookBackup"}""")
            fail("应该拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bills"))
        }
    }

    @Test
    fun `空分类名的组被丢弃`() {
        val json = """
            {"kind":"AutoBookBackup","bills":[],
             "ledger":{"expense":[{"n":"","s":["x"]},{"n":"餐饮","s":["三餐"]}]}}
        """.trimIndent()
        val ledger = Backup.decode(json).ledger!!
        assertEquals(1, ledger.expense.size)
        assertEquals("餐饮", ledger.expense[0].name)
    }
}
