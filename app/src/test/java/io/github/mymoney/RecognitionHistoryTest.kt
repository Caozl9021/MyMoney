package io.github.mymoney

import io.github.mymoney.data.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import org.junit.Assert.*
import org.junit.Test

class RecognitionHistoryTest {
    private fun draft() = BillDraft(1999, BillType.EXPENSE, 1700000000000,
        "咖啡", "零钱", null, "com.tencent.mm", "wechat", captureSessionId = "session-a")
    private fun row() = RecognitionEntity.fromDraft(draft(), at = 1700000001000)

    @Test fun `同金额独立页面会话不能合并且旧fingerprint保持不变`() {
        val a = draft()
        val b = a.copy(captureSessionId = "session-b")
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(b))
    }
    @Test fun `强订单跨页面和分钟仍是同一身份`() {
        val a = draft().copy(transactionKey = "full-order-hash")
        val b = a.copy(captureSessionId = "other", timeMillis = a.timeMillis + 60000, merchant = "edited")
        assertEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(b))
        assertNotEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(type = BillType.REFUND)))
    }
    @Test fun `明确页面时间优先于弱会话且商户抖动不影响身份`() {
        val a = draft().copy(hasExplicitTime = true)
        assertEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(captureSessionId = "other")))
        // 商户文案是页面上最易抖的字段，切页重绘/点更多都会变；同一笔必须仍视为同一身份。
        assertEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(merchant = "超市")))
        assertEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(merchant = null)))
    }
    @Test fun `明确页面时间但缺单号走time分支商户变化身份稳定`() {
        // F4：支付宝详情页单号常藏「更多」里，采集时缺 transactionKey 只有页面时间。
        // 此时必须走 time 分支（金额+类型+时间+方式），商户变化不影响身份，切页重绘不重复弹卡。
        val a = draft().copy(hasExplicitTime = true, transactionKey = null, method = "工商银行储蓄卡(5821)")
        assertEquals(null, a.transactionKey)
        assertEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(merchant = "换了个商户名", captureSessionId = "page-2")))
        // 补到单号后切到 order 分支仍是同一身份（time 分支与 order 分支对同一笔不冲突）
        assertNotEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(priceCents = 2000)))
    }
    @Test fun `明确页面时间时付款方式区分不同笔但金额与时间区分`() {
        val a = draft().copy(hasExplicitTime = true, method = "工商银行储蓄卡(5821)")
        // 换一张卡付款 → 视为不同笔
        assertNotEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(method = "零钱")))
        // 金额不同 → 不同笔
        assertNotEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(priceCents = 2000)))
        // 支付时间不同（秒级）→ 不同笔
        assertNotEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(a.copy(timeMillis = a.timeMillis + 1000)))
        // 三样都相同，即使 point 更多/商户/商品说明都变 → 同一笔，不重复弹卡
        val same = a.copy(merchant = "另一商户", productName = "另一商品说明", captureSessionId = "page-2")
        assertEquals(RecognitionIdentity.key(a), RecognitionIdentity.key(same))
    }
    @Test fun `历史草稿完整保留元数据及用户分类`() {
        val r = RecognitionEntity.fromDraft(draft().copy(category = "餐饮", note = "早餐", transactionKey = "order", hasExplicitTime = true))
        val back = r.toDraft()
        assertEquals(r.id, back.historyId)
        assertEquals(r.transactionKey, back.transactionKey)
        assertEquals(r.captureSessionId, back.captureSessionId)
        assertTrue(back.hasExplicitTime)
        assertEquals("早餐", back.note)
        assertEquals("餐饮", back.category)
    }
    @Test fun `JSON2往返保留全部历史处理分享身份及关联`() {
        val rows = listOf(row().copy(status = RecognitionStatus.RECORDED, billId = 42, shareState = RecognitionShareState.FAILED))
        val snap = Backup.Snapshot(100, emptyList(), null, null, rows)
        assertEquals(snap, Backup.decode(Backup.encode(snap)))
    }
    @Test fun `JSON1缺历史保持null而显式空历史保留空列表`() {
        assertNull(Backup.decode("""{"format":1,"bills":[]}""").history)
        assertEquals(emptyList<RecognitionEntity>(), Backup.decode("""{"format":2,"bills":[],"history":[]}""").history)
    }
    @Test fun `备份合并绝不降级已记账或丢失分享状态`() {
        val local = row().copy(status = RecognitionStatus.RECORDED, billId = 9, shareState = RecognitionShareState.REQUESTED)
        val incoming = local.copy(status = RecognitionStatus.PENDING, billId = null, shareState = RecognitionShareState.NOT_REQUESTED, updatedAt = local.updatedAt + 100)
        assertEquals(local, mergeRecognition(local, incoming))
    }
    @Test fun `恢复同强身份沿用本地UUID并可推进已记账`() {
        val local = row()
        val incoming = local.copy(id = "remote-id", status = RecognitionStatus.RECORDED, billId = 8)
        val merged = mergeRecognition(local, incoming)
        assertEquals(local.id, merged.id)
        assertEquals(RecognitionStatus.RECORDED, merged.status)
        assertEquals(8L, merged.billId)
    }
    @Test fun `坏历史状态和重复身份拒绝整份备份`() {
        val map = RecognitionCodec.encode(row()).toMutableMap()
        map["status"] = "UNKNOWN"
        try { RecognitionCodec.decode(map); fail("应拒绝未知状态") } catch (_: IllegalArgumentException) { }
        val rows = listOf(row(), row().copy(id = "other"))
        try { Backup.decode(Backup.encode(Backup.Snapshot(1, emptyList(), null, null, rows))); fail("应拒绝重复身份") } catch (_: IllegalArgumentException) { }
    }
}
