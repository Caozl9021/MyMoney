package io.github.mymoney

import com.example.billkit.BillDraft
import com.example.billkit.BillKit
import com.example.billkit.BillKitConfig
import com.example.billkit.DebugBus
import com.example.billkit.Packages
import com.example.billkit.UiSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** F16/F9: the same screen offered again (confirm card closed, page refocused) is not a new bill. */
class SamePageTest {
    private val minimal = listOf("支付成功", "测试商户（*某）", "¥0.01", "完成")
    private val drafts = mutableListOf<BillDraft>()
    private val t0 = 1_792_713_659_000L // hh:mm:59

    private fun offer(items: List<String>, now: Long, pkg: String = Packages.WECHAT) =
        BillKit.offer(UiSnapshot(pkg, null, items), now = now)

    private fun install(config: BillKitConfig = BillKitConfig()) =
        BillKit.install(config, sink = { drafts += it })

    @After fun tearDown() { BillKit.reset(); DebugBus.clear() }

    @Test fun `same minimal page re-offered across minute and beyond dedupe window is skipped`() {
        install()
        offer(minimal, t0)
        offer(minimal, t0 + 2_000)                  // next minute: old code produced a second draft
        assertEquals("skip:same-page", DebugBus.state.value?.outcome)
        offer(minimal, t0 + 120_000)                // beyond 90s dedupe window
        assertEquals(1, drafts.size)
    }

    @Test fun `a different screen in between allows the same success page as a new bill`() {
        install()
        offer(minimal, t0)
        offer(listOf("向商家付钱", "测试商户（*某）", "请输入金额"), t0 + 5_000)
        offer(minimal, t0 + 70_000)
        assertEquals(2, drafts.size)
    }

    @Test fun `another whitelisted app screen also ends the page session`() {
        install()
        offer(minimal, t0)
        offer(listOf("首页", "推荐"), t0 + 5_000, Packages.ALIPAY)
        offer(minimal, t0 + 70_000)
        assertEquals(2, drafts.size)
    }

    @Test fun `detail page with time and order keeps existing behaviour`() {
        install()
        offer(Samples.wechatBill, t0)
        offer(Samples.wechatBill, t0 + 5_000)
        assertEquals(1, drafts.size)
        offer(Samples.alipayBill, t0 + 10_000, Packages.ALIPAY)
        assertEquals(2, drafts.size)
    }

    @Test fun `throttled draft is not remembered and reset clears the session`() {
        install()
        offer(Samples.wechatBill, t0)
        offer(minimal, t0 + 100)                    // throttled (1.5s): must not be remembered
        assertEquals("skip:throttled", DebugBus.state.value?.outcome)
        offer(minimal, t0 + 2_000)
        assertEquals(2, drafts.size)
        BillKit.reset()
        offer(minimal, t0 + 200_000)
        assertEquals(3, drafts.size)
    }
}
