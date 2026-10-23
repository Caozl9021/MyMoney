package com.example.autobook.ui

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.autobook.capture.ConfirmDelivery
import com.example.autobook.capture.ConfirmLaunch
import com.example.autobook.data.BillWriter
import com.example.autobook.data.PendingStore
import com.example.autobook.export.CostShare
import com.example.billkit.BillDraft
import com.example.billkit.BillType

/**
 * 确认页（透明 Activity 宿主）。
 *
 * **只在两种情况下出现**：① 没有悬浮窗权限、只能退回 Activity 时；② 用户点了通知。
 * 正常情况下识别完立刻由 [com.example.autobook.overlay.ConfirmOverlay] 把同一张
 * 确认卡（[ConfirmSheet]）直接盖在对方 App 上，不启动 Activity、也不发通知。
 *
 * 窗口本身是 Translucent + backgroundDimEnabled，所以这里只画「压暗层 + 底部卡片」。
 */
class ConfirmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PRICE_CENTS = "price_cents"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TIME = "time"
        const val EXTRA_MERCHANT = "merchant"
        const val EXTRA_METHOD = "method"
        const val EXTRA_ORDER_TAIL = "order_tail"
        const val EXTRA_SOURCE_PKG = "source_pkg"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_SUB_CATEGORY = "sub_category"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_NOTE = "note"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val draft = readDraft()
        // 回报「我起来了」：投递方靠这个判断后台启动有没有被系统吞掉
        ConfirmLaunch.markLaunched(draft.fingerprint)
        ConfirmDelivery.cancel(this, draft.fingerprint)

        setContent {
            AutoBookTheme {
                var saving by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf<String?>(null) }
                ConfirmSheet(
                    draft = draft,
                    saving = saving,
                    errorMessage = saveError,
                    onSave = { edited ->
                        if (!saving) {
                            saving = true
                            saveError = null
                            BillWriter.save(applicationContext, edited, original = draft,
                                onDone = { finish() },
                                onError = { saving = false; saveError = it.message ?: "保存失败，请重试" })
                        }
                    },
                    onImport = { edited ->
                        if (!saving) {
                            saving = true
                            saveError = null
                            BillWriter.save(applicationContext, edited, original = draft,
                                onDone = { finish(); runCatching { CostShare.shareBills(applicationContext, listOf(edited), onlyTarget = true) } },
                                onError = { saving = false; saveError = it.message ?: "保存失败，请重试" })
                        }
                    },
                    onDiscard = { if (!saving) { PendingStore.remove(draft.fingerprint); finish() } },
                )
            }
        }
    }

    private fun readDraft(): BillDraft {
        val type = runCatching {
            BillType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "EXPENSE")
        }.getOrDefault(BillType.EXPENSE)

        return BillDraft(
            priceCents = intent.getLongExtra(EXTRA_PRICE_CENTS, 0),
            type = type,
            timeMillis = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
            merchant = intent.getStringExtra(EXTRA_MERCHANT),
            method = intent.getStringExtra(EXTRA_METHOD),
            orderTail = intent.getStringExtra(EXTRA_ORDER_TAIL),
            sourcePkg = intent.getStringExtra(EXTRA_SOURCE_PKG) ?: "",
            strategy = intent.getStringExtra(EXTRA_STRATEGY) ?: "",
            category = intent.getStringExtra(EXTRA_CATEGORY),
            subCategory = intent.getStringExtra(EXTRA_SUB_CATEGORY),
            account = intent.getStringExtra(EXTRA_ACCOUNT),
            note = intent.getStringExtra(EXTRA_NOTE),
        )
    }
}
