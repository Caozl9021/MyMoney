package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.importer.QianjiImport
import io.github.caozl9021.mymoney.importer.QianjiRow
import io.github.caozl9021.mymoney.importer.QianjiXlsx
import io.github.caozl9021.mymoney.ui.QianjiRowFilter
import io.github.caozl9021.mymoney.ui.QIANJI_PREVIEW_PAGE_SIZE
import org.junit.Assert.*
import org.junit.Test

class QianjiPreviewDisplayTest {
    private fun item(index: Int, state: QianjiImport.State) = QianjiImport.Item(
        QianjiXlsx.Row(index + 2, emptyMap(), QianjiRow.Result.Skipped("synthetic")), state, "synthetic", null)

    @Test fun attentionContainsOnlySkippedAndConflictingRows() {
        val original = QianjiImport.State.entries.mapIndexed { i, state -> item(i, state) }
        assertEquals(setOf(QianjiImport.State.SKIPPED, QianjiImport.State.CONFLICT), QianjiRowFilter.ATTENTION.select(original).map { it.state }.toSet())
        assertEquals(original, QianjiRowFilter.ALL.select(original))
        assertEquals(4, original.size)
    }
    @Test fun incrementalPreviewReachesEveryRowWithoutDuplication() {
        val original = (0 until 2279).map { item(it,QianjiImport.State.READY) }
        val rows = QianjiRowFilter.READY.select(original)
        var count = QIANJI_PREVIEW_PAGE_SIZE
        while (count < rows.size) count += QIANJI_PREVIEW_PAGE_SIZE
        assertEquals(original,rows.take(count))
        assertEquals(2279, rows.take(count).map { it.row.number }.distinct().size)
        assertEquals(50,rows.take(QIANJI_PREVIEW_PAGE_SIZE).size)
    }
    @Test fun switchingFiltersPreservesSourceOrderAndState() {
        val original = (0 until 100).map { item(it,if(it%2==0) QianjiImport.State.DUPLICATE else QianjiImport.State.READY) }
        assertEquals((0 until 100 step 2).map { it + 2 },QianjiRowFilter.DUPLICATE.select(original).map { it.row.number })
        assertEquals(original,QianjiRowFilter.ALL.select(original))
    }
}
