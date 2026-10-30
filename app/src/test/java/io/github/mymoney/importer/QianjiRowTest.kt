package io.github.mymoney.importer

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class QianjiRowTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun row() = QianjiRow.requiredHeaders.associateWith { "" }.toMutableMap().apply {
        put("ID", "qj1"); put("时间", "2026-10-26 17:31:35"); put("类型", "支出")
        put("金额", "19.99"); put("币种", "CNY"); put("账户1", "账户甲")
    }
    @Test fun mapsExactAmount() {
        val record = (QianjiRow.map(row(), zone) as QianjiRow.Result.Accepted).record
        assertEquals(1999L, record.cents)
        assertEquals("EXPENSE", record.type)
    }
    @Test fun skipsAmbiguousRecords() {
        for ((key, value) in listOf("类型" to "报销", "币种" to "USD", "优惠券" to "1", "金额" to "1.001", "时间" to "2026-02-30 12:00:00")) {
            assertTrue(QianjiRow.map(row().apply { put(key, value) }, zone) is QianjiRow.Result.Skipped)
        }
    }
    @Test fun transferRequiresBothAccounts() {
        val cells = row().apply { put("类型", "转账") }
        assertTrue(QianjiRow.map(cells, zone) is QianjiRow.Result.Skipped)
        cells["账户2"] = "账户乙"
        assertEquals("账户乙", (QianjiRow.map(cells, zone) as QianjiRow.Result.Accepted).record.transferAccount)
    }
    @Test fun editingImportedBillKeepsSourceIdentity() {
        val fp = "qianji:" + "a".repeat(64)
        val original = io.github.mymoney.data.BillEntity(priceCents=1999,type="EXPENSE",timeMillis=1,
            merchant=null,method=null,orderTail=null,sourcePkg="qianji",strategy="qianji-xlsx",fingerprint=fp,createdAt=1)
        val edited = io.github.mymoney.data.applyBillEdit(original, com.example.billkit.BillDraft(
            priceCents=2000,type=com.example.billkit.BillType.EXPENSE,timeMillis=1,merchant="修改商户",method=null,orderTail=null,sourcePkg="qianji",strategy="qianji-xlsx"))
        assertEquals(fp, edited.fingerprint)
        assertEquals(2000L, edited.priceCents)
    }
}
