package com.example.autobook.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.autobook.data.AppDb
import com.example.autobook.data.BillWriter
import com.example.autobook.data.RecognitionStore
import kotlinx.coroutines.*
import com.example.autobook.export.CostShare
import com.example.autobook.ui.AutoBookTheme
import com.example.autobook.ui.ConfirmResultSheet
import com.example.autobook.ui.BillResultActivity
import com.example.autobook.ui.ConfirmSheet
import com.example.autobook.ui.ConfirmFeedback
import com.example.autobook.ui.design.AbHaptic
import android.widget.Toast
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把确认卡（[ConfirmSheet]）**直接以悬浮窗形式**盖在当前界面上。
 *
 * 为什么不用 Activity：Android 10+ 的后台启动限制（BAL）会静默吞掉 `startActivity`，
 * 只有"正在展示悬浮窗 + 持有 SYSTEM_ALERT_WINDOW"才稳定放行。而如果连 Activity 都不启动，
 * 就根本没有 BAL 这回事 —— 识别完成的一瞬间卡片就浮在支付宝/微信上面，
 * 不闪一下、不出通知、用户不需要点任何东西。这是唯一能做到"直接弹出"的路径。
 *
 * 生命周期：一次只允许一张编辑卡；保存后原位显示结果，下一笔可立即替换结果。
 * 返回键 / 熄屏关闭未保存草稿时保留待确认，关闭结果不改识别状态。
 * 没有悬浮窗权限时 [canShow] 为 false，调用方应退回 Activity 方案。
 */
object ConfirmOverlay {

    private const val TAG = "AutoBook"

    /** 等主线程把窗口挂上的上限（调用方一般在后台采集线程） */
    private const val SHOW_TIMEOUT_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var showing = false

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var owner: SheetOwner? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    fun canShow(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    fun isShowing(): Boolean = showing

    /**
     * 弹出确认卡。任意线程可调用。
     * @return true 表示卡片已经真的出现在屏幕上
     */
    fun show(context: Context, draft: BillDraft, allowed: () -> Boolean = { true }): Boolean {
        val app = context.applicationContext
        if (!canShow(app) || !allowed()) return false
        if (Looper.myLooper() == Looper.getMainLooper()) return showOnMain(app, draft, allowed)

        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val deadline = android.os.SystemClock.uptimeMillis() + SHOW_TIMEOUT_MS
        main.post {
            ok.set(showOnMain(app, draft) { allowed() && android.os.SystemClock.uptimeMillis() <= deadline })
            latch.countDown()
        }
        return try {
            latch.await(SHOW_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 撤掉确认卡（幂等，任意线程可调） */
    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            session?.let { card -> appContext?.let { setAside(it, card) } }
        } else {
            main.post { session?.let { card -> appContext?.let { setAside(it, card) } } }
        }
    }

    // ------------------------------------------------------------------

    private class Session(val draft: BillDraft) {
        var edited: BillDraft = draft
        var result by mutableStateOf<BillWriter.SaveResult?>(null)
        var sharing by mutableStateOf(false)
        var shareMessage by mutableStateOf<String?>(null)
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var busyLabel by mutableStateOf("保存中…")
        var pickerBackHandler: (() -> Boolean)? = null
    }
    private var session: Session? = null

    private fun showOnMain(app: Context, draft: BillDraft, allowed: () -> Boolean): Boolean {
        if (!allowed()) return false
        // Keep the existing editor intact; another draft remains durable and uses fallback delivery.
        if (session != null) {
            if (session?.result == null) return false
            dismissOnMain()
        }

        val session = Session(draft)
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val owner = SheetOwner()
        val root = FrameLayout(app).apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    // 返回键 = 先放一边，不是丢弃
                    if (!session.saving && session.pickerBackHandler?.invoke() != true) setAside(app, session)
                    true
                } else {
                    false
                }
            }
        }
        // Compose 在这条 View 树下需要自己的一套 lifecycle / savedstate
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        owner.create()

