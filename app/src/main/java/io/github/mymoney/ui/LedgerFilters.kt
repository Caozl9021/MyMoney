package io.github.mymoney.ui

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.mymoney.data.LedgerDateWindow
import io.github.mymoney.data.ledgerDateWindow
import io.github.mymoney.ui.design.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal fun ledgerFilterWindow(mode: String, start: String, end: String, today: LocalDate, zone: ZoneId, monthStartDay: Int = 1): LedgerDateWindow = when (mode) {
    "TODAY" -> ledgerDateWindow(today, today, zone)
    "MONTH" -> io.github.mymoney.data.LedgerMonthPeriod.containing(today, monthStartDay, zone).let { LedgerDateWindow(it.fromMillis, it.toMillis) }
    "LAST_MONTH" -> io.github.mymoney.data.LedgerMonthPeriod.containing(today, monthStartDay, zone).shift(-1).let { LedgerDateWindow(it.fromMillis, it.toMillis) }
    "CUSTOM" -> ledgerDateWindow(LocalDate.parse(start), LocalDate.parse(end), zone)
    else -> LedgerDateWindow(Long.MIN_VALUE, Long.MAX_VALUE)
}

internal fun ledgerDateFilterLabel(mode: String, start: String, end: String, monthStartDay: Int = 1): String = when (mode) {
    "TODAY" -> "今天"
    "MONTH" -> if (monthStartDay == 1) "本月" else "本期"
    "LAST_MONTH" -> if (monthStartDay == 1) "上月" else "上期"
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedgerFilters(
    keyword: String, onKeyword: (String) -> Unit,
    dateMode: String, start: String, end: String, today: LocalDate,
    onDate: (String, String, String) -> Unit,
    category: String?, categories: List<String>, onCategory: (String?) -> Unit,
    type: String?, onType: (String?) -> Unit,
    reimburse: String?, onReimburse: (String?) -> Unit,
    active: Boolean, onClear: () -> Unit, summaryLabel: String = "", monthStartDay: Int = 1,
) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val palette = LocalAbPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AbSpacing.page)
            .padding(top = AbSpacing.xs, bottom = AbSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        LedgerSearchField(keyword, onKeyword, focused, { focused = it },
            onSearch = { keyboard?.hide() })
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerFilterPill(
                text = ledgerDateFilterLabel(dateMode, start, end, monthStartDay),
                selected = dateMode != "ALL" && dateMode.isNotEmpty(),
                onClick = { picker = "DATE" },
            )
            LedgerFilterPill(
                text = category?.ifEmpty { "未分类" } ?: "全部分类",
                selected = category != null,
                onClick = { picker = "CATEGORY" },
            )
            LedgerFilterPill(
                text = ledgerTypeLabel(type),
                selected = type != null,
                onClick = { picker = "TYPE" },
            )
            LedgerFilterPill(
                text = when (reimburse) { "PENDING" -> "待报销"; "REIMBURSED" -> "已报销"; else -> "报销" },
                selected = reimburse != null,
                onClick = { picker = "REIMBURSE" },
            )
        }
        if (summaryLabel.isNotEmpty() || active) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AbSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    summaryLabel,
                    style = AbType.caption1,
                    color = palette.secondaryLabel,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Text(
                        "清除筛选",
                        style = AbType.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AbRadius.capsule))
                            .clickable(onClick = onClear)
                            .padding(horizontal = AbSpacing.s, vertical = 2.dp),
                    )
                }
            }
        }
    }
    when (picker) {
        "DATE" -> {
            val options = listOf(
                AppDialogChoice("ALL", "全部日期"),
                AppDialogChoice("TODAY", "今天"),
                AppDialogChoice("MONTH", if (monthStartDay == 1) "本月" else "本期"),
                AppDialogChoice("LAST_MONTH", if (monthStartDay == 1) "上月" else "上期"),
                AppDialogChoice("CUSTOM", "自选起止日期", if (dateMode == "CUSTOM" && start.isNotEmpty() && end.isNotEmpty()) "$start 至 $end" else null),
            )
            AppChoiceDialog(
                title = "日期范围",
                options = options,
                selectedKey = dateMode,
                onChoose = { key ->
                    if (key == "CUSTOM") {
                        picker = "CUSTOM"
                    } else {
                        onDate(key, start, end)
                        picker = null
                    }
                },
                onDismiss = { picker = null },
            )
        }
        "CATEGORY" -> {
            val rawCategories = (listOf<String?>(null, "") + categories.filter { it.isNotBlank() }).distinct()
            val options = rawCategories.map { value ->
                val key = when (value) {
                    null -> "__ALL__"
                    "" -> "__UNCATEGORIZED__"
                    else -> value
                }
                val label = value?.ifEmpty { "未分类" } ?: "全部分类"
                AppDialogChoice(key, label)
            }
            val selectedKey = when (category) {
                null -> "__ALL__"
                "" -> "__UNCATEGORIZED__"
                else -> category
            }
            AppChoiceDialog(
                title = "分类",
                options = options,
                selectedKey = selectedKey,
                onChoose = { key ->
                    val chosen = when (key) {
                        "__ALL__" -> null
                        "__UNCATEGORIZED__" -> ""
                        else -> key
                    }
                    onCategory(chosen)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        "REIMBURSE" -> {
            val options = listOf(
                AppDialogChoice("__ALL__", "全部"),
                AppDialogChoice("PENDING", "待报销"),
                AppDialogChoice("REIMBURSED", "已报销"),
            )
            AppChoiceDialog(
                title = "报销状态",
                options = options,
                selectedKey = reimburse ?: "__ALL__",
                onChoose = { key ->
                    onReimburse(if (key == "__ALL__") null else key)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        "TYPE" -> {
            val options = listOf(null, "EXPENSE", "INCOME", "REFUND", "TRANSFER").map { value ->
                AppDialogChoice(value ?: "__ALL__", ledgerTypeLabel(value))
            }
            AppChoiceDialog(
                title = "账单类型",
                options = options,
                selectedKey = type ?: "__ALL__",
                onChoose = { key ->
                    onType(if (key == "__ALL__") null else key)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        "CUSTOM" -> LedgerRangePicker(start, end, today, onDismiss = { picker = null }) { from, to ->
            onDate("CUSTOM", from, to); picker = null
        }
    }
}

@Composable
private fun LedgerFilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalAbPalette.current
    val bg = if (selected) p.accent.copy(alpha = 0.14f) else p.fill
    val fg = if (selected) p.accent else p.label
    val shape = RoundedCornerShape(AbRadius.capsule)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (selected) Modifier.border(0.75.dp, p.accent.copy(alpha = 0.4f), shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                role = androidx.compose.ui.semantics.Role.Tab
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AbType.footnote.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (focused) Modifier.border(0.75.dp, p.accent.copy(alpha = 0.55f), shape)
                else Modifier,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        Icon(AbIcons.Search, contentDescription = null, Modifier.size(16.dp), tint = p.secondaryLabel)
        BasicTextField(
            value = keyword,
            onValueChange = onKeyword,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .semantics { contentDescription = "搜索商户或备注" },
            singleLine = true,
            textStyle = AbType.subhead.copy(color = p.label),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSearch()
            }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (keyword.isEmpty()) {
                        Text("搜索商户或备注", style = AbType.subhead, color = p.secondaryLabel, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (keyword.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { onKeyword("") }
                    .semantics { contentDescription = "清除搜索" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AbIcons.Close, contentDescription = null, Modifier.size(15.dp), tint = p.secondaryLabel)
            }
        }
    }
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
