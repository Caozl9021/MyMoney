package io.github.mymoney.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 线性图标集（官方 Lucide Icons 路径：24×24 viewBox、2.0dp 描边、圆头圆角）。
 *
 * 与底部悬浮 Dock（`AnimatedTabIcons.kt`，Lucide Animated）同源，保证顶栏、列表、
 * 分类徽章、设置页到底栏全 App 只有一套几何语言；不引 `material-icons-extended`。
 */
internal fun lucideIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }
    }.build()

object AbIcons {

    private fun line(name: String, vararg paths: String): ImageVector = lucideIcon(name, *paths)

    val ChevronRight = line("chevronRight", "M9 18l6-6-6-6")
    val ChevronLeft = line("chevronLeft", "M15 18l-6-6 6-6")
    val ChevronUp = line("chevronUp", "M18 15l-6-6-6 6")
    val ChevronDown = line("chevronDown", "M6 9l6 6 6-6")
    val Plus = line("plus", "M5 12h14", "M12 5v14")
    val Close = line("close", "M18 6L6 18", "M6 6l12 12")
    val Check = line("check", "M20 6L9 17l-5-5")

    val Home = line(
        "home",
        "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
    )
    val List = line("list", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    val Chart = line("chart", "M18 20V10", "M12 20V4", "M6 20v-6")
    val Info = line(
        "info",
        "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
        "M12 16v-4",
        "M12 8h.01",
    )
    val Settings = line(
        "settings",
        "M12 2a10 10 0 0 0 0 20c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-1 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8z",
        "M6.5 11.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        "M9.5 7.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        "M14.5 7.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        "M17.5 11.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
    )
    val Trash = line(
        "trash",
        "M3 6h18",
        "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
        "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
        "M10 11v6",
        "M14 11v6",
    )
    val Export = line(
        "export",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "M17 8l-5-5-5 5",
        "M12 3v12",
    )
    val Terminal = line("terminal", "M4 17l6-6-6-6", "M12 19h8")
    val Ocr = line(
        "ocr",
        "M3 7V5a2 2 0 0 1 2-2h2",
        "M17 3h2a2 2 0 0 1 2 2v2",
        "M21 17v2a2 2 0 0 1-2 2h-2",
        "M7 21H5a2 2 0 0 1-2-2v-2",
        "M7 12h10",
    )
    val Shield = line(
        "shield",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
    )
    val Tag = line(
        "tag",
        "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z",
        "M7.5 7.5h.01",
    )
    val Wallet = line(
        "wallet",
        "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",
        "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",
    )
    /** 支付账户 / 信用卡 */
    val Card = line("card", "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M2 10h20")
    /** 储蓄卡 / 银行 */
    val Bank = line(
        "bank",
        "M3 22h18",
        "M6 18v-7",
        "M10 18v-7",
        "M14 18v-7",
        "M18 18v-7",
        "M12 2L20 7H4z",
    )
    val Clock = line("clock", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z", "M12 6v6l4 2")
    val Store = line(
        "store",
        "M2 7l2.41-4.9A2 2 0 0 1 6.2 1h11.6a2 2 0 0 1 1.79 1.1L22 7",
        "M4 7v13a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7",
        "M9 22v-8h6v8",
        "M2 7h20",
    )
    val Pencil = line("pencil", "M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z", "M15 5l4 4")
    val Receipt = line(
        "receipt",
        "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z",
        "M14 8H8",
        "M16 12H8",
        "M13 16H8",
    )
    val Note = line(
        "note",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8",
        "M16 13H8",
        "M16 17H8",
    )
    val Accessibility = line(
        "accessibility",
        "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z",
    )
    val Layers = line(
        "layers",
        "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
        "M22 12.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65",
        "M22 17.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65",
    )

    /** 支出/收入方向箭头 */
    val ArrowUpRight = line("arrowUpRight", "M7 7h10v10", "M7 17L17 7")
    val ArrowDownLeft = line("arrowDownLeft", "M17 17H7V7", "M17 7L7 17")

    val Search = line("search", "M11 3a8 8 0 1 0 0 16 8 8 0 0 0 0-16z", "M21 21l-4.3-4.3")

    val Filter = line(
        "filter",
        "M21 4h-7",
        "M10 4H3",
        "M21 12h-9",
        "M8 12H3",
        "M21 20h-5",
        "M12 20H3",
        "M14 2v4",
        "M8 10v4",
        "M16 18v4",
    )

    /** 资产页「隐藏金额 / 显示金额」 */
    val Eye = line(
        "eye",
        "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
        "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
    )
    val EyeOff = line(
        "eyeOff",
        "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
        "M14.084 14.158a3 3 0 0 1-4.242-4.242",
        "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
        "M2 2l20 20",
    )
}
