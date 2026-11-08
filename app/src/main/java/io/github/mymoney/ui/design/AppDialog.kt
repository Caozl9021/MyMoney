package io.github.mymoney.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Application-owned dialog content. All page dialogs should use this family instead of
 * Material3 AlertDialog so width, typography, actions and busy semantics stay consistent.
 */
@Composable
internal fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = AbSpacing.xxl)
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(AbRadius.card),
            color = LocalAbPalette.current.cardBg,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(AbSpacing.xxl)
                    .semantics { paneTitle = title },
                verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
            ) {
                Text(title, style = AbType.navTitle, color = LocalAbPalette.current.label)
                content()
            }
        }
    }
}

/** Shared dialog action row: cancel on the left, confirm on the right, equal width. */
@Composable
internal fun AppDialogActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = "取消",
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    busyLabel: String = "处理中…",
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.m),
    ) {
        if (dismissLabel != null && onDismiss != null) {
            SecondaryButton(
                dismissLabel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
        if (destructive) {
            DestructiveButton(
                confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                busy = busy,
                busyLabel = busyLabel,
            )
        } else {
            PrimaryButton(
                confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                busy = busy,
                busyLabel = busyLabel,
            )
        }
    }
}

/** Plain confirmation/info dialog with a message body. */
@Composable
internal fun AppAlertDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit,
    dismissLabel: String? = "取消",
    destructive: Boolean = false,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    busyLabel: String = "处理中…",
) {
    AppDialog(title, onDismiss = { if (!busy) onDismiss() }) {
        Text(message, style = AbType.body, color = LocalAbPalette.current.secondaryLabel)
        AppDialogActions(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            dismissLabel = dismissLabel,
            onDismiss = onDismiss,
            destructive = destructive,
            busy = busy,
            confirmEnabled = confirmEnabled,
            busyLabel = busyLabel,
        )
    }
}

/** A labelled option used by [AppChoiceDialog]. */
internal data class AppDialogChoice(
    val key: String,
    val title: String,
    val subtitle: String? = null,
)

/** Single-choice dialog rendered as iOS-style list rows with a trailing check mark. */
@Composable
internal fun AppChoiceDialog(
    title: String,
    options: List<AppDialogChoice>,
    selectedKey: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalAbPalette.current
    AppDialog(title, onDismiss = onDismiss) {
        InsetGroup {
            options.forEachIndexed { index, option ->
                ListRow(
                    title = option.title,
                    subtitle = option.subtitle,
                    dividerAbove = index > 0,
                    trailing = {
                        if (option.key == selectedKey) {
                            Text("✓", style = AbType.body, color = palette.accent)
                        }
                    },
                    onClick = { onChoose(option.key) },
                )
            }
        }
        SecondaryButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** Controlled single-field input dialog; the caller owns validation and the value. */
@Composable
internal fun AppInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "保存",
    placeholder: String = "",
    error: String? = null,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    AppDialog(title, onDismiss = { if (!busy) onDismiss() }) {
        InsetGroup {
            InputRow(label = label, value = value, onValueChange = onValueChange, placeholder = placeholder)
        }
        error?.let { InlineMessage(it, isError = true, announce = true) }
        AppDialogActions(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            dismissLabel = "取消",
            onDismiss = onDismiss,
            busy = busy,
            confirmEnabled = confirmEnabled,
        )
    }
}

/** One action in [AppActionSheet]. */
internal data class AppAction(
    val label: String,
    val subtitle: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Bottom action sheet used instead of Material3 DropdownMenu. */
@Composable
internal fun AppActionSheet(
    actions: List<AppAction>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Keep the sheet visually lifted from the gesture area rather than flush with it.
    val bottomPadding = maxOf(
        if (navBottom > 0.dp) navBottom + AbSpacing.xxl else 0.dp,
        AbSpacing.xxxl * 2 + AbSpacing.s,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(start = AbSpacing.l, top = AbSpacing.l, end = AbSpacing.l),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(AbRadius.sheet),
                    color = LocalAbPalette.current.cardBg,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(AbSpacing.m),
                        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
                    ) {
                        if (title != null) {
                            Text(
                                title,
                                style = AbType.footnote,
                                color = LocalAbPalette.current.secondaryLabel,
                                modifier = Modifier.padding(horizontal = AbSpacing.m, vertical = AbSpacing.xs),
                            )
                        }
                        InsetGroup {
                            actions.forEachIndexed { index, action ->
                                ListRow(
                                    title = action.label,
                                    subtitle = action.subtitle,
                                    dividerAbove = index > 0,
                                    destructive = action.destructive,
                                    onClick = {
                                        onDismiss()
                                        action.onClick()
                                    },
                                )
                            }
                        }
                        SecondaryButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(bottomPadding))
            }
        }
    }
}
