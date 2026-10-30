package io.github.mymoney

import com.example.billkit.AmountParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class AmountParserTest {

    @Test
    fun `toCents 处理千分位与币种`() {
        assertEquals(123450L, AmountParser.toCents("¥1,234.5"))
        assertEquals(2580L, AmountParser.toCents("￥25.80"))
        assertEquals(990L, AmountParser.toCents("9.9元"))
        assertNull(AmountParser.toCents("--"))
        assertNull(AmountParser.toCents("99999999"))
    }

    @Test
    fun `优先取标签后的金额`() {
        val items = listOf("支付成功", "¥25.80", "实付款", "128.00", "订单编号", "202410211234567890")
        assertEquals(12800L, AmountParser.findAmount(items)?.cents)
    }

    @Test
    fun `订单号里的数字不算金额`() {
        val items = listOf("交易成功", "订单编号", "202410211234567890", "¥12.00")
        assertEquals(1200L, AmountParser.findAmount(items)?.cents)
    }

    @Test
    fun `没有负号的裸金额也能识别并带回方向`() {
        // 微信交易详情：金额是 -3.50，既没有 ¥ 也不是标签格式
        val items = listOf("交易详情", "-3.50", "扫二维码付款-给早餐店", "支付方式", "工商银行储蓄卡(5821)")
        val hit = AmountParser.findAmount(items)
        assertEquals(350L, hit?.cents)
        assertEquals(-1, hit?.sign)
    }

    @Test
    fun `支付宝的支出二字既是标签也是方向`() {
        // 真机页面没有 ¥，金额和「支出」粘在一起
        val items = listOf("东富路家味餐厅", "支出19元", "交易成功", "支付时间", "2026-10-20 11:49:27")
        val hit = AmountParser.findAmount(items)
        assertEquals(1900L, hit?.cents)
        assertEquals(-1, hit?.sign)
    }

    @Test
    fun `收入标签给正向`() {
        val items = listOf("转账成功", "收入 1,200.00", "转账时间", "2026-10-20 11:49:27")
        val hit = AmountParser.findAmount(items)
        assertEquals(120000L, hit?.cents)
        assertEquals(1, hit?.sign)
    }

    @Test
    fun `带正号的金额是收入`() {
        val hit = AmountParser.findAmount(listOf("收款详情", "+12.00", "当前状态", "已收钱"))
        assertEquals(1200L, hit?.cents)
        assertEquals(1, hit?.sign)
    }

    @Test
    fun `没有金额时返回 null 而不是猜`() {
        val items = listOf("支付成功", "查看订单", "完成")
        assertNull(AmountParser.findAmount(items))
    }

    @Test
    fun `解析中文日期时间`() {
        val items = listOf("支付时间", "2024年10月21日 09:50:12")
        val expected = LocalDateTime.of(2024, 10, 21, 9, 50, 12)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, AmountParser.findTime(items, LocalDateTime.of(2024, 1, 1, 0, 0)))
    }

    @Test
    fun `非法日期被丢弃`() {
        assertNull(
            AmountParser.findTime(
                listOf("2024年13月45日 99:99"),
                LocalDateTime.of(2024, 1, 1, 0, 0),
            )
        )
    }

    @Test
    fun `状态判定优先级：失败 高于 成功`() {
        assertEquals(
            AmountParser.Status.FAILED,
            AmountParser.status(listOf("支付失败", "支付成功")),
        )
        assertEquals(
            AmountParser.Status.PENDING,
            AmountParser.status(listOf("正在付款", "请输入支付密码")),
        )
        assertEquals(
            AmountParser.Status.SUCCESS,
            AmountParser.status(listOf("交易成功", "付款方式", "零钱")),
        )
        assertEquals(
            AmountParser.Status.UNKNOWN,
            AmountParser.status(listOf("账单详情", "商户名称")),
        )
    }
}
