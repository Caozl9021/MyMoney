package com.example.autobook.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.autobook.ui.design.*

/** Shared, app-styled confirmation; dismissal always means continue editing. */
@Composable
internal fun DiscardChangesDialog(onContinue: () -> Unit, onDiscard: () -> Unit) {
    val palette = LocalAbPalette.current
    Dialog(onDismissRequest = onContinue, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(AbSpacing.xxl).widthIn(max = 400.dp).fillMaxWidth(),
            shape = RoundedCornerShape(AbRadius.card),
            color = palette.cardBg,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(AbSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("放弃本次修改？", style = AbType.navTitle, color = palette.label, textAlign = TextAlign.Center)
                Spacer(Modifier.height(AbSpacing.s))
                Text("尚未保存的内容将被丢弃。", style = AbType.subhead, color = palette.secondaryLabel, textAlign = TextAlign.Center)
                Spacer(Modifier.height(AbSpacing.xl))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                    SecondaryButton("继续编辑", onClick = onContinue, modifier = Modifier.weight(1f), tint = palette.label)
                    DestructiveButton("放弃修改", onClick = onDiscard, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
