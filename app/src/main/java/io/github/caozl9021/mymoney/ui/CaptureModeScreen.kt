package io.github.caozl9021.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.billkit.root.RootShell
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.caozl9021.mymoney.capture.CaptureModeManager
import io.github.caozl9021.mymoney.data.CaptureMode
import io.github.caozl9021.mymoney.ui.design.AbRadius
import io.github.caozl9021.mymoney.ui.design.AbSpacing
import io.github.caozl9021.mymoney.ui.design.AbType
import io.github.caozl9021.mymoney.ui.design.LocalAbPalette
import io.github.caozl9021.mymoney.ui.design.PrimaryButton

/**
 * First-launch gate. Saving the choice is independent of whether permission is ready.
 *
 * 只问一件事：这台手机接下来按哪种方式工作。选完之后设置里就只剩这一种方式的入口——
 * root 用户看不到无障碍那一套，普通用户也看不到任何 root 字样。
 */
@Composable
fun CaptureModeScreen(onOrdinarySetup: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    var choice by rememberSaveable { mutableStateOf<CaptureMode?>(null) }
    var checkingRoot by remember { mutableStateOf(false) }
    var rootError by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(p.pageBg).systemBarsPadding()) {
        TextButton(onClick = {
            CaptureModeManager.prepareOrdinarySetup(context)
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xxl)) {
            Text("稍后设置")
        }
        CaptureModeChoice(
            mode = choice,
            onSelect = { choice = it; rootError = null },
            onDone = {
                if (choice == CaptureMode.ROOT) {
                    checkingRoot = true
                    scope.launch {
                        try {
                            val granted = withContext(Dispatchers.IO) { RootShell.isGranted() }
                            if (choice == CaptureMode.ROOT) {
                                if (granted) CaptureModeManager.select(context, CaptureMode.ROOT)
                                else rootError = "Root 授权未通过，请重试或选择普通模式。"
                            }
                        } finally { checkingRoot = false }
                    }
                } else choice?.let { CaptureModeManager.select(context, it) }
            },
            modifier = Modifier.weight(1f),
            doneLabel = if (checkingRoot) "正在检测 Root…" else "继续",
            busy = checkingRoot,
            error = rootError,
            onUseOrdinary = onOrdinarySetup,
        )
    }
}

/** Shared with Settings: selection is local until the caller saves with onDone. */
@Composable
fun CaptureModeChoice(
    mode: CaptureMode?,
    onSelect: (CaptureMode) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    doneLabel: String = "继续",
    busy: Boolean = false,
    error: String? = null,
    onUseOrdinary: (() -> Unit)? = null,
) {
    val p = LocalAbPalette.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(AbSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.xl),
    ) {
        Text("选择使用方式", style = AbType.largeTitle, color = p.label)
        Column(
            Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            CaptureModeCard(
                "普通模式",
                "通知识别，可选页面辅助",
                mode == CaptureMode.ACCESSIBILITY,
                { if (onUseOrdinary != null) onUseOrdinary() else onSelect(CaptureMode.ACCESSIBILITY) },
            )
            CaptureModeCard(
                "Root 模式",
                "仅适用于已 Root 的手机",
                mode == CaptureMode.ROOT,
                { onSelect(CaptureMode.ROOT) },
            )
        }
        error?.let { Text(it, style = AbType.footnote, color = p.red) }
        PrimaryButton(doneLabel, onDone, Modifier.fillMaxWidth(), enabled = mode != null && !busy)
    }
}

@Composable
private fun CaptureModeCard(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val p = LocalAbPalette.current
    val shape = RoundedCornerShape(AbRadius.group)
    Column(
        Modifier.fillMaxWidth()
            .background(p.cardBg, shape)
            .border(1.dp, if (selected) p.accent else p.separator, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(AbSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = AbType.headline, color = p.label)
            if (selected) Text("已选择", style = AbType.footnote, color = p.accent)
        }
        Text(description, style = AbType.subhead, color = p.secondaryLabel)
    }
}
