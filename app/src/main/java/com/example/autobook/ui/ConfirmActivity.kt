package com.example.autobook.ui

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.autobook.ui.design.rememberAbHaptic
import android.widget.Toast
import com.example.autobook.capture.ConfirmDelivery
import com.example.autobook.capture.ConfirmLaunch
import com.example.autobook.data.AppDb
import com.example.autobook.data.BillWriter
import com.example.autobook.data.RecognitionStore
import com.example.autobook.data.RecognitionStatus
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
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

    private val foregroundRefreshRate by lazy { ForegroundRefreshRate(window) }

    override fun onResume() {
        super.onResume()
        foregroundRefreshRate.resume()
    }

    override fun onPause() {
        foregroundRefreshRate.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        foregroundRefreshRate.focusChanged(hasFocus)
    }

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
        const val EXTRA_HISTORY_ID = "history_id"
        const val EXTRA_TRANSACTION_KEY = "transaction_key"
        const val EXTRA_EXPLICIT_TIME = "explicit_time"
        const val EXTRA_CAPTURE_SESSION = "capture_session"
    }

    private var boundDraft by mutableStateOf<BillDraft?>(null)
    private var bindingGeneration = 0

    private fun automaticPresentationAllowed(incoming: Intent): Boolean {
        val token = incoming.getStringExtra("autobook_auto_capture_token") ?: return true
        val settings = com.example.autobook.data.Settings(this)
        return settings.enabled && settings.autoConfirm &&
            token == com.example.autobook.capture.NotificationIngress.configurationToken(this) &&
            (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked != true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureConfirmationWindow()
        super.onCreate(savedInstanceState)
        if (!automaticPresentationAllowed(intent)) { finish(); return }
        savedInstanceState?.getString(EXTRA_HISTORY_ID)?.let { intent.putExtra(EXTRA_HISTORY_ID, it) }
        bindIntent()
        setContent {
            AutoBookTheme {
                val draft = boundDraft
                if (draft != null) key(draft.historyId) {
                    LaunchedEffect(draft.historyId) {
                        withFrameNanos { }
                        withFrameNanos { }
                        if (automaticPresentationAllowed(intent)) ConfirmLaunch.markLaunched(draft.historyId ?: draft.fingerprint)
                        else finish()
                    }
                    val operation = remember(draft.historyId) {
                        ViewModelProvider(this@ConfirmActivity).get(draft.historyId!!, ConfirmSaveOperation::class.java)
                    }
                    val haptic = rememberAbHaptic()
                    DisposableEffect(operation, haptic) {
                        val feedbackHaptic: () -> Unit = { haptic.confirm() }
                        operation.onStored = feedbackHaptic
                        onDispose { if (operation.onStored === feedbackHaptic) operation.onStored = null }
                    }
                    LaunchedEffect(operation.feedback) {
                        if (operation.feedback != null && boundDraft?.historyId == draft.historyId) finish()
                    }
                    val result = operation.result
                    if (result != null) {
                        ConfirmResultSheet(
                            draft = result.bill?.let(BillWriter::toDraft) ?: operation.edited ?: draft,
                            title = ConfirmFeedback.saved(result.inserted),
                            detail = ConfirmFeedback.resultDetail(result),
                            canView = result.bill != null,
                            sharing = operation.sharing,
                            shareMessage = operation.shareMessage,
                            onDismiss = { finish() },
                            onView = { result.bill?.let { BillResultActivity.open(this@ConfirmActivity, it.id) } },
                            onShare = if (result.bill != null && result.bill.type != BillType.REFUND.name)
                                ({ operation.share(this@ConfirmActivity, draft.historyId) }) else null,
                        )
                    } else ConfirmSheet(
                        draft = operation.edited ?: draft,
                        saving = operation.saving,
                        errorMessage = operation.error,
                        busyLabel = operation.busyLabel,
                        onDraftChange = { operation.edited = it },
                        onSave = { operation.submit(applicationContext, draft, it) },
                        onImport = { operation.submit(applicationContext, draft, it) },
                        onIgnore = { operation.update { RecognitionStore.ignore(draft.historyId!!) } },
                        onDismiss = { edited -> operation.update { RecognitionStore.preserve(edited) } },
                        noteSuggestions = rememberNoteSuggestions(),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        boundDraft?.historyId?.let { outState.putString(EXTRA_HISTORY_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!automaticPresentationAllowed(intent)) return
        val current = boundDraft
        if (current != null) {
            val operation = ViewModelProvider(this).get(current.historyId!!, ConfirmSaveOperation::class.java)
            if (operation.result == null && operation.feedback == null) {
                Toast.makeText(applicationContext, "新识别已保留在待确认，请先处理当前账单", Toast.LENGTH_SHORT).show()
                return
            }
        }
        setIntent(intent)
        bindIntent()
    }

    private fun bindIntent() {
        val raw = readDraft()
        val generation = ++bindingGeneration
        boundDraft = null
        ConfirmDelivery.cancel(this, raw.historyId ?: raw.fingerprint)
        lifecycleScope.launch {
            try {
                val record = raw.historyId?.let { RecognitionStore.find(it) } ?: if (raw.historyId == null) RecognitionStore.record(raw) else null
                if (generation != bindingGeneration) return@launch
                if (record == null || record.status == RecognitionStatus.IGNORED) {
                    Toast.makeText(applicationContext, "该识别记录已处理或不存在，请在识别历史查看", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val restored = record.toDraft()
                    if (record.status == RecognitionStatus.RECORDED) {
                        val bill = withContext(Dispatchers.IO) { record.billId?.let { AppDb.get(applicationContext).bills().byId(it) } }
                        if (generation != bindingGeneration) return@launch
                        val operation = ViewModelProvider(this@ConfirmActivity).get(record.id, ConfirmSaveOperation::class.java)
                        operation.restoreResult(BillWriter.SaveResult(false, bill))
                    }
                    intent.putExtra(EXTRA_HISTORY_ID, restored.historyId)
                    if (!automaticPresentationAllowed(intent)) { finish(); return@launch }
                    boundDraft = restored
                }
            } catch (error: Exception) {
                if (generation == bindingGeneration) {
                    Toast.makeText(applicationContext, error.message ?: "识别历史读取失败，请重试", Toast.LENGTH_LONG).show()
                    finish()
                }
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
            historyId = intent.getStringExtra(EXTRA_HISTORY_ID),
            transactionKey = intent.getStringExtra(EXTRA_TRANSACTION_KEY),
            hasExplicitTime = intent.getBooleanExtra(EXTRA_EXPLICIT_TIME, false),
            captureSessionId = intent.getStringExtra(EXTRA_CAPTURE_SESSION),
        )
    }
}

/** Retained only for this Activity: recreation reconnects to its in-flight result, process death resets it. */
class ConfirmSaveOperation : ViewModel() {
    var edited: BillDraft? = null
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    var busyLabel by mutableStateOf("保存中…")
        private set
    // Cleared when the current composition leaves; no Activity or old View is retained across rotation.
    var onStored: (() -> Unit)? = null

    fun update(action: suspend () -> Unit) {
        if (saving || feedback != null) return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                action()
                feedback = "已更新"
            } catch (failure: Exception) {
                error = failure.message ?: "更新失败，请重试"
            } finally {
                saving = false
            }
        }
    }

    var result by mutableStateOf<BillWriter.SaveResult?>(null)
        private set
    var sharing by mutableStateOf(false)
        private set
    var shareMessage by mutableStateOf<String?>(null)
        private set

    fun restoreResult(stored: BillWriter.SaveResult) {
        if (result == null) result = stored
    }

    fun submit(context: android.content.Context, original: BillDraft, edited: BillDraft) {
        if (saving || feedback != null || result != null) return
        this.edited = edited
        saving = true
        error = null
        busyLabel = "保存中…"
        BillWriter.saveWithResult(context, edited, original = original,
            onDone = { stored ->
                result = stored
                saving = false
                onStored?.invoke()
            },
            onError = {
                saving = false
                error = it.message ?: "保存失败，请重试"
            },
        )
    }

    fun share(context: android.content.Context, historyId: String?) {
        val stored = result ?: return
        val bill = stored.bill ?: return
        if (sharing || bill.type == BillType.REFUND.name) return
        sharing = true
        shareMessage = null
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                val current = withContext(Dispatchers.IO) { AppDb.get(app).bills().byId(bill.id) }
                if (onStored == null) return@launch
                if (current == null) {
                    result = stored.copy(bill = null)
                    shareMessage = "本地记录已处理，但关联账单已删除，无法分享"
                } else {
                    val launched = runCatching { CostShare.shareBills(app, listOf(BillWriter.toDraft(current)), onlyTarget = true) }.getOrNull() != null
                    val marked = runCatching { historyId?.let { RecognitionStore.markShare(it, launched) } }.isSuccess
                    shareMessage = ConfirmFeedback.shared(stored.inserted, launched) + if (marked) "" else "；分享状态保存失败"
                }
            } catch (failure: Exception) {
                shareMessage = "已保存到本地，分享失败，可重试"
            } finally {
                sharing = false
            }
        }
    }
}
