package com.example.autobook.ui

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.autobook.R
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.data.AppDb
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbMotion
import com.example.autobook.ui.design.AbSize
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.LiquidTabBar
import com.example.autobook.ui.design.TabItem
import com.example.autobook.ui.design.createLiquidGlassTabBar
import kotlinx.coroutines.launch

/**
 * 主界面。
 *
 * 这里**刻意不用** `setContent` 单根 ComposeView，而是手搭一个 FrameLayout：
 *
 * ```
 * FrameLayout
 *  ├─ ComposeView A（整页内容，作为采样源）
 *  └─ LiquidGlassTabBar（真玻璃胶囊 + 只画图标的 ComposeView B）   ← Android 13+
 *     └─ 或 ComposeView C（Compose 假玻璃兜底）                    ← Android 12 及以下
 * ```
 *
 * 原因：`LiquidGlassView` 的折射是把采样源 ViewGroup 重新录进 RenderNode，再按胶囊
 * 形状做像素偏移 + 色散。采样源与玻璃必须是兄弟节点；玻璃长在采样源内部会自我反馈
 * （详见 `ui/design/LiquidGlassTabBar.kt` 的类注释）。
 */
class MainActivity : ComponentActivity() {

    /** Tab 索引提到 Activity 层级：内容 ComposeView 与玻璃上的图标 ComposeView 共享它 */
    private val tabState = mutableIntStateOf(0)
    private val requestAdd = mutableIntStateOf(0)
    private val editorVisible = mutableStateOf(false)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "com.example.autobook.action.ADD_BILL") {
            tabState.intValue = 1
            requestAdd.intValue++
            // Consume the command while retaining extras identifying the launch source.
            intent.action = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏到边：状态栏 / 导航栏都透明，页面自己用 insets 让位。
        // 不这么做（尤其 Android 15 强制 edge-to-edge 之后）系统会在底部导航栏位置
        // 画一层对比度 scrim，看起来就像一条旧版 NavigationBar 的底色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        CaptureModeManager.init(this)
        tabState.intValue = savedInstanceState?.getInt("selectedTab", 0)?.coerceIn(tabs.indices) ?: 0
        requestAdd.intValue = savedInstanceState?.getInt("requestAdd", 0) ?: 0
        if (savedInstanceState == null && intent.action == "com.example.autobook.action.ADD_BILL") {
            tabState.intValue = 1
            requestAdd.intValue++
            intent.action = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val select: (Int) -> Unit = { next ->
            if (next != tabState.intValue) tabState.intValue = next
        }

        val root = FrameLayout(this)

        val contentView = ComposeView(this).apply {
            id = R.id.main_content
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent { AutoBookTheme { AppContent(tabState = tabState, requestAdd = requestAdd.intValue, onEditorVisibilityChanged = { editorVisible.value = it }) } }
        }
        root.addView(
            contentView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val glassBar = createLiquidGlassTabBar(
            activity = this,
            source = contentView,
            tabState = tabState,
            items = tabs,
            onSelect = select,
        )
        val dockView: View
        if (glassBar != null) {
            dockView = glassBar
            // 高度 / 左右留白共用 AbSize，底部 16dp + 导航条 inset 在构造函数里设好
            root.addView(glassBar)
        } else {
            // API < 33：库内部不建 impl（只剩透明壳），退回原来的 Compose 假玻璃。
            // 关键：这一层只能占胶囊那么大。若铺满全屏，ComposeView 会把整屏的触摸
            // 都吃掉，页面就点不动了。
            val density = resources.displayMetrics.density
            val barHeight = (AbSize.tabBarHeight.value * density).toInt()
            val side = (AbSize.tabBarSideMargin.value * density).toInt()
            val bottom = (16f * density).toInt()

            val fallbackBar = ComposeView(this).apply {
                id = R.id.dock_tabs
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    AutoBookTheme {
                        val adaptiveHeight = com.example.autobook.ui.design.dockHeight()
                        val host = this@apply
                        androidx.compose.runtime.SideEffect {
                            val params = host.layoutParams
                            val target = (adaptiveHeight.value * density).toInt()
                            if (params != null && params.height != target) {
                                params.height = target
                                host.layoutParams = params
                            }
                        }
                        LiquidTabBar(
                            items = tabs,
                            selected = tabState.intValue,
                            onSelect = select,
                        )
                    }
                }
            }
            dockView = fallbackBar
            // 假玻璃兜底的胶囊阴影：View 级 elevation（ComposeView 自己画的内容
            // 不会带投影，所以挂在外层 FrameLayout 上）。真玻璃则使用独立的位图阴影层。
            fallbackBar.elevation = 5f * density
            fallbackBar.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    val radius = view.height / 2f
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            fallbackBar.clipToOutline = false
            root.addView(
                fallbackBar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    barHeight,
                    Gravity.BOTTOM,
                ).apply {
                    leftMargin = side
                    rightMargin = side
                    bottomMargin = bottom
                },
            )
            ViewCompat.setOnApplyWindowInsetsListener(fallbackBar) { v, insets ->
                val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    val target = bottom + navBottom
                    if (lp.bottomMargin != target) {
                        lp.bottomMargin = target
                        v.layoutParams = lp
                    }
                }
                insets
            }
        }

        // The choice screen lives in the content source; hide the sibling native dock as well.
        // No extra full-screen overlay is retained after choosing a mode.
        dockView.visibility = if (CaptureModeManager.state.value.mode == null) View.GONE else View.VISIBLE
        lifecycleScope.launch { androidx.compose.runtime.snapshotFlow { editorVisible.value }.collect { visible -> dockView.visibility = if (visible || CaptureModeManager.state.value.mode == null) View.GONE else View.VISIBLE } }
        setContentView(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureModeManager.state.collect { state ->
                    dockView.visibility = if (state.mode == null || editorVisible.value) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedTab", tabState.intValue)
        outState.putInt("requestAdd", requestAdd.intValue)
        super.onSaveInstanceState(outState)
    }
}

