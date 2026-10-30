package io.github.mymoney

import io.github.mymoney.data.*
import io.github.mymoney.ui.*
import com.example.billkit.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class AccountPeriodHistoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val a = AssetAccount("a", "现金", initialized = true, createdAt = 1, updatedAt = 1)
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun row(id: Long, date: String, type: String = "EXPENSE", amount: Long = 100) = BillEntity(
        id = id, priceCents = amount, type = type, timeMillis = at(date), merchant = null, method = null,
        orderTail = null, sourcePkg = "test", strategy = "test", fingerprint = "bill-$id", createdAt = 1, accountId = a.id, account = a.name, assetKey = "key-$id")
    @Test fun currentEmptyIsPresentAndTransferOnlyHistoryIsHidden() {
        val periods = accountPeriodHistory(a, listOf(row(1,"2026-09-10","TRANSFER")), emptyList(), LocalDate.parse("2026-10-28"),1,zone)
        assertEquals(1,periods.size);assertTrue(periods.single().rows.isEmpty());assertEquals(0L,periods.single().outflow)
    }
    @Test fun customCrossYearBoundariesUseBillTimeAndShowOnlyPopulatedHistoricalPeriods() {
        val records = listOf(row(1,"2025-12-24"),row(2,"2025-12-25"),row(3,"2026-01-24"),row(4,"2026-01-25"))
        val result = accountPeriodHistory(a, records, emptyList(),LocalDate.parse("2026-01-20"),25,zone)
        val current = result.first();assertEquals(YearMonth.of(2025,12),current.period.month)
        assertEquals(setOf(2L,3L),current.rows.mapNotNull { it.bill?.id }.toSet());assertEquals(200L,current.outflow)
        assertEquals(4,result.sumOf { it.rows.size })
    }
    @Test fun expenseIncomeRefundAndFeeSumWithoutTransferOrPostingDuplication() {
        val bills=listOf(row(1,"2026-10-10",amount=1200),row(2,"2026-10-10","INCOME",2000),
            row(3,"2026-10-10","REFUND",300),row(4,"2026-10-10","TRANSFER",9000),
            row(5,"2026-10-10",amount=200).copy(feeParentKey="key-4"))
        val entries=listOf(AssetEntry("opening","o","a",10000,"OPENING","",at("2026-10-01")),
            AssetEntry("t","t","a",-9000,"TRANSFER","",at("2026-10-10"),"key-4"),
            AssetEntry("e","e","a",-1200,"BILL","",at("2026-10-10")+1,"key-1"))
        val result=accountPeriodHistory(a,bills,entries,LocalDate.parse("2026-10-28"),1,zone).first()
        assertEquals(1400L,result.outflow);assertEquals(2300L,result.inflow);assertEquals(5,result.rows.size)
        assertFalse(result.rows.any { it.bill?.type=="TRANSFER" || it.entry?.kind=="TRANSFER" })
        assertEquals(-200L,result.rows.first { it.bill?.id==1L }.balanceAfter)
        assertEquals(-200L,AssetMath.balance("a",entries))
    }
    @Test fun manualAdjustmentsAndTheirReversalsAreVisibleWithoutCountingAsIncome() {
        val entries=listOf(AssetEntry("a","a","a",500,"ADJUSTMENT","test",at("2026-10-03")),
            AssetEntry("r","r","a",-500,"REVERSAL","undo",at("2026-10-04"),reversalOf="a"))
        val result=accountPeriodHistory(a,emptyList(),entries,LocalDate.parse("2026-10-28"),1,zone).first()
        assertEquals(2,result.rows.size);assertEquals(0L,result.inflow);assertEquals(0L,result.outflow);assertEquals(0L,result.rows.first().balanceAfter)
    }
    @Test fun thousandRowsAndAliasedUnlinkedHistoryAreNotTruncatedOrCrossAssigned() {
        val account=a.copy(aliases="[\"旧现金\"]")
        val records=(1L..1000).map { row(it,"2026-10-12") } + row(1001,"2026-10-12").copy(accountId=null,account="旧现金") +
            row(1002,"2026-10-12").copy(accountId="other",account="现金")
        val result=accountPeriodHistory(account,records,emptyList(),LocalDate.parse("2026-10-28"),1,zone).first()
        assertEquals(1001,result.rows.size);assertEquals(100100L,result.outflow)
    }
    @Test fun dstRepeatedHourStaysInOneConfiguredLocalPeriod() {
        val ny=ZoneId.of("America/New_York")
        val bills=listOf(row(1,"2026-10-10").copy(timeMillis=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()),
            row(2,"2026-10-10").copy(timeMillis=Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()))
        val result=accountPeriodHistory(a,bills,emptyList(),LocalDate.parse("2026-11-02"),1,ny)
        assertEquals(2,result.first().rows.size);assertEquals(200L,result.first().outflow)
    }
    @Test fun transferDestinationSurvivesHistoryBackupAndProtectsLocalDraft() {
        val draft=BillDraft(100,BillType.TRANSFER,1,null,null,null,"test","test",account="现金",transferAccountId="to")
        val history=RecognitionEntity.fromDraft(draft,"test",1)
        assertEquals("to",history.toDraft().transferAccountId)
        val encoded=Backup.encode(Backup.Snapshot(1,emptyList(),null,null,history=listOf(history)))
        assertEquals(history,Backup.decode(encoded).history!!.single())
        val old=RecognitionCodec.encode(history).toMutableMap().also { it.remove("transferAccountId") }
        assertNull(RecognitionCodec.decode(old).transferAccountId)
        val newer=history.copy(transferAccountId="remote",updatedAt=3)
        val local=history.copy(protectedFields=CaptureFields.ACCOUNT)
        assertEquals("to",mergeRecognition(local,newer).transferAccountId)
        assertTrue(CaptureFields.changed(draft,draft.copy(transferAccountId="changed")) and CaptureFields.ACCOUNT != 0L)
        assertTrue(editorFieldsChanged(draft,draft.copy(transferAccountId="changed"),"1.00"))
        assertNull(Backup.decode(encoded.replace("\"format\":8","\"format\":7").replace("\"transferAccountId\":\"to\"","\"transferAccountId\":null")).history!!.single().transferAccountId)
    }
}
