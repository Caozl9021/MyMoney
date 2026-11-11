package io.github.mymoney

import androidx.compose.ui.geometry.Size
import io.github.mymoney.data.BillEntity
import io.github.mymoney.data.Reimburse
import io.github.mymoney.ui.clusterCellGeometry
import io.github.mymoney.ui.clusterGap
import io.github.mymoney.ui.ledgerTotals
import io.github.mymoney.ui.shellRadius
import io.github.mymoney.ui.subcategoryAlignRow
import io.github.mymoney.ui.subcategoryRowCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReimburseLayoutTest {
    @Test
    fun subcategoryShapesFollowTheAgreedCounts() {
        assertEquals(listOf(1), subcategoryRowCounts(1))
        assertEquals(listOf(2), subcategoryRowCounts(2))
        assertEquals(listOf(1, 2), subcategoryRowCounts(3))
        assertEquals(listOf(2, 2), subcategoryRowCounts(4))
        assertEquals(listOf(3, 2), subcategoryRowCounts(5))
        assertEquals(listOf(3, 3), subcategoryRowCounts(6))
        assertEquals(listOf(2, 3, 2), subcategoryRowCounts(7))
        assertEquals(listOf(2, 3, 3), subcategoryRowCounts(8))
        assertEquals(listOf(3, 3, 3), subcategoryRowCounts(9))
        assertEquals(listOf(3, 4, 3), subcategoryRowCounts(10))
        assertEquals(listOf(4, 4, 3), subcategoryRowCounts(11))
        assertEquals(0, subcategoryAlignRow(1))
        assertEquals(0, subcategoryAlignRow(2))
        assertEquals(1, subcategoryAlignRow(3))
    }

    @Test
    fun cornerSelectionFollowsInsetShell() {
        val size = Size(200f, 156f)
        val gap = clusterGap(size)
        val inset = gap / 2f
        val outer = shellRadius(size) - inset
        // 8 格：2/3/3，index 1 = 右上角
        val corner = clusterCellGeometry(8, 1, size, gap)!!
        assertEquals(size.width - inset, corner.rect.right, 0.01f)
        assertEquals(inset, corner.rect.top, 0.01f)
        assertEquals(outer, corner.topRight, 0.01f)
        assertTrue(corner.topLeft < outer)
        assertTrue(corner.bottomRight < outer)

        val single = clusterCellGeometry(1, 0, size, gap)!!
        assertEquals(outer, single.topLeft, 0.01f)
        assertEquals(outer, single.topRight, 0.01f)
        assertEquals(outer, single.bottomRight, 0.01f)
        assertEquals(outer, single.bottomLeft, 0.01f)
    }

    @Test
    fun reimbursedExpenseLeavesTheTotal() {
        val pending = bill(Reimburse.PENDING, 1000)
        val done = bill(Reimburse.REIMBURSED, 2500)
        val income = bill(Reimburse.NONE, 400).copy(type = "INCOME")
        val totals = ledgerTotals(listOf(pending, done, income))
        assertEquals(1000, totals.expense)
        assertEquals(400, totals.income)
        assertEquals(-600, totals.balance)
    }

    private fun bill(status: String, cents: Long) = BillEntity(
        priceCents = cents, type = "EXPENSE", timeMillis = 1, merchant = null, method = null, orderTail = null,
        sourcePkg = "", strategy = "", fingerprint = "fp-$cents", createdAt = 1, reimburseStatus = status,
    )
}
