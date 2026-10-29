package com.example.autobook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autobook.data.LedgerMonthPeriod
import com.example.autobook.data.LedgerPeriodStore
import com.example.autobook.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LedgerPeriodPage(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val saved = rememberMonthStartDay()
    var selected by rememberSaveable(saved) { mutableIntStateOf(saved) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val clock = rememberLedgerClock()
    val current = LedgerMonthPeriod.containing(clock.date, selected, clock.zone)
    val scope = rememberCoroutineScope()
    ActiveBackHandler(enabled = busy) { }
    ActiveBackHandler(enabled = picker && !busy) { picker = false }
    if (picker) {
        MonthStartDayPage(selected, clock, onBack = { picker = false }, onSelect = { selected = it; picker = false })
        return
    }
    SettingsContentPage("记账周期", { if (!busy) onBack() }) {
        GroupHeader("每月起始日")
        InsetGroup {
            ListRow(title = "每月 $selected 日", subtitle = "从这一天开始统计一个月的收支", chevron = true,
                onClick = { if (!busy) picker = true })
        }
        GroupHeader("周期预览")
        InsetGroup {
            ListRow(title = if (selected == 1) "本月" else "本期", subtitle = current.label())
            ListRow(title = if (selected == 1) "上月" else "上期", subtitle = current.shift(-1).label(), dividerAbove = true)
        }
        message?.let { InlineMessage(it, isError = true, announce = true) }
        PrimaryButton("保存", onClick = {
            busy = true; message = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { LedgerPeriodStore.save(context, selected) } }
                    .onSuccess {
                        android.widget.Toast.makeText(context, "已按每月${selected}日统计", android.widget.Toast.LENGTH_SHORT).show()
                        onBack()
                    }.onFailure { message = it.message ?: "保存失败，请重试" }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth(), busy = busy, busyLabel = "正在保存…", enabled = !busy)
        if (selected != 1) SecondaryButton("恢复每月1日", { selected = 1 }, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

/** A normal app settings page, with no platform dialog or default Material picker chrome. */
@Composable
private fun MonthStartDayPage(initial: Int, clock: LedgerClock, onBack: () -> Unit, onSelect: (Int) -> Unit) {
    var draft by rememberSaveable(initial) { mutableIntStateOf(initial) }
    val palette = LocalAbPalette.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val preview = LedgerMonthPeriod.containing(clock.date, draft, clock.zone)
    SettingsContentPage("每月起始日", onBack) {
        GroupHeader("选择日期")
        InsetGroup {
            ListRow(title = "每月 $draft 日开始", subtitle = preview.label())
            InsetDivider()
            BoxWithConstraints(Modifier.fillMaxWidth().padding(AbCardDensity.content)) {
                val columns = (maxWidth.value / (48f * fontScale)).toInt().coerceIn(2, 7)
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                    (1..31).toList().chunked(columns).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                            line.forEach { day ->
                                val chosen = draft == day
                                val shape = RoundedCornerShape(AbRadius.chip)
                                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                                    .background(if (chosen) palette.fill else Color.Transparent)
                                    .border(if (chosen) 1.5.dp else 0.5.dp, if (chosen) palette.label else palette.separator, shape)
                                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { draft = day })
                                    .semantics { contentDescription = "每月${day}日" }
                                    .padding(vertical = AbSpacing.s, horizontal = 2.dp), contentAlignment = Alignment.Center) {
                                    Text(day.toString(), style = AbType.body.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal), color = palette.label)
                                }
                            }
                            repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        PrimaryButton("选定 $draft 日", { onSelect(draft) }, Modifier.fillMaxWidth())
        SecondaryButton("取消", onBack, Modifier.fillMaxWidth())
    }
}
