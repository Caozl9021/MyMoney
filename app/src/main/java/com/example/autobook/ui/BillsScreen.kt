package com.example.autobook.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import com.example.autobook.ui.design.rememberAbHaptic
import com.example.autobook.ui.design.LocalReduceMotion
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.autobook.data.BillEntity
import com.example.autobook.data.BillWriter
import com.example.autobook.ui.design.PrimaryButton
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.sectionHeader
import com.example.autobook.ui.design.BillListRow
import com.example.autobook.ui.design.EmptyState
import com.example.autobook.ui.design.GroupFooter
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PageScaffold
import com.example.autobook.ui.design.SwipeDeleteBackground
import com.example.autobook.ui.design.formatAmount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 明细：按天分组的实色卡，左滑删除。
 *
 * 分组头给出当天的支出/收入小计 —— 记账 App 里这是用户最常扫的信息。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(bills: List<BillEntity>, requestAdd: Int = 0, onEditorVisibilityChanged: (Boolean) -> Unit = {}) {
    val p = LocalAbPalette.current
    val listState = rememberLazyListState()
    val app = LocalContext.current.applicationContext
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var newSource by rememberSaveable { mutableStateOf<String?>(null) }
    var newTime by rememberSaveable { mutableStateOf(0L) }
    val undoItems by BillWriter.pendingUndo.collectAsState()
    val reduceMotion = LocalReduceMotion.current
    fun addBill() {
        editingId = null
        newTime = System.currentTimeMillis()
        newSource = "com.example.autobook.manual.${java.util.UUID.randomUUID()}"
    }
    val original = editingId?.let { id -> bills.firstOrNull { it.id == id } }
    val amountBounds = remember { mutableStateMapOf<Long, Rect>() }
    val editorOpen = newSource != null || original != null
    val currentVisibilityChanged by rememberUpdatedState(onEditorVisibilityChanged)
    LaunchedEffect(editorOpen) { currentVisibilityChanged(editorOpen) }
    DisposableEffect(Unit) { onDispose { currentVisibilityChanged(false) } }
    var consumedRequest by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(requestAdd) {
        if (requestAdd != 0 && requestAdd != consumedRequest) {
            consumedRequest = requestAdd
            addBill()
        }
    }

    // 按「日期」分组，而不是按格式化后的字符串 —— 否则跨年的同月日会被并到一组。
    val days = remember(bills) {
        bills.groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
    }

    Box(Modifier.fillMaxSize()) {
    PageScaffold(
        title = "明细",
        listState = listState,
        subtitle = if (bills.isEmpty()) null else "共 ${bills.size} 笔",
    ) { padding ->
        Box(Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = AbSpacing.xs,
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbSpacing.xs),
        ) {
            item(key = "add") {
                PrimaryButton(text = "记一笔", onClick = { addBill() }, modifier = Modifier.fillMaxWidth())
            }
            if (bills.isEmpty()) item(key = "empty") {
                InsetGroup {
                    EmptyState(icon = AbIcons.Note, title = "还没有账单", message = "点「记一笔」手动添加，或开启识别后自动生成确认卡。")
                }
            }
            days.forEach { (day, dayBills) ->
                item(key = "h-$day") {
                    DayHeader(
                        day = dayLabel(day),
                        expense = dayBills.filter { it.type == "EXPENSE" }.sumOf { it.priceCents },
                        income = dayBills.filter { it.type == "INCOME" }.sumOf { it.priceCents },
                        refund = dayBills.filter { it.type == "REFUND" }.sumOf { it.priceCents },
                    )
                }
                item(key = "g-$day") {
                    InsetGroup {
                        dayBills.forEachIndexed { index, bill ->
                            if (index > 0) InsetDivider(inset = 62.dp)
                            key(bill.id) {
                                SwipeableBillRow(
                                    bill,
                                    onAmountBounds = { amountBounds[bill.id] = it },
                                    hideAmount = editorOpen && editingId == bill.id,
                                    onEdit = { editingId = bill.id; newSource = null },
                                    onDelete = {
                                        BillWriter.deleteUndoably(app, bill.id) {
                                            Toast.makeText(app, it, Toast.LENGTH_LONG).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                GroupFooter(if (reduceMotion) "点账单编辑  ·  删除后5秒内可撤销  ·  数据仅存本机" else "点账单编辑  ·  左滑删除后5秒内可撤销  ·  数据仅存本机")
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = AbSpacing.page)
                .padding(bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            undoItems.forEach { item ->
                key(item.bill.id) {
                    Snackbar(action = {
                        TextButton(onClick = {
                            BillWriter.undoDelete(app, item.bill.id) { result ->
                                val message = result.fold({ "已撤销删除" }, { it.message ?: "撤销失败" })
                                Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("撤销") }
                    }) { Text("已删除 ${item.bill.merchant ?: formatAmount(item.bill.priceCents)}") }
                }
            }
        }
        }
    }
    if (editorOpen) key(newSource, editingId) {
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            BillEditorDialog(original, newSource.orEmpty(), newTime,
                sourceAmountBounds = editingId?.let { amountBounds[it] },
                onClose = { newSource = null; editingId = null })
        }
    }
    }
}

/** 「10月21日 星期三」；今年不显示年份，跨年才带年份。 */
private fun dayLabel(date: LocalDate): String {
    val sameYear = date.year == LocalDate.now().year
    val pattern = if (sameYear) "M月d日 EEEE" else "yyyy年M月d日 EEEE"
    return DateTimeFormatter.ofPattern(pattern, Locale.CHINA).format(date)
}

@Composable
private fun DayHeader(day: String, expense: Long, income: Long, refund: Long) {
    val p = LocalAbPalette.current
    Column(
        Modifier.fillMaxWidth().padding(start = AbSpacing.xs, end = AbSpacing.xs, top = AbSpacing.m),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(day, style = AbType.sectionHeader, color = p.secondaryLabel)
        Text(
            buildString {
                if (expense > 0) append("支出 ").append(formatAmount(expense))
                if (expense > 0 && income > 0) append("   ")
                if (income > 0) append("收入 ").append(formatAmount(income))
                if (refund > 0) { if (isNotEmpty()) append("   "); append("退款 ").append(formatAmount(refund)) }
            },
            style = AbType.caption1,
            color = p.tertiaryLabel,
        )
    }
}

/** 左滑删除：滑过阈值即删库，条目随列表刷新消失。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableBillRow(bill: BillEntity, onEdit: () -> Unit, onDelete: () -> Unit, onAmountBounds: (Rect) -> Unit, hideAmount: Boolean) {
    val haptic = rememberAbHaptic()
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BillListRow(bill, modifier = Modifier.weight(1f).clickable(onClick = onEdit),
                onAmountBounds = onAmountBounds, hideAmount = hideAmount)
            IconButton(onClick = { haptic.warn(); onDelete() }) {
                Icon(AbIcons.Close, contentDescription = "删除账单，可在5秒内撤销", tint = LocalAbPalette.current.red)
            }
        }
        return
    }
    var rowWidth by remember { mutableStateOf(1f) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptic.warn()
                onDelete()
                // 等数据库流移除行；失败时原行仍可继续操作。
                false
            } else false
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = { SwipeDeleteBackground(progress = (kotlin.math.abs(runCatching { state.requireOffset() }.getOrDefault(0f)) / rowWidth).coerceIn(0f, 1f)) },
        modifier = Modifier.onSizeChanged { rowWidth = it.width.toFloat().coerceAtLeast(1f) },
    ) {
        // 行本身要盖住红色底衬，所以必须有实底
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .background(LocalAbPalette.current.cardBg),
        ) {
            BillListRow(bill, modifier = Modifier.clickable(onClick = onEdit), onAmountBounds = onAmountBounds, hideAmount = hideAmount)
        }
    }
}
