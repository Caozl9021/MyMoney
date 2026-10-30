package com.example.billkit.spec

import org.junit.Assert.*
import org.junit.Test

class CategoryEngineTest {
    private fun engine() = SpecCategoryEngine().apply {
        rebuild(SpecAssets.classpath(SpecAssets.COMMON_JS), SpecAssets.classpath(SpecAssets.CATEGORY_JS))
    }
    @Test fun `原创分类兼容入口可加载`() { assertTrue(engine().isReady()) }
    @Test fun `仅明确语义提供有限兜底`() {
        val e = engine()
        assertEquals("公交地铁", e.classify("公交地铁乘车", "Expend")?.category)
        assertEquals("医疗", e.classify("门诊挂号", "Expend")?.category)
        assertEquals("工资", e.classify("工资发放", "Income")?.category)
        assertNull(e.classify("未知商户", "Expend"))
    }
    @Test fun `不重复实现固定餐段和交易类型猜测`() {
        val e = engine()
        for (time in listOf("07:00", "12:00", "18:00", "23:00")) assertNull(e.classify("测试食堂", "Expend", "", time))
        assertNull(e.classify("退款", "Income"))
        assertNull(e.classify("工资", "Transfer"))
    }
}
