package io.github.mymoney.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.mymoney.ui.design.AppDialog
import io.github.mymoney.ui.design.AppDialogActions
import io.github.mymoney.ui.design.LocalAbPalette

/** Shared, app-styled confirmation; dismissal always means continue editing. */
@Composable
internal fun DiscardChangesDialog(onContinue: () -> Unit, onDiscard: () -> Unit) {
    val palette = LocalAbPalette.current
    AppDialog(title = "放弃本次修改？", onDismiss = onContinue) {
        Text("尚未保存的内容将被丢弃。", color = palette.secondaryLabel)
        AppDialogActions(
            confirmLabel = "放弃修改",
            onConfirm = onDiscard,
            dismissLabel = "继续编辑",
            onDismiss = onContinue,
            destructive = true,
        )
    }
}
