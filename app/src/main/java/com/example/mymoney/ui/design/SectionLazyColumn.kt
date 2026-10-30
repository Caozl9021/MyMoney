package com.example.mymoney.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The lazy counterpart of SectionColumn: spacing belongs to item relationships. */
@Composable
fun SectionLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(modifier = modifier, state = state, contentPadding = contentPadding,
        verticalArrangement = Arrangement.Top) {
        SectionLazyScope(this).content()
    }
}

private class SectionLazyScope(private val target: LazyListScope) : LazyListScope by target {
    private var previous: SectionRole? = null
    private var hasItems = false
    private fun gap(role: SectionRole): Dp = when {
        !hasItems -> 0.dp
        role == SectionRole.HEADER -> AbLayout.groupGap
        previous == SectionRole.HEADER -> AbLayout.headingGap
        role == SectionRole.FOOTER -> AbLayout.captionGap
        role == SectionRole.ACTION -> AbLayout.actionGap
        role == SectionRole.CARD && previous == SectionRole.CARD -> AbLayout.cardGap
        else -> AbLayout.fieldGap
    }
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val role = contentType as? SectionRole ?: when {
            key?.toString()?.let { it.contains("hdr") || it.contains("header") } == true -> SectionRole.HEADER
            key == "footer" -> SectionRole.FOOTER
            else -> SectionRole.CARD
        }
        val space = gap(role)
        target.item(key, contentType) {
            val itemScope = this
            Box(Modifier.fillMaxWidth().padding(top = space)) { content(itemScope) }
        }
        previous = role; hasItems = true
    }
    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
        val initial = gap(SectionRole.CARD)
        target.items(count, key, contentType) { index ->
            val itemScope = this
            Box(Modifier.fillMaxWidth().padding(top = if (index == 0) initial else AbLayout.cardGap)) { itemContent(itemScope, index) }
        }
        if (count > 0) { previous = SectionRole.CARD; hasItems = true }
    }
}
