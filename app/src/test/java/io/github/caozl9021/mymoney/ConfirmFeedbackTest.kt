package io.github.caozl9021.mymoney

import io.github.caozl9021.mymoney.ui.ConfirmFeedback
import org.junit.Assert.*
import org.junit.Test

class ConfirmFeedbackTest {
    @Test fun duplicateDoesNotReportANewEntry() {
        val message = ConfirmFeedback.saved(inserted = false)
        assertTrue(message.contains("已经记过"))
        assertFalse(message.contains("已记一笔"))
    }

    @Test fun failedShareKeepsLocalSaveExplicit() {
        val message = ConfirmFeedback.shared(inserted = true, launched = false)
        assertTrue(message.contains("已保存到本地"))
        assertTrue(message.contains("分享失败"))
        assertFalse(message.contains("已发起分享"))
    }

    @Test fun launchedShareRequiresExternalConfirmation() {
        for (inserted in listOf(true, false)) {
            val message = ConfirmFeedback.shared(inserted, launched = true)
            assertTrue(message.contains("已发起分享"))
            assertTrue(message.contains("目标 App 确认导入"))
            assertFalse(message.contains("导入成功"))
        }
    }
}
