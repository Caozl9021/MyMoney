package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.data.*
import io.github.caozl9021.mymoney.ui.editorFieldsChanged
import com.example.billkit.*
import org.junit.Assert.*
import org.junit.Test

class NoteEnhancementTest {
    private fun draft() = BillDraft(1999, BillType.EXPENSE, 0, "京东", null, null, "pkg", "test",
        productName = "iPhone 16", productSource = ProductExtractor.RULE, noteTemplateSnapshot = "{商户}·{商品}")
    @Test fun productIsAlwaysExplicitOptIn() {
        val d = draft()
        assertEquals("京东", NoteTemplate.render(d, "{商户}·{商品}"))
        assertEquals("京东·iPhone 16", NoteTemplate.productSuggestion(d, "{商户}·{商品}"))
        assertEquals("iPhone 16", NoteTemplate.productSuggestion(d, "{商户}"))
        val suggestions = NoteSuggester.suggest(d, emptyList(), learningEnabled = false)
        assertEquals("商品", suggestions.first().source)
        assertEquals("京东·iPhone 16", suggestions.first().note)
    }
    @Test fun variableEmptinessDoesNotDamageLiteralModelNames() {
        assertEquals("3M-蓝色", NoteTemplate.render(draft().copy(merchant = null), "3M-蓝色·{商户}"))
        assertEquals("京东", NoteTemplate.render(draft(), "{商品}·{商户}"))
        assertEquals("京东·付款", NoteTemplate.render(draft(), "{商户}·{商品}·付款"))
        assertEquals("{商品}", NoteTemplate.render(draft(), "{{商品}}"))
        assertEquals(listOf("{未知}"), NoteTemplate.unknownTokens("{商户}·{未知}·{{商品}}"))
    }
    @Test fun dateAndSubcategoryHaveIndependentSemantics() {
        val d = draft().copy(subCategory = "数码")
        val date = java.text.SimpleDateFormat("MM-dd").format(java.util.Date(d.timeMillis))
        assertEquals("$date·数码", NoteTemplate.render(d, "{日期}·{二级分类}"))
        assertTrue(NoteTemplate.render(d, "{星期}").startsWith("星期"))
        val emoji = "😀".repeat(70)
        assertEquals(60, NoteTemplate.limit(emoji).codePointCount(0, NoteTemplate.limit(emoji).length))
    }
    @Test fun explicitFieldOnlyAndNumbersPreserved() {
        listOf("iPhone 16", "1L牛奶", "3M胶带", "5号电池").forEach { name ->
            assertEquals(name, ProductExtractor.extract(listOf("商品名称：$name", "实付金额", "19.99")))
        }
        assertEquals("生椰拿铁", ProductExtractor.extract(listOf("商品说明", "生椰拿铁", "实付款：19.99")))
        listOf(listOf("商品总额：99"), listOf("推荐商品：咖啡"), listOf("付款成功"),
            listOf("商品名称：A", "商品名称：B"), listOf("商品", "数量：2")).forEach { assertNull(ProductExtractor.extract(it)) }
        assertNull(ProductExtractor.extract(listOf("商品名称：A"), truncated = true))
        assertNull(ProductExtractor.extract(listOf("商品名称：A"), truncated = true))
    }
    @Test fun metadataCannotChangeIdentityAndProtectedEmptyRemainsEmpty() {
        val d = draft(); assertEquals(d.fingerprint, d.copy(productName = "别的商品").fingerprint)
        assertEquals(RecognitionIdentity.key(d), RecognitionIdentity.key(d.copy(productName = null)))
        val row = RecognitionEntity.fromDraft(d.copy(note = null, noteOrigin = NoteTemplate.USER))
        val merged = CaptureMergePolicy.enrich(row, d.copy(note = "自动建议"), editing = false)
        assertNull(merged.note)
        val reopened = RecognitionCodec.decode(RecognitionCodec.encode(row)).toDraft()
        assertEquals(d.productName, reopened.productName)
        assertEquals(NoteTemplate.USER, reopened.noteOrigin)
        assertEquals(d.noteTemplateSnapshot, reopened.noteTemplateSnapshot)
    }
    @Test fun dirtyBusinessValuesAndInvalidAmountsAreProtected() {
        val d = draft().copy(note = "备注")
        assertFalse(editorFieldsChanged(d, d, "19.99"))
        assertTrue(editorFieldsChanged(d, d.copy(note = null), "19.99"))
        assertTrue(editorFieldsChanged(d, d, "x"))
        assertFalse(editorFieldsChanged(d.copy(priceCents = 0), d.copy(priceCents = 0), ""))
    }
    @Test fun newBackupPreservesProductAndEmptyNoteOwnership() {
        val d = draft().copy(note = null, noteOrigin = NoteTemplate.USER)
        val b = Backup.Bill(d.priceCents, d.type.name, d.timeMillis, d.merchant, null, null, "pkg", "test", d.fingerprint,
            1, null, null, null, null, productName = d.productName, productSource = d.productSource,
            noteOrigin = d.noteOrigin, noteTemplateSnapshot = d.noteTemplateSnapshot)
        val decoded = Backup.decode(Backup.encode(Backup.Snapshot(1, listOf(b), null, null, history = listOf(RecognitionEntity.fromDraft(d)))))
        assertEquals(b, decoded.bills.single())
        assertEquals(NoteTemplate.USER, decoded.history!!.single().toDraft().noteOrigin)
    }
}
