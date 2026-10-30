package io.github.caozl9021.mymoney.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个 1×1 的透明悬浮窗，只用来"持有"后台启动 Activity 的豁免。
 *
 * 为什么需要它：Android 10+ 限制后台启动 Activity（BAL）。无障碍服务被系统绑定时
 * 天然豁免，但 root 轮询跑在普通后台进程里，`startActivity` 会被静默丢弃。
 * 系统对**正在展示悬浮窗**、且持有 SYSTEM_ALERT_WINDOW 的应用放行，
 * 所以弹确认页前先挂一个几乎看不见的锚点，弹完立刻撤掉。
 *
 * 注意：**没有悬浮窗权限时，真正的确认卡会走 [ConfirmOverlay] 而不是 Activity**，
 * 锚点只是"有权限但悬浮卡挂不上"时的二线方案。
 *
 * 这里刻意不承载任何 UI：确认卡是 [io.github.caozl9021.mymoney.ui.ConfirmSheet]。
 */
object OverlayAnchor {

    private const val TAG = "MyMoney"

    /** 锚点保留时长：足够让 startActivity 过检即可，别久留 */
    private const val HOLD_MS = 3_000L

    /** 后台线程等主线程挂载的上限 */
    private const val HOLD_TIMEOUT_MS = 600L

    /**
     * 窗口挂上后给 WMS 一点时间把"该应用可见"算出来。
     * 少了这一步，紧接着的 startActivity 仍可能被判定为后台启动。
     */
    private const val SETTLE_MS = 90L

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var releaseTask: Runnable? = null

    fun canShow(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    /**
     * 挂上锚点。返回 false 表示没有悬浮窗权限或挂载失败
     * （此时后台启动大概率会被拦，调用方应改用 [ConfirmOverlay] 或落队列 + 通知）。
     *
     * 可以在任意线程调用：`WindowManager.addView` 必须在有 Looper 的主线程执行，
     * 否则会抛 "Can't create handler inside thread that has not called Looper.prepare()"。
     */
    fun hold(context: Context): Boolean {
        val app = context.applicationContext
        if (!canShow(app)) return false

        if (Looper.myLooper() == Looper.getMainLooper()) return holdOnMain(app)

        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            ok.set(holdOnMain(app))
            latch.countDown()
        }
        return try {
            val attached = latch.await(HOLD_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok.get()
            if (attached) Thread.sleep(SETTLE_MS)
            attached
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun release() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseOnMain()
        } else {
            main.post { releaseOnMain() }
        }
    }

    // ------------------------------------------------------------------

    private fun holdOnMain(app: Context): Boolean {
        releaseOnMain()

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val anchor = View(app).apply {
            // 完全透明、不接收任何输入，用户看不到也点不到
            setBackgroundColor(0x00000000)
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        return try {
            wm.addView(anchor, params)
            windowManager = wm
            view = anchor
            val task = Runnable { releaseOnMain() }
            releaseTask = task
            main.postDelayed(task, HOLD_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗锚点挂载失败: ${e.message}")
            false
        }
    }

    private fun releaseOnMain() {
        releaseTask?.let { main.removeCallbacks(it) }
        releaseTask = null
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        windowManager = null
    }
}
