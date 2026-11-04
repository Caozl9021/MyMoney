package io.github.mymoney.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.mymoney.ui.design.*

/** Identical symbol, baseline and text at the source, overlay and editable target. */
@Composable
internal fun BillAmountDisplay(cents: Long, type: String, modifier: Modifier = Modifier, hidden: Boolean = false,
    onBounds: ((Rect?) -> Unit)? = null, amountFontSize: TextUnit? = null, onFontSize: ((TextUnit) -> Unit)? = null) {
    val p = LocalAbPalette.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val boundsChanged by rememberUpdatedState(onBounds)
    DisposableEffect(Unit) { onDispose { boundsChanged?.invoke(null) } }
    BoxWithConstraints(modifier) {
        val preferred = amountFontSize ?: (AbType.amountEntry.fontSize / density.fontScale.coerceAtLeast(1f))
        val text = formatForInput(cents)
        val symbolWidth = measurer.measure("¥", AbType.amountTitle).size.width
        val available = with(density) { maxWidth.toPx() - 6.dp.toPx() } - symbolWidth
        val width = measurer.measure(text, AbType.amountEntry.copy(fontSize = preferred), maxLines = 1, softWrap = false).size.width
        val fitted = preferred * (available / width.coerceAtLeast(1)).coerceIn(0.1f, 1f)
        SideEffect { onFontSize?.invoke(fitted) }
        Row(Modifier.onGloballyPositioned { boundsChanged?.invoke(it.boundsInRoot()) }
            .graphicsLayer { alpha = if (hidden) 0f else 1f }, verticalAlignment = Alignment.CenterVertically) {
            Text("¥", style = AbType.amountTitle, color = amountTint(p, type), modifier = Modifier.padding(end = 6.dp))
            Text(text, style = AbType.amountEntry.copy(fontSize = fitted), color = amountTint(p, type), maxLines = 1)
        }
    }
}