        val compose = ComposeView(app).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent { SheetContent(app, session) }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 盖住状态栏/导航栏，让压暗层覆盖整屏
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 备注/金额要能弹输入法
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        return try {
            if (!allowed()) return false
            wm.addView(root, params)
            appContext = app
            windowManager = wm
            rootView = root
            this.owner = owner
            this.session = session
            showing = true
            registerScreenOff(app, session)
            com.example.autobook.capture.ConfirmDelivery.cancel(app, draft.historyId ?: draft.fingerprint)
            root.requestFocus()
            true
        } catch (e: Exception) {
            Log.w(TAG, "确认卡悬浮窗挂载失败: ${e.message}")
            runCatching { wm.removeView(root) }
            owner.destroy()
            false
        }
    }

    private fun dismissOnMain() {
        val wm = windowManager
        val root = rootView
        windowManager = null
        rootView = null
        showing = false
        session = null

        if (root != null && wm != null) {
            runCatching { wm.removeView(root) }
        }
        unregisterScreenOff()
        owner?.destroy()
        owner = null
        appContext = null
    }

    @Composable
    private fun SheetContent(app: Context, card: Session) {
        AutoBookTheme {
            val result = card.result
            if (result != null) {
                ConfirmResultSheet(
                    draft = result.bill?.let(BillWriter::toDraft) ?: card.edited,
                    title = ConfirmFeedback.saved(result.inserted),
                    detail = ConfirmFeedback.resultDetail(result),
                    canView = result.bill != null,
                    sharing = card.sharing,
                    shareMessage = card.shareMessage,
                    onDismiss = { if (session === card) dismissOnMain() },
                    onView = { result.bill?.let { if (BillResultActivity.open(app, it.id) && session === card) dismissOnMain() } },
                    onShare = if (result.bill != null && result.bill.type != BillType.REFUND.name)
                        ({ share(app, card) }) else null,
                )
            } else ConfirmSheet(
                draft = card.edited,
                saving = card.saving,
                errorMessage = card.error,
                busyLabel = card.busyLabel,
                onSave = { edited -> save(app, card, edited) },
                onImport = { edited -> save(app, card, edited) },
                onIgnore = {
                    if (session === card && !card.saving) {
                        updateHistory(card) { card.draft.historyId?.let { RecognitionStore.ignore(it) } }
                    }
                },
                onDismiss = { edited -> card.edited = edited; setAside(app, card) },
                onDraftChange = { card.edited = it },
                onPickerBackHandler = { card.pickerBackHandler = it },
                noteSuggestions = com.example.autobook.ui.rememberNoteSuggestions(),
            )
        }
    }

    private fun save(app: Context, card: Session, edited: BillDraft) {
        if (session !== card || card.saving || card.result != null) return
        // Native back and screen-off consult this same synchronous flag.
        card.saving = true
        card.error = null
        card.busyLabel = "保存中…"
        card.edited = edited
        BillWriter.saveWithResult(app, edited, original = card.draft,
            onDone = { stored ->
                card.result = stored
                card.saving = false
                card.pickerBackHandler = null
                if (session === card) {
                    rootView?.let { AbHaptic(it).confirm() }
                    if (ConfirmFeedback.closeAfterSave(stored)) dismissOnMain()
                }
            },
            onError = {
                card.saving = false
                card.error = it.message ?: "保存失败，请重试"
                if (session !== card) {
                    // A replaced card still needs a recoverable draft when its write fails.
                    scope.launch { runCatching { RecognitionStore.preserve(edited) } }
                    Toast.makeText(app, "保存失败，已放到待确认，可重试", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun share(app: Context, card: Session) {
        val stored = card.result ?: return
        val bill = stored.bill ?: return
        if (session !== card || card.sharing || bill.type == BillType.REFUND.name) return
        card.sharing = true
        card.shareMessage = null
        scope.launch {
            try {
                val current = withContext(Dispatchers.IO) { AppDb.get(app).bills().byId(bill.id) }
                if (session !== card) return@launch
                if (current == null) {
                    card.result = stored.copy(bill = null)
                    card.shareMessage = "本地记录已处理，但关联账单已删除，无法分享"
                } else {
                    val launched = runCatching { CostShare.shareBills(app, listOf(BillWriter.toDraft(current)), onlyTarget = true) }.getOrNull() != null
                    val marked = runCatching { card.draft.historyId?.let { RecognitionStore.markShare(it, launched) } }.isSuccess
                    card.shareMessage = ConfirmFeedback.shared(stored.inserted, launched) + if (marked) "" else "；分享状态保存失败"
                    if (launched && session === card) {
                        // The overlay must not cover the target app's own import confirmation.
                        dismissOnMain()
                        Toast.makeText(app, card.shareMessage, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (failure: Exception) {
                card.shareMessage = "已保存到本地，分享失败，可重试"
            } finally {
                card.sharing = false
            }
        }
    }

    /** User dismissal preserves the draft; an in-flight save owns it until its result. */
    private fun setAside(app: Context, card: Session) {
        if (session !== card || card.saving) return
        if (card.result != null) {
            dismissOnMain()
            return
        }
        updateHistory(card) {
            RecognitionStore.preserve(card.edited)
            Toast.makeText(app, "已放到待确认，可在首页补记", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHistory(card: Session, action: suspend () -> Unit) {
        if (session !== card || card.saving) return
        card.saving = true
        scope.launch {
            try {
                action()
                if (session === card) dismissOnMain()
            } catch (error: Exception) {
                card.error = error.message ?: "更新失败，请重试"
            } finally {
                card.saving = false
            }
        }
    }

    private fun registerScreenOff(app: Context, card: Session) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) setAside(app, card)
            }
        }
        val ok = runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (ok) screenOffReceiver = receiver
    }

    private fun unregisterScreenOff() {
        val receiver = screenOffReceiver ?: return
        screenOffReceiver = null
        runCatching { appContext?.unregisterReceiver(receiver) }
    }

    /**
     * 最小可用的 LifecycleOwner：ComposeView 挂到普通窗口（不是 Activity）时，
     * 必须自己提供 lifecycle + savedstate，否则 setContent 会抛
     * "ViewTreeLifecycleOwner not found"。
     */
    private class SheetOwner : LifecycleOwner, SavedStateRegistryOwner {

        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performAttach()
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
