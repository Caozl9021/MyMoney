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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.example.autobook.data.BillWriter
import com.example.autobook.data.PendingStore
import com.example.autobook.export.CostShare
import com.example.autobook.ui.AutoBookTheme
import com.example.autobook.ui.ConfirmSheet
import com.example.billkit.BillDraft
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
 * 生命周期：一次只允许一张卡；忽略 / 记一笔 / 导入 / 返回键 / 熄屏 都会撤掉窗口。
 * 没有悬浮窗权限时 [canShow] 为 false，调用方应退回 Activity 方案。
 */
object ConfirmOverlay {

    private const val TAG = "AutoBook"

    /** 等主线程把窗口挂上的上限（调用方一般在后台采集线程） */
    private const val SHOW_TIMEOUT_MS = 800L

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
    fun show(context: Context, draft: BillDraft): Boolean {
        val app = context.applicationContext
        if (!canShow(app)) return false
        if (Looper.myLooper() == Looper.getMainLooper()) return showOnMain(app, draft)

        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            ok.set(showOnMain(app, draft))
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
            dismissOnMain()
        } else {
            main.post { dismissOnMain() }
        }
    }

    // ------------------------------------------------------------------

    private fun showOnMain(app: Context, draft: BillDraft): Boolean {
        // 同一时刻只留一张卡
        dismissOnMain()

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val owner = SheetOwner()
        val root = FrameLayout(app).apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
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
            setContent { SheetContent(app, draft) }
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
            wm.addView(root, params)
            appContext = app
            windowManager = wm
            rootView = root
            this.owner = owner
            showing = true
            registerScreenOff(app)
                com.example.autobook.capture.ConfirmDelivery.cancel(app, draft.fingerprint)
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

        if (root != null && wm != null) {
            runCatching { wm.removeView(root) }
        }
        unregisterScreenOff()
        owner?.destroy()
        owner = null
        appContext = null
    }

    @Composable
    private fun SheetContent(app: Context, draft: BillDraft) {
        AutoBookTheme {
            var saving by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf<String?>(null) }
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(durationMillis = 140),
                label = "confirm-sheet",
            )
            Box(Modifier.fillMaxSize().alpha(alpha)) {
                ConfirmSheet(
                    draft = draft,
                    saving = saving,
                    errorMessage = saveError,
                    onSave = { edited ->
                        if (!saving) {
                            saving = true
                            saveError = null
                            BillWriter.save(app, edited, original = draft,
                                onDone = { dismiss() },
                                onError = { saving = false; saveError = it.message ?: "保存失败，请重试" })
                        }
                    },
                    onImport = { edited ->
                        if (!saving) {
                            saving = true
                            saveError = null
                            BillWriter.save(app, edited, original = draft,
                                onDone = { dismiss(); runCatching { CostShare.shareBills(app, listOf(edited), onlyTarget = true) } },
                                onError = { saving = false; saveError = it.message ?: "保存失败，请重试" })
                        }
                    },
                    onDiscard = { if (!saving) { PendingStore.remove(draft.fingerprint); dismiss() } },
                )
            }
        }
    }

    private fun registerScreenOff(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) dismiss()
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
