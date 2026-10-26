package com.example.autobook.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 线性图标集（SF Symbols 观感：2dp 描边、圆头圆角）。
 *
 * 自绘而不是引 `material-icons-extended`：那个库会给 debug 包塞进几千个图标、
 * 编译时间涨一倍，而我们只需要十几个。每个图标都是一条或几条 stroke 路径。
 */
object AbIcons {

    private fun line(name: String, vararg paths: String): ImageVector =
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
                    strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    fill = null,
                )
            }
        }.build()

    private fun filled(name: String, vararg paths: String): ImageVector =
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
                    fill = SolidColor(Color.Black),
                )
            }
        }.build()

    val ChevronRight = line("chevronRight", "M9 5l7 7-7 7")
    val ChevronLeft = line("chevronLeft", "M15 5l-7 7 7 7")
    val ChevronUp = line("chevronUp", "M5 15l7-7 7 7")
    val ChevronDown = line("chevronDown", "M5 9l7 7 7-7")
    val Plus = line("plus", "M12 5v14", "M5 12h14")
    val Close = line("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11")
    val Check = line("check", "M5 12.5l4.5 4.5L19 6.5")

    val Home = line(
        "home",
        "M3.5 10.2L12 3.4l8.5 6.8V19a1.6 1.6 0 0 1-1.6 1.6h-4.4v-6.2H9.5v6.2H5.1A1.6 1.6 0 0 1 3.5 19z",
    )
    val List = line("list", "M4.5 4.5h15v15h-15z", "M8 9h8", "M8 12.5h8", "M8 16h5")
    val Chart = line("chart", "M4 20V11.5", "M9.5 20V4.5", "M15 20v-6.5", "M20.5 20V8", "M2.5 20h19")
    val Settings = line(
        "settings",
        "M4 7.5h5.5", "M14 7.5h6", "M4 12h9.5", "M18 12h2", "M4 16.5h3.5", "M12 16.5h8",
        "M12 5.6a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 0 0 0-3.8",
        "M16 10.1a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 0 0 0-3.8",
        "M10 14.6a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 0 0 0-3.8",
    )
    val Trash = line(
        "trash",
        "M4.5 7h15", "M9.5 7V4.4h5V7", "M6.5 7l.9 12.6h9.2L17.5 7", "M10 10.5v6", "M14 10.5v6",
    )
    val Export = line("export", "M12 3.8v10.7", "M7.8 10.8L12 15l4.2-4.2", "M4 18.5v1.7h16v-1.7")
    val Terminal = line("terminal", "M3.6 5h16.8v14H3.6z", "M7.5 10l2.6 2.5L7.5 15", "M13 15h4")
    val Ocr = line(
        "ocr",
        "M3.4 5.2h17.2v13.6H3.4z", "M3.4 16l4.8-4.8 4 4 3-3 5.6 5.4",
        "M9 9.9a1.15 1.15 0 1 0 0-2.3 1.15 1.15 0 0 0 0 2.3",
    )
    val Shield = line("shield", "M12 3l8 2.8v6c0 4.9-3.4 8-8 9.2-4.6-1.2-8-4.3-8-9.2v-6z")
    val Tag = line("tag", "M4 12.2l8-8h7.8V12l-8 8z", "M15.6 8.4h.02")
    val Wallet = line("wallet", "M3.4 7.4h17.2v12H3.4z", "M3.4 7.4L6.6 4h10.8l3.2 3.4", "M16 13h2")
    /** 支付账户 / 信用卡 */
    val Card = line("card", "M3.2 6.8h17.6v10.4H3.2z", "M3.2 10.6h17.6")
    /** 储蓄卡 / 银行 */
    val Bank = line(
        "bank",
        "M3.4 9.4L12 4.4l8.6 5",
        "M5.6 9.4V19", "M9.9 9.4V19", "M14.1 9.4V19", "M18.4 9.4V19",
        "M3.2 19.9h17.6",
    )
    val Clock = line("clock", "M12 3.2a8.8 8.8 0 1 0 0 17.6 8.8 8.8 0 0 0 0-17.6", "M12 7.4v5l3.4 2")
    val Store = line("store", "M4 9.2l1.6-5h12.8L20 9.2", "M4 9.2h16V20H4z", "M9.2 20v-6h5.6v6")
    val Note = line("note", "M4.6 4.6h14.8v14.8H4.6z", "M8 9h8", "M8 12.5h8", "M8 16h4")
    val Accessibility = line(
        "accessibility",
        "M12 5.4a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2",
        "M4.6 8.4h14.8", "M12 8.4v5.2", "M8.2 20.4l3.8-6.8 3.8 6.8",
    )
    val Layers = line("layers", "M12 3.4l8.4 4.6L12 12.6 3.6 8z", "M3.6 12.6L12 17.2l8.4-4.6", "M3.6 16.6L12 21.2l8.4-4.6")

    /** 支出/收入方向箭头 */
    val ArrowUpRight = line("arrowUpRight", "M7 17L17 7", "M8.5 7H17v8.5")
    val ArrowDownLeft = line("arrowDownLeft", "M17 7L7 17", "M15.5 17H7V8.5")

    val Search = line("search", "M10.8 4a6.8 6.8 0 1 0 0 13.6 6.8 6.8 0 0 0 0-13.6", "M16 16l4 4")

    val Filter = line("filter", "M3.6 6.4h16.8", "M6.6 12h10.8", "M9.6 17.6h4.8")

    /** 资产页「隐藏金额 / 显示金额」 */
    val Eye = line(
        "eye",
        "M2.6 12S6.2 5.8 12 5.8 21.4 12 21.4 12 17.8 18.2 12 18.2 2.6 12 2.6 12z",
        "M12 9.2a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6",
    )
    val EyeOff = line(
        "eyeOff",
        "M2.6 12S6.2 5.8 12 5.8c1.7 0 3.2.5 4.5 1.2",
        "M21.4 12s-3.6 6.2-9.4 6.2c-1.7 0-3.2-.5-4.5-1.2",
        "M4 4l16 16",
    )
}
