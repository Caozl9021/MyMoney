package io.github.caozl9021.mymoney.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.caozl9021.mymoney.ui.design.*

/** Navigation rows share the same neutral cards and spacing as the statistics screen. */
internal data class SettingsLink(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
internal fun SettingsNavigationGroup(title: String, links: List<SettingsLink>) {
    Column(verticalArrangement = Arrangement.spacedBy(AbLayout.headingGap)) {
        GroupHeader(title)
        InsetGroup {
            links.forEachIndexed { index, link ->
                ListRow(title = link.title, subtitle = link.subtitle,
                    leading = { SettingsIconBadge(link.title, link.icon) },
                    dividerAbove = index > 0, chevron = true, onClick = link.onClick)
            }
        }
    }
}

@Composable
internal fun SettingsMenuPage(
    title: String,
    onBack: () -> Unit,
    groups: List<Pair<String, List<SettingsLink>>>,
    header: (@Composable () -> Unit)? = null,
) {
    SubPageScaffold(title = title, onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap)) {
            if (header != null) item { Column(verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) { header() } }
            groups.forEach { (label, links) ->
                item { SettingsNavigationGroup(label, links) }
            }
        }
    }
}

@Composable
internal fun SettingsContentPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SubPageScaffold(title = title, onBack = onBack) { padding ->
        io.github.caozl9021.mymoney.ui.design.SectionColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = AbSpacing.page)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), content = content)
    }
}