private val tabs = listOf(
    TabItem("首页", AbIcons.Home),
    TabItem("明细", AbIcons.List),
    TabItem("统计", AbIcons.Chart),
    TabItem("设置", AbIcons.Settings),
)

@Composable
private fun AppContent(tabState: MutableIntState, requestAdd: Int, onEditorVisibilityChanged: (Boolean) -> Unit) {
    // Outgoing AnimatedContent pages may dispose after the incoming editor opens.
    // Track each page independently so an old page cannot unhide the sibling dock.
    val editors = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    val editorChanged: (Int, Boolean) -> Unit = { page, visible ->
        editors[page] = visible
        onEditorVisibilityChanged(editors.values.any { it })
    }
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val reduceMotion = com.example.autobook.ui.design.LocalReduceMotion.current
    val tab = tabState.intValue
    val tabStates = rememberSaveableStateHolder()
    val captureState by CaptureModeManager.state.collectAsStateWithLifecycle()

    // 上一次切换方向：向右点 = 新页从右进；向左点 = 新页从左进。
    var forward by remember { mutableStateOf(true) }
    var lastTab by remember { mutableStateOf(tab) }
    if (tab != lastTab) {
        forward = tab > lastTab
        lastTab = tab
    }

    val bills by remember { AppDb.get(context).bills().observeRecent() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 返回 / 侧滑：非首页 → 回首页；已在首页 → 交给系统退出（符合 Android 惯例）
    BackHandler(enabled = captureState.mode != null && tab != 0) { tabState.intValue = 0 }

    if (captureState.mode == null) {
        CaptureModeScreen()
        return
    }

    Box(Modifier.fillMaxSize().background(p.pageBg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                val spec = tween<androidx.compose.ui.unit.IntOffset>(
                    durationMillis = if (reduceMotion) 0 else AbMotion.normal,
                    easing = FastOutSlowInEasing,
                )
                (slideInHorizontally(spec) { w -> dir * w / 6 } +
                    fadeIn(tween(if (reduceMotion) 0 else AbMotion.normal))) togetherWith
                    (slideOutHorizontally(spec) { w -> -dir * w / 6 } +
                        fadeOut(tween(if (reduceMotion) 0 else AbMotion.fast)))
            },
            label = "tabSwitch",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            tabStates.SaveableStateProvider(current) {
                when (current) {
                    0 -> HomeScreen(
                        bills = bills,
                        onEditorVisibilityChanged = { editorChanged(0, it) },
                        onSeeAll = { tabState.intValue = 1 },
                        onOpenSettings = { tabState.intValue = 3 },
                    )
                    1 -> BillsScreen(bills = bills, requestAdd = requestAdd, onEditorVisibilityChanged = { editorChanged(1, it) })
                    2 -> StatsScreen(bills = bills)
                    else -> SettingsScreen()
                }
            }
        }
    }
}
