package com.example.autobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.autobook.data.LedgerDateWindow
import com.example.autobook.data.ledgerDateWindow
import com.example.autobook.ui.design.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal fun ledgerFilterWindow(mode: String, start: String, end: String, today: LocalDate, zone: ZoneId): LedgerDateWindow = when (mode) {
    "TODAY" -> ledgerDateWindow(today, today, zone)
    "MONTH" -> ledgerDateWindow(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()), zone)
    "LAST_MONTH" -> today.minusMonths(1).let { ledgerDateWindow(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth()), zone) }
    "CUSTOM" -> ledgerDateWindow(LocalDate.parse(start), LocalDate.parse(end), zone)
    else -> LedgerDateWindow(Long.MIN_VALUE, Long.MAX_VALUE)
}

internal fun ledgerDateFilterLabel(mode: String, start: String, end: String): String = when (mode) {
    "TODAY" -> "今天"
    "MONTH" -> "本月"
    "LAST_MONTH" -> "上月"
    "CUSTOM" -> "$start 至 $end"
    else -> "全部日期"
}

internal fun ledgerTypeLabel(type: String?): String = when (type) {
    "EXPENSE" -> "支出"
    "INCOME" -> "收入"
    "REFUND" -> "退款"
    "TRANSFER" -> "转账"
    else -> "全部类型"
}

/** Always visible controls; option lists can scroll independently at large font sizes. */
@Composable
internal fun LedgerFilters(
    keyword: String, onKeyword: (String) -> Unit,
    dateMode: String, start: String, end: String, today: LocalDate,
    onDate: (String, String, String) -> Unit,
    category: String?, categories: List<String>, onCategory: (String?) -> Unit,
    type: String?, onType: (String?) -> Unit,
    active: Boolean, onClear: () -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val palette = LocalAbPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = AbSpacing.page)) {
        LedgerSearchField(keyword, onKeyword, focused, { focused = it },
            onSearch = { keyboard?.hide() })
        Row(Modifier.fillMaxWidth()) {
          // 一行三个筛选胶囊，窄屏/大字可横向滚动；命中条件的那一颗带选中态（描边+浅底+勾），
          // 不让用户靠「文字变了」去猜哪一项生效。
          Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            CapsuleChip(ledgerDateFilterLabel(dateMode, start, end), dateMode != "ALL" && dateMode.isNotEmpty(), { picker = "DATE" },
                Modifier.height(48.dp))
            CapsuleChip(category?.ifEmpty { "未分类" } ?: "全部分类", !category.isNullOrEmpty(), { picker = "CATEGORY" },
                Modifier.height(48.dp))
            CapsuleChip(ledgerTypeLabel(type), type != null, { picker = "TYPE" }, Modifier.height(48.dp))
          }
          if (active) TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") }
        }
    }
    when (picker) {
        "DATE" -> LedgerChoiceDialog("日期范围", onDismiss = { picker = null }) {
            listOf("ALL" to "全部日期", "TODAY" to "今天", "MONTH" to "本月", "LAST_MONTH" to "上月").forEach { (value, label) ->
                TextButton(onClick = { onDate(value, start, end); picker = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (dateMode == value) "✓ $label" else label) }
            }
            TextButton(onClick = { picker = "CUSTOM" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("自选起止日期") }
        }
        "CATEGORY" -> LedgerChoiceDialog("分类", onDismiss = { picker = null }) {
            (listOf<String?>(null, "") + categories.filter { it.isNotBlank() }).distinct().forEach { value ->
                TextButton(onClick = { onCategory(value); picker = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    val label = value?.ifEmpty { "未分类" } ?: "全部分类"
                    Text(if (category == value) "✓ $label" else label)
                }
            }
        }
        "TYPE" -> LedgerChoiceDialog("账单类型", onDismiss = { picker = null }) {
            listOf(null, "EXPENSE", "INCOME", "REFUND", "TRANSFER").forEach { value ->
                TextButton(onClick = { onType(value); picker = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text((if (type == value) "✓ " else "") + ledgerTypeLabel(value))
                }
            }
        }
        "CUSTOM" -> LedgerRangePicker(start, end, today, onDismiss = { picker = null }) { from, to ->
            onDate("CUSTOM", from, to); picker = null
        }
    }
}

/**
 * 搜索胶囊：单行低对比填充，没有 floating label。
 *
 * 原来的 OutlinedTextField 带 label，未聚焦时占位文字是「浮起来的第二行」，
 * 聚焦后标签跳到边框上，视觉高度随之跳动。这里换成固定视觉高 48dp 的胶囊，
 * 占位文字就在中间，焦点只改描边和底色，不改变尺寸。
 *
 * 无障碍：图标是纯装饰不重复朗读；清除按钮有独立语义标签，
 * 输入框本身用 `semantics` 补上「搜索商户或备注」的描述，TalkBack 不会只念一个空框。
 */
@Composable
private fun LedgerSearchField(
    keyword: String,
    onKeyword: (String) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
) {
    val p = LocalAbPalette.current
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(AbRadius.capsule)
    val bg = if (focused) p.cardBg else p.fill
    val stroke = if (focused) p.accent.copy(alpha = 0.55f) else p.separator
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Icon(AbIcons.Search, contentDescription = null, Modifier.size(18.dp), tint = p.secondaryLabel)
        BasicTextField(
            value = keyword,
            onValueChange = onKeyword,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .semantics { contentDescription = "搜索商户或备注" },
            singleLine = true,
            textStyle = AbType.body.copy(color = p.label),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                // 只收起键盘，不移动焦点：硬件 Enter 的 key-up 可能落到工具栏按钮上。
                onSearch()
            }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (keyword.isEmpty()) {
                        Text("搜索商户或备注", style = AbType.body, color = p.secondaryLabel, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (keyword.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onKeyword("") }
                    .semantics { contentDescription = "清除搜索" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AbIcons.Close, contentDescription = null, Modifier.size(16.dp), tint = p.secondaryLabel)
            }
        }
    }
}

@Composable
private fun LedgerChoiceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), content = content)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerRangePicker(start: String, end: String, today: LocalDate, onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    // Material's calendar values represent UTC dates, independent of the device's local offset.
    val initialStart = runCatching { LocalDate.parse(start) }.getOrDefault(today)
    val initialEnd = runCatching { LocalDate.parse(end) }.getOrDefault(today)
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialSelectedEndDateMillis = initialEnd.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
            val from = Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
            val to = Instant.ofEpochMilli(state.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
            onApply(from.toString(), to.toString())
        }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DateRangePicker(state = state, modifier = Modifier.heightIn(max = 480.dp), title = { Text("选择起止日期", modifier = Modifier.padding(16.dp)) }) }
}
