package io.github.mymoney.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog

/** Application-owned dialog content: colors, typography and actions use the page tokens. */
@Composable
internal fun AppDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(AbRadius.card), color = LocalAbPalette.current.cardBg) {
            Column(Modifier.widthIn(max = androidx.compose.ui.unit.Dp(480f)).imePadding()
                .verticalScroll(rememberScrollState()).padding(AbSpacing.l),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                Text(title, style = AbType.navTitle, color = LocalAbPalette.current.label)
                content()
            }
        }
    }
}
