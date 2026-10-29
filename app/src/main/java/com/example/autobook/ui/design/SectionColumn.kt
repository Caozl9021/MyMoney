package com.example.autobook.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

internal enum class SectionRole { HEADER, CARD, FOOTER, ACTION }

/** Spacing is decided once from the relationship, rather than nested spacers/padding. */
@Composable
fun SectionColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        Layout(content = { content() }, modifier = Modifier.fillMaxWidth()) { children, constraints ->
            val measured = children.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
            val positions = IntArray(children.size)
            var y = 0
            children.forEachIndexed { i, child ->
                val role = child.layoutId
                val previous = children.getOrNull(i - 1)?.layoutId
                val gap = when {
                    i == 0 -> 0.dp
                    role == SectionRole.HEADER -> AbLayout.groupGap
                    previous == SectionRole.HEADER -> AbLayout.headingGap
                    role == SectionRole.FOOTER -> AbLayout.captionGap
                    role == SectionRole.ACTION -> AbLayout.actionGap
                    role == SectionRole.CARD && previous == SectionRole.CARD -> AbLayout.cardGap
                    else -> AbLayout.fieldGap
                }
                y += gap.roundToPx()
                positions[i] = y
                y += measured[i].height
            }
            layout(constraints.maxWidth, y.coerceAtLeast(constraints.minHeight)) {
                measured.forEachIndexed { i, child -> child.placeRelative(0, positions[i]) }
            }
        }
    }
}
