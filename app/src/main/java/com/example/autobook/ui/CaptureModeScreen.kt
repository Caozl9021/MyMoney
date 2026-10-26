package com.example.autobook.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.autobook.capture.CaptureModeManager
import com.example.autobook.data.CaptureMode
import com.example.autobook.data.Settings
import com.example.autobook.ui.design.AbRadius
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PrimaryButton

/** First-launch gate. Saving the choice is independent of whether permission is ready. */
@Composable
fun CaptureModeScreen() {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    var choice by rememberSaveable { mutableStateOf<CaptureMode?>(null) }
    Column(Modifier.fillMaxSize().background(p.pageBg).systemBarsPadding()) {
        Text("通知监听需要单独授予系统通知使用权。当前交易通知模板待真实样本验证，页面辅助可稍后配置。",
            style = AbType.footnote, color = p.secondaryLabel,
            modifier = Modifier.padding(horizontal = AbSpacing.xxl, vertical = AbSpacing.s))
        TextButton(onClick = {
            Settings(context).apply {
                notificationCaptureEnabled = true
                pageFallbackEnabled = false
                enabled = true
            }
            CaptureModeManager.reconcile(context)
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = AbSpacing.xxl)) {
            Text("先使用通知监听，稍后配置页面辅助")
        }
        CaptureModeChoice(
            mode = choice,
            onSelect = { choice = it },
            onDone = { choice?.let { CaptureModeManager.select(context, it) } },
            modifier = Modifier.weight(1f),
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
) {
    val p = LocalAbPalette.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(AbSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.xl),
    ) {
        Text("页面辅助方式", style = AbType.largeTitle, color = p.label)
        Text(
            "页面辅助从 Root 与无障碍中选择一种，与通知监听独立。选择后会记住，也可稍后在设置中切换。",
            style = AbType.body,
            color = p.secondaryLabel,
        )
        Column(
            Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
        ) {
            CaptureModeCard(
                "Root 模式",
                "适合已 Root 的手机，需要 su 授权。通过 Root 读取账单页面，无需开启无障碍服务。",
                mode == CaptureMode.ROOT,
                { onSelect(CaptureMode.ROOT) },
            )
            CaptureModeCard(
                "无障碍模式",
                "适合普通手机，需要在系统设置中开启 AutoBook 无障碍服务。通过页面文字识别账单，无需 Root。",
                mode == CaptureMode.ACCESSIBILITY,
                { onSelect(CaptureMode.ACCESSIBILITY) },
            )
        }
        Text(
            "悬浮窗权限用于在支付应用上直接弹出记账确认卡。可以先保存模式进入 App，再在设置中完成授权；Root 授权未通过也可切换模式。",
            style = AbType.footnote,
            color = p.secondaryLabel,
        )
        PrimaryButton(doneLabel, onDone, Modifier.fillMaxWidth(), enabled = mode != null)
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
