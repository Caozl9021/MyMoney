package com.example.autobook.ui.design

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 跟随系统强调色（Material You / Android 12+）。
 *
 * Android 12 起系统会从壁纸里抽出一组 tonal palette，通过
 * `android.R.color.system_accent1_*` 暴露出来。这里取 accent1 那一组，
 * 只替换我们自己的 `accent` / `onAccent` / 玻璃描边，
 * 中性色（页面底、卡片、分隔线）保持不动 —— 中性色一旦跟着壁纸走，
 * 浅色界面的层级就没了。
 *
 * 取不到（API < 31、OEM 没实现、或资源是空桩）就原样返回，退回品牌薄荷绿。
 */
object SystemAccent {

    /**
     * 系统强调色的 tonal 快照。
     *
     * 注意：Android framework 里的档位是 **0 / 10 / 50 / 100 / 200 … 900 / 1000**，
     * 不是 Material3 那套 10/20/40/80/90/95。用错名字 `getIdentifier` 会返回 0，
     * 整个功能静默失效（踩过）。
     */
    private data class Tones(
        @ColorInt val c0: Int,
        @ColorInt val c50: Int,
        @ColorInt val c100: Int,
        @ColorInt val c200: Int,
        @ColorInt val c400: Int,
        @ColorInt val c700: Int,
    )

    private val names = listOf(
        "system_accent1_0",
        "system_accent1_50",
        "system_accent1_100",
        "system_accent1_200",
        "system_accent1_400",
        "system_accent1_700",
    )

    @Volatile
    private var cached: Tones? = null

    @Volatile
    private var cacheKey: String? = null

    /**
     * 把品牌色换成系统强调色。
     * @param dark 当前是否深色主题
     */
    fun apply(context: Context, palette: AbPalette, dark: Boolean): AbPalette {
        val tones = tones(context.applicationContext) ?: return palette
        // 浅色用 400（够深、白底可读），深色用 200（够亮、黑底可读）
        val accent = Color(if (dark) tones.c200 else tones.c400)
        // onAccent 一律用 0 档（白），压在 accent 上都够对比
        val onAccent = Color(tones.c0)
        // 玻璃上沿描边掺一点 accent 的浅档，让玻璃边缘也带系统色
        val strokeTint = Color(if (dark) tones.c700 else tones.c100)
        return palette.copy(
            accent = accent,
            onAccent = onAccent,
            glassStrokeTop = lerp(palette.glassStrokeTop, strokeTint, 0.30f),
        )
    }

    /** 系统是否有可用的 Material You 调色（API 31+ 且资源可读）。 */
    fun available(context: Context): Boolean = tones(context.applicationContext) != null

    /** 当前取到的强调色（调试/预览用），取不到返回 null。 */
    fun accentColor(context: Context): Color? =
        tones(context.applicationContext)?.let { Color(it.c400) }

    private fun tones(context: Context): Tones? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val key = Build.VERSION.RELEASE + "/" + context.resources.configuration.uiMode
        cached?.let { if (cacheKey == key) return it }

        val res = context.resources
        val ids = names.map { res.getIdentifier(it, "color", "android") }
        if (ids.any { it == 0 }) return null
        val values = runCatching { ids.map { res.getColor(it, null) } }.getOrNull() ?: return null
        // 全同色说明资源是空桩（个别 ROM 会这样），当作不可用
        if (values.all { it == values[0] }) return null

        val t = Tones(values[0], values[1], values[2], values[3], values[4], values[5])
        cached = t
        cacheKey = key
        return t
    }

    /** 色值是否足够饱和（灰阶不算强调色），调试/单测用。 */
    fun isChromatic(@ColorInt color: Int): Boolean {
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        return maxOf(r, g, b) - minOf(r, g, b) >= 24
    }
}
