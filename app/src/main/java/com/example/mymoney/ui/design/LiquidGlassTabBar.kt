package com.example.mymoney.ui.design

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.mymoney.R
import com.example.mymoney.ui.MyMoneyTheme
import com.qmdeve.liquidglass.widget.LiquidGlassView

/**
 * 真·液体玻璃（liquid glass）Tab 栏，Android 13 / API 33+ 才有。
 *
 * 为什么不能用一个 Composable 了事：[LiquidGlassView] 的做法是把**采样源 ViewGroup**
 * 重新录进一个 `RenderNode`，再用 AGSL（`RuntimeShader`）按胶囊形状做像素偏移（折射）
 * 与 R/G/B 分离（色散）。所以采样源必须与玻璃**互为兄弟节点** —— 玻璃若长在被采样的
 * 子树里，就会把自己也录进去，形成无限反馈。
 *
 * 内容采样与 dock 分开：
 * 1. `source`：整页内容（Activity 里的 ComposeView），只当采样源；
 * 2. [LiquidGlassView]：胶囊本体，`bind(source)`，负责真实折射 / 色散 / 霜化；
 * 3. 独立阴影 View 与透明 ComposeView：阴影在玻璃下，图标和标签在玻璃上。
 *
 * 因为库只在 API 33+ 建 impl（更低版本只剩一个透明壳），调用方必须先问
 * [supportsLiquidGlass]，为 false 时退回 Compose 假玻璃 [LiquidTabBar]。
 */
@SuppressLint("ViewConstructor")
class LiquidGlassTabBar(
    activity: ComponentActivity,
    source: ViewGroup,
    private val tabState: MutableIntState,
    private val items: List<TabItem>,
    private val onSelect: (Int) -> Unit,
) : FrameLayout(activity) {

    private val glass = LiquidGlassView(activity)

    // The bundled AAR's setters each post updateParameters(), which records the
    // source and invalidates the glass even for identical values. Apply only changes.
    private var appliedTint: androidx.compose.ui.graphics.Color? = null
    private var appliedTintAlpha: Float? = null

    private val overlay = ComposeView(activity).apply { id = R.id.dock_tabs }

    private val density = resources.displayMetrics.density

    /** 侧边留白与 Compose 兜底共用尺寸。 */
    private val sidePx = (AbSize.tabBarSideMargin.value * density).toInt()

    /** 距屏幕底边 16dp，导航条 inset 在运行时再叠加 */
    private val baseBottomPx = (16f * density).toInt()

    // 双层模糊和下移尾部保留透明绘制空间，胶囊本身尺寸不变。
    private val shadowBleedPx = (18f * density).toInt()

    private val barHeightPx = AbSize.tabBarHeight.value * density

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 绑定采样源。此时 glass 还没 attach，bind 只把 source 存下来，
        // 等 onAttachedToWindow 后由 ensureGlass() 真正初始化。
        glass.bind(source)
        glass.setCornerRadius(barHeightPx / 2f)
        // 参考视频：保留细微边缘折射，避免强烈拉扯与彩虹色散。
        glass.setRefractionHeight(16f * density)
        glass.setRefractionOffset(12f * density)
        glass.setDispersion(0.16f)
        // The AAR accepts physical pixels. Bound the density-adapted radius;
        // set once, without adding per-frame sampling or bitmap work.
        glass.setBlurRadius((8f * density).coerceAtMost(32f))
        glass.setTintColorRed(1f)
        glass.setTintColorGreen(1f)
        glass.setTintColorBlue(1f)
        glass.setTintAlpha(0f)
        // 库自带的拖拽/弹性/触摸特效是给演示卡用的，Tab 栏不需要
        glass.setDraggableEnabled(false)
        glass.setElasticEnabled(false)
        glass.setTouchEffectEnabled(false)
        // 阴影只在自己的软件位图内模糊、挖空，再作为独立兄弟层合成。
        // CLEAR 不接触屏幕画布，硬件 Canvas 也无需支持 BlurMaskFilter。
        val shadowView = CapsuleShadowView(activity, shadowBleedPx)
        addView(shadowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            glass,
            1,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = shadowBleedPx
                rightMargin = shadowBleedPx
                topMargin = shadowBleedPx
                bottomMargin = shadowBleedPx
            },
        )

        overlay.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        overlay.setContent {
            MyMoneyTheme {
                // 浅深主题均只覆薄层中性色，保留背景透光与文字可读性。
                val dark = LocalAbPalette.current == DarkPalette || LocalAbPalette.current.pageBg == DarkPalette.pageBg
                val tint = LocalAbPalette.current.cardBg
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                SideEffect {
                    val height = dockHeightForFontScale(fontScale).value * density
                    val params = layoutParams
                    if (params != null && params.height != height.toInt() + shadowBleedPx * 2) {
                        params.height = height.toInt() + shadowBleedPx * 2
                        layoutParams = params
                        glass.setCornerRadius(height / 2f)
                    }
                    val previousTint = appliedTint
                    if (previousTint?.red != tint.red) glass.setTintColorRed(tint.red)
                    if (previousTint?.green != tint.green) glass.setTintColorGreen(tint.green)
                    if (previousTint?.blue != tint.blue) glass.setTintColorBlue(tint.blue)
                    appliedTint = tint
                    val alpha = if (dark) 0.28f else 0.10f
                    if (appliedTintAlpha != alpha) {
                        glass.setTintAlpha(alpha)
                        appliedTintAlpha = alpha
                    }
                }
                LiquidTabCells(
                    items = items,
                    selected = tabState.intValue,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = shadowBleedPx
                rightMargin = shadowBleedPx
                topMargin = shadowBleedPx
                bottomMargin = shadowBleedPx
            },
        )

        // 容器 = 胶囊 + 四周 bleed。玻璃与图标再用 bleed 的 margin 缩回到胶囊原位，
        // 所以**视觉上胶囊尺寸完全不变**，多出来的只是给阴影用的透明边距。
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            barHeightPx.toInt() + shadowBleedPx * 2,
            Gravity.BOTTOM,
        ).apply {
            leftMargin = sidePx - shadowBleedPx
            rightMargin = sidePx - shadowBleedPx
            bottomMargin = baseBottomPx - shadowBleedPx
        }

        // 导航条 inset 只能运行时取：手势导航约 24dp，三键导航更高。
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (v.layoutParams as? LayoutParams)?.let { lp ->
                val target = baseBottomPx - shadowBleedPx + navBottom
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    v.layoutParams = lp
                }
            }
            insets
        }
    }
}

