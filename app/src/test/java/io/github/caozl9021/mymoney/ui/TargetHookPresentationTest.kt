package io.github.caozl9021.mymoney.ui

import io.github.caozl9021.mymoney.lsp.HookLoadState
import io.github.caozl9021.mymoney.lsp.HookTargetStatus
import org.junit.Assert.*
import org.junit.Test

class TargetHookPresentationTest {
    @Test fun loadedModuleWithoutATransactionIsReadyAndWaiting() {
        val text = targetHookSubtitle(HookTargetStatus(state = HookLoadState.READY, count = 2), true, true)
        assertTrue(text.contains("模块已加载"))
        assertTrue(text.contains("等待交易凭证"))
        assertFalse(text.contains("未收到"))
        assertFalse(text.contains("请重新打开"))
    }
    @Test fun aReceiptCannotTurnAnUnconfirmedModuleIntoLoaded() {
        val text = targetHookSubtitle(HookTargetStatus(state = HookLoadState.NO_REPLY, lastReceiptAt = 1700000000000L), true, true)
        assertTrue(text.contains("尚未确认加载"))
        assertTrue(text.contains("最近凭证"))
        assertFalse(text.contains("模块已加载"))
    }
    @Test fun targetFailureAndOldCodeAreDifferentFromNoTransactions() {
        val failed = targetHookSubtitle(HookTargetStatus(state = HookLoadState.UNSUPPORTED), true, true)
        val old = targetHookSubtitle(HookTargetStatus(state = HookLoadState.OLD_VERSION), true, true)
        assertTrue(failed.contains("采集方法尚未就绪"))
        assertTrue(old.contains("已加载旧版"))
        assertFalse(failed.contains("等待交易凭证"))
        assertFalse(old.contains("等待交易凭证"))
    }
    @Test fun unknownArchiveVersionNeverPretendsToBeKnownOldOrReady() {
        val text = targetHookSubtitle(HookTargetStatus(state = HookLoadState.OLD_VERSION, version = "unknown"), true, true)
        assertTrue(text.contains("版本待确认"))
        assertFalse(text.contains("已加载旧版"))
        assertFalse(text.contains("等待交易凭证"))
    }
    @Test fun switchesNeverEraseLoadProofOrTheLastReceipt() {
        val status = HookTargetStatus(state = HookLoadState.READY, count = 1, lastReceiptAt = 1700000000000L)
        val off = targetHookSubtitle(status, true, false)
        val paused = targetHookSubtitle(status, false, true)
        assertTrue(off.contains("模块已加载")); assertTrue(off.contains("采集已关闭")); assertTrue(off.contains("最近凭证"))
        assertTrue(paused.contains("模块已加载")); assertTrue(paused.contains("自动记账已暂停")); assertTrue(paused.contains("最近凭证"))
    }
}
