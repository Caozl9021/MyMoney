package io.github.caozl9021.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.caozl9021.mymoney.ui.design.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Local calendar date and hour/minute; only confirmation changes the caller's draft. */
internal fun ledgerTimeMillis(date: LocalDate, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
    require(hour in 0..23 && minute in 0..59)
    return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}

@Composable
internal fun LedgerDateTimeDialog(value: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(value, zone) { Instant.ofEpochMilli(value).atZone(zone) }
    var day by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(initial).toString()) }
    var hour by rememberSaveable { mutableStateOf(initial.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable { mutableStateOf(initial.minute.toString().padStart(2, '0')) }
    val selected = LocalDate.parse(day)
    val shown = YearMonth.parse(month)
    val p = LocalAbPalette.current
    val valid = hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .85f
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(AbRadius.card))
            .background(p.cardBg).heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("日期与时间", style = AbType.headline, color = p.label, modifier = Modifier.weight(1f))
                DateAction("取消", onDismiss)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DateAction("‹", { month = shown.minusMonths(1).toString() }, "上个月")
                Text("${shown.year}年${shown.monthValue}月", style = AbType.body, color = p.label,
                    modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally))
                DateAction("›", { month = shown.plusMonths(1).toString() }, "下个月")
            }
            Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, style = AbType.caption1, color = p.secondaryLabel, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally))
            } }
            val offset = shown.atDay(1).dayOfWeek.value - 1
            repeat((offset + shown.lengthOfMonth() + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val n = week * 7 + column - offset + 1
                        if (n !in 1..shown.lengthOfMonth()) Spacer(Modifier.weight(1f).height(44.dp))
                        else {
                            val date = shown.atDay(n)
                            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (date == selected) p.accent.copy(alpha = .16f) else p.cardBg)
                                .clickable { day = date.toString() }.semantics { role = Role.Button; this.selected = date == selected; contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日" },
                                contentAlignment = Alignment.Center) { Text(n.toString(), style = AbType.body, color = if (date == selected) p.accent else p.label) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("时间", style = AbType.footnote, color = p.secondaryLabel, modifier = Modifier.weight(1f))
                ClockField(hour, "小时") { hour = it }
                Text(":", style = AbType.body, color = p.label)
                ClockField(minute, "分钟") { minute = it }
            }
            if (!valid) Text("请输入有效时分", style = AbType.caption1, color = p.red)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateAction("现在", {
                    val now = java.time.ZonedDateTime.now(zone)
                    day = now.toLocalDate().toString(); month = YearMonth.from(now).toString()
                    hour = now.hour.toString().padStart(2, '0'); minute = now.minute.toString().padStart(2, '0')
                })
                PrimaryButton("确定", enabled = valid, modifier = Modifier.weight(1f), onClick = {
                    if (valid) onConfirm(ledgerTimeMillis(LocalDate.parse(day), hour.toInt(), minute.toInt(), zone))
                })
            }
        }
    }
}

@Composable
private fun DateAction(text: String, onClick: () -> Unit, description: String = text) {
    val p = LocalAbPalette.current
    Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onClick)
        .semantics { role = Role.Button; contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(text, style = AbType.body, color = p.secondaryLabel)
    }
}

@Composable
private fun ClockField(value: String, label: String, onChange: (String) -> Unit) {
    val p = LocalAbPalette.current
    BasicTextField(value, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) onChange(it) }, singleLine = true,
        textStyle = AbType.body.copy(color = p.label), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(p.accent), modifier = Modifier.width(56.dp).heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp)).background(p.fill).padding(12.dp).semantics { contentDescription = label })
}