/** 设备是否支持真玻璃（库只在 API 33+ 建 impl）。 */
fun supportsLiquidGlass(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 构造真玻璃 Tab 栏；API < 33 返回 null，调用方自己退回 [LiquidTabBar]。
 */
fun createLiquidGlassTabBar(
    activity: ComponentActivity,
    source: ViewGroup,
    tabState: MutableIntState,
    items: List<TabItem>,
    onSelect: (Int) -> Unit,
): LiquidGlassTabBar? =
    if (supportsLiquidGlass()) LiquidGlassTabBar(activity, source, tabState, items, onSelect) else null

/** 按尺寸缓存阴影；只有尺寸变化时重画软件位图，切 Tab 不分配位图。 */
private class CapsuleShadowView(context: Context, private val bleed: Int) : View(context) {
    private var shadow: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadow = null
        if (w <= bleed * 2 || h <= bleed * 2) return
        val density = resources.displayMetrics.density
        shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val capsule = RectF(bleed.toFloat(), bleed.toFloat(), (w - bleed).toFloat(), (h - bleed).toFloat())
            val radius = capsule.height() / 2f
            val blurred = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(22, 0, 0, 0)
                maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
            }
            val shifted = RectF(capsule).apply { offset(0f, 2f * density) }
            canvas.drawRoundRect(shifted, radius, radius, blurred)
            // A wider low-opacity penumbra and a soft close shadow share this cache.
            blurred.color = Color.argb(12, 0, 0, 0)
            blurred.maskFilter = BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL)
            shifted.offset(0f, 1f * density)
            canvas.drawRoundRect(shifted, radius, radius, blurred)
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            canvas.drawRoundRect(capsule, radius, radius, clear)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shadow?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
    }
}
