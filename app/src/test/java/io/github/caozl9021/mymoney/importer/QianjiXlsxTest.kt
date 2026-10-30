package io.github.caozl9021.mymoney.importer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.ZoneId
import io.github.caozl9021.mymoney.data.*

class QianjiXlsxTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun row(id: String = "qj1", amount: String = "19.99") = QianjiRow.requiredHeaders.associateWith { "" }.toMutableMap().apply {
        put("ID", id); put("时间", "2026-10-26 17:31:35"); put("类型", "支出"); put("金额", amount); put("币种", "CNY"); put("备注", " 保留 空格 ")
    }
    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
    private fun workbook(rows: List<Map<String,String>>, formula: Boolean = false, doctype: Boolean = false, shared: Boolean = false): ByteArray {
        val headers = QianjiRow.requiredHeaders.reversed()
        fun col(i: Int): String = if (i < 26) ('A' + i).toString() else "A" + ('A' + i - 26)
        val all = listOf(headers) + rows.map { r -> headers.map { r[it].orEmpty() } }
        var n = 0
        val sheet = (if (doctype) "<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" else "") + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" + all.mapIndexed { i, cells ->
            "<row r=\"${i+1}\">" + cells.mapIndexed { j, v ->
                val content = if (shared) "<v>${n++}</v>" else "<is><t>${xmlEscape(v)}</t></is>"
                "<c r=\"${col(j)}${i+1}\" t=\"${if(shared) "s" else "inlineStr"}\">${if(formula && i == 1 && j == 0) "<f>1+1</f>" else ""}$content</c>"
            }.joinToString("") + "</row>"
        }.joinToString("") + "</sheetData></worksheet>"
        val files = linkedMapOf(
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"账单\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to sheet)
        if (shared) files["xl/sharedStrings.xml"] = "<sst>" + all.flatten().joinToString("") { "<si><t>${xmlEscape(it)}</t></si>" } + "</sst>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }
    private fun read(bytes: ByteArray) = QianjiXlsx.read(ByteArrayInputStream(bytes), zone)
    @Test fun readsReorderedHeadersAndPreservesText() {
        for (shared in listOf(true, false)) {
            val doc = read(workbook(listOf(row()), shared = shared))
            val r = (doc.rows.single().result as QianjiRow.Result.Accepted).record
            assertEquals(1999, r.cents.toInt()); assertEquals(" 保留 空格 ", r.note); assertEquals(2, doc.rows.single().number)
        }
    }
    @Test fun rejectsFormulaAndDoctypeAndBrokenZip() {
        for (data in listOf(workbook(listOf(row()), formula=true), workbook(listOf(row()), doctype=true), byteArrayOf(1,2,3))) {
            assertTrue(runCatching { read(data) }.isFailure)
        }
    }
    @Test fun fileDuplicatesAndConflictsAreVisible() {
        val duplicate = QianjiImport.preview(read(workbook(listOf(row(), row()))), emptyList())
        assertEquals(1, duplicate.count(QianjiImport.State.READY)); assertEquals(1, duplicate.count(QianjiImport.State.DUPLICATE))
        val conflict = QianjiImport.preview(read(workbook(listOf(row(), row(amount="20")))), emptyList())
        assertEquals(0, conflict.count(QianjiImport.State.READY)); assertEquals(2, conflict.count(QianjiImport.State.CONFLICT))
    }
    @Test fun knownIdentityAndTombstoneCannotReimport() {
        val doc = read(workbook(listOf(row())))
        val first = QianjiImport.preview(doc, emptyList())
        val identity = first.items.single().identity!!
        assertEquals(1, QianjiImport.preview(doc, listOf(identity)).count(QianjiImport.State.DUPLICATE))
        assertEquals(1, QianjiImport.preview(doc, listOf(identity.copy(contentHash="0".repeat(64)))).count(QianjiImport.State.CONFLICT))
        assertEquals(1, QianjiImport.preview(doc, emptyList(), setOf(identity.sourceKey)).count(QianjiImport.State.DUPLICATE))
    }
    @Test fun ignoresBookForIdentityButNotChangedAmount() {
        val a = read(workbook(listOf(row())))
        val b = read(workbook(listOf(row().apply { put("账本", "其他账本") })))
        assertEquals(QianjiImport.preview(a, emptyList()).items.single().identity, QianjiImport.preview(b, emptyList()).items.single().identity)
    }
    @Test fun backupFiveCarriesIdentityAndTransferSnapshot() {
        val r = QianjiImport.preview(read(workbook(listOf(row()))), emptyList()).items.single().identity!!
        val b = Backup.Bill(100,"TRANSFER",1,null,null,null,"qianji","qianji-xlsx",r.sourceKey,1,null,null,"甲",null, transferAccount="乙")
        val snap = Backup.Snapshot(1,listOf(b),null,null, imports=listOf(r))
        val result = Backup.decode(Backup.encode(snap))
        assertEquals(listOf(r), result.imports); assertEquals("乙",result.bills.single().transferAccount)
        assertTrue(runCatching { ImportIdentityCodec.merge(listOf(r),listOf(r.copy(contentHash="0".repeat(64)))) }.isFailure)
        assertEquals(1,ImportIdentityCodec.merge(listOf(r),listOf(r)).size)
    }
    @Test fun sampleReadOnlyWhenExplicitlyProvided() {
        val path = System.getenv("QIANJI_SAMPLE") ?: return
        val doc = java.io.File(path).inputStream().use { QianjiXlsx.read(it,zone) }
        assertEquals(2279, doc.rows.size)
        val preview = QianjiImport.preview(doc,emptyList())
        println("QIANJI_SAMPLE rows=${doc.rows.size} ready=${preview.count(QianjiImport.State.READY)} skipped=${preview.count(QianjiImport.State.SKIPPED)} conflict=${preview.count(QianjiImport.State.CONFLICT)}")
        println("QIANJI_SAMPLE reasons="+preview.items.filter { it.state!=QianjiImport.State.READY }.groupingBy { it.reason }.eachCount())
        assertTrue(preview.count(QianjiImport.State.READY)>2200)
    }
}
