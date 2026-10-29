package com.example.autobook.ui

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.autobook.data.AppDb
import com.example.autobook.importer.*
import com.example.autobook.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QianjiImportPage(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val palette = LocalAbPalette.current
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<QianjiImport.Preview?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var filter by remember { mutableStateOf(QianjiRowFilter.ATTENTION) }
    var visibleCount by remember { mutableIntStateOf(QIANJI_PREVIEW_PAGE_SIZE) }
    var expandedRow by remember { mutableStateOf<Int?>(null) }
    ActiveBackHandler(enabled = busy) { }

    fun showReport(value: QianjiImport.Preview) {
        report = value
        filter = if (value.items.any { it.state == QianjiImport.State.SKIPPED || it.state == QianjiImport.State.CONFLICT })
            QianjiRowFilter.ATTENTION else QianjiRowFilter.ALL
        visibleCount = QIANJI_PREVIEW_PAGE_SIZE
        expandedRow = null
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true; error = null; report = null; finished = false; fileName = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val name = runCatching {
                            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0)?.take(240) else null
                            }
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "钱迹账单.xlsx"
                        val doc = context.contentResolver.openInputStream(uri)?.use { QianjiXlsx.read(it) } ?: error("无法读取文件")
                        name to QianjiImport.previewDatabase(AppDb.get(context), doc)
                    }
                }.onSuccess { (name, preview) -> fileName = name; showReport(preview) }
                    .onFailure { error = "读取失败：${it.message}" }
                busy = false
            }
        }
    }
    val selectFile = { launcher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }
    val current = report
    val filtered = remember(current, filter) { filter.select(current?.items.orEmpty()) }
    val omitted = remember(current) {
        current?.items.orEmpty().filter { it.state == QianjiImport.State.READY }
            .flatMap { (it.row.result as QianjiRow.Result.Accepted).record.omittedFields }.groupingBy { it }.eachCount()
    }
    val totals = remember(current) {
        current?.items.orEmpty().filter { it.state == QianjiImport.State.READY }
            .groupBy { (it.row.result as QianjiRow.Result.Accepted).record.type }
            .mapValues { (_, rows) -> rows.size to rows.fold(BigInteger.ZERO) { n, item ->
                n + BigInteger.valueOf((item.row.result as QianjiRow.Result.Accepted).record.cents)
            } }
    }

    SubPageScaffold(title = "钱迹账单", onBack = { if (!busy) onBack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap)) {
            item("file") {
                com.example.autobook.ui.design.SectionColumn {
                    GroupHeader("导入文件")
                    InsetGroup {
                        ListRow(title = "钱迹账单", subtitle = fileName ?: "选择钱迹导出的 XLSX 文件",
                            leading = { SettingsIconBadge("钱迹账单", AbIcons.Export) }, subtitleMaxLines = 2)
                        Column(Modifier.padding(start = AbSpacing.m, end = AbSpacing.m, bottom = AbSpacing.m)) {
                            if (current == null) PrimaryButton("选择文件", selectFile, Modifier.fillMaxWidth(),
                                enabled = !busy, busy = busy, busyLabel = "正在读取…")
                            else SecondaryButton("选择其他文件", selectFile, Modifier.fillMaxWidth(), enabled = !busy)
                        }
                    }
                }
            }
            if (error != null) item("error") { InlineMessage(error.orEmpty(), isError = true, announce = true) }
            if (current != null) {
                item("summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                        GroupHeader(if (finished) "导入完成" else "文件预览")
                        InsetGroup {
                            Column(Modifier.padding(com.example.autobook.ui.design.AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                    if (finished) Icon(AbIcons.Check, null, Modifier.size(22.dp), tint = palette.accent)
                                    Text(if (finished) "本次新增" else "可导入账单", style = AbType.footnote, color = palette.secondaryLabel)
                                }
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                                    Text(current.count(QianjiImport.State.READY).toString(), style = AbType.largeTitle, color = palette.label)
                                    Text("条", Modifier.padding(bottom = 4.dp), style = AbType.body, color = palette.secondaryLabel)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                    Text("重复 ${current.count(QianjiImport.State.DUPLICATE)}", style = AbType.footnote, color = palette.secondaryLabel)
                                    Text("跳过 ${current.count(QianjiImport.State.SKIPPED)}", style = AbType.footnote, color = palette.secondaryLabel)
                                    Text("冲突 ${current.count(QianjiImport.State.CONFLICT)}", style = AbType.footnote, color = palette.secondaryLabel)
                                }
                            }
                            if (totals.isNotEmpty()) {
                                InsetDivider()
                                Column(Modifier.padding(horizontal = AbSpacing.l, vertical = AbSpacing.s),
                                    verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                    listOf("EXPENSE", "INCOME", "TRANSFER").forEach { type -> totals[type]?.let { (count, cents) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                                            Text("${qianjiTypeLabel(type)} · $count 条", Modifier.weight(1f), style = AbType.footnote, color = palette.secondaryLabel)
                                            Text("¥${BigDecimal(cents, 2).toPlainString()}", Modifier.weight(1f), style = AbType.footnote,
                                                textAlign = TextAlign.End, color = when(type) { "EXPENSE" -> palette.expense; "INCOME" -> palette.income; else -> palette.transfer })
                                        }
                                    } }
                                }
                            }
                        }
                    }
                }
                if (omitted.isNotEmpty()) item("omitted") {
                    InsetGroup {
                        ListRow(title = if (finished) "未保留的信息" else "以下信息不会导入",
                            subtitle = omitted.entries.joinToString(" · ") { "${it.key} ${it.value} 条" })
                    }
                }
                if (!finished && current.count(QianjiImport.State.READY) > 0) item("action") {
                    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                        if (current.count(QianjiImport.State.READY) > 0) {
                            PrimaryButton("导入 ${current.count(QianjiImport.State.READY)} 条账单", { confirm = true },
                                Modifier.fillMaxWidth(), enabled = !busy, busy = busy, busyLabel = "正在导入…")
                        }
                    }
                }
            }
            item("help") {
                InsetGroup {
                    ListRow(title = "导入说明", subtitle = if (showHelp) null else "支持范围、重复处理与备份", onClick = { showHelp = !showHelp },
                        trailing = { Text(if (showHelp) "收起" else "展开", style = AbType.footnote, color = palette.secondaryLabel) })
                    if (showHelp) {
                        InsetDivider()
                        Column(Modifier.padding(com.example.autobook.ui.design.AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.m)) {
                            QianjiHelpText("支持范围", "仅 CNY 支出、收入及完整转账。报销、债务和含优惠券、手续费、关联账单等记录暂不支持，预览中会列明。")
                            QianjiHelpText("现有数据", "不修改分类与账户清单，不调整余额；未匹配的名称保留在账单中。与手动或自动记账重合的交易需自行核对。")
                            QianjiHelpText("重复与删除", "相同钱迹 ID 不重复导入，内容改变时列为冲突。已删除记录再次选择文件也会跳过；可用含原账单的完整备份显式覆盖恢复。")
                            QianjiHelpText("备份与时间", "建议先导出当前完整备份。旧版备份可能不含导入身份；时间按本机时区解释。")
                            current?.let { p ->
                                val currencies = p.items.groupingBy { it.row.cells["币种"].orEmpty().ifBlank { "空币种" } }.eachCount()
                                QianjiHelpText("原表币种", currencies.entries.joinToString(" · ") { "${it.key} ${it.value} 条" })
                            }
                        }
                    }
                }
            }
            if (current != null) {
                item("detail-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                        GroupHeader("账单明细")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs), verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
                            QianjiRowFilter.entries.forEach { option ->
                                val label = if (finished && option == QianjiRowFilter.READY) "已导入" else option.label
                                CapsuleChip(label, filter == option, onClick = { filter = option; visibleCount = QIANJI_PREVIEW_PAGE_SIZE; expandedRow = null })
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) item("empty") {
                    InsetGroup { ListRow(title = if (filter == QianjiRowFilter.ATTENTION) "没有需要关注的记录" else "没有这类账单", subtitle = "可切换筛选查看其他记录") }
                }
                items(filtered.take(visibleCount), key = { it.row.number }) { item ->
                    QianjiPreviewRow(item, finished, expandedRow == item.row.number) {
                        expandedRow = if (expandedRow == item.row.number) null else item.row.number
                    }
                }
                if (visibleCount < filtered.size) item("more") {
                    SecondaryButton("显示更多（已显示 ${minOf(visibleCount, filtered.size)} / ${filtered.size}）",
                        { visibleCount += QIANJI_PREVIEW_PAGE_SIZE }, Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text("导入到当前账本？") },
        text = { Text("将新增 ${report?.count(QianjiImport.State.READY) ?: 0} 条账单，跳过其余记录。原账本名称和预览列出的附加信息不会保留，资产余额保持不变。") },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val doc = report?.document ?: return@TextButton
            busy = true; error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { QianjiImport.commit(AppDb.get(context), doc) } }
                    .onSuccess {
                        showReport(it); finished = true
                        com.example.autobook.systems.SystemIntegration.refresh(context)
                        Toast.makeText(context, "已新增 ${it.count(QianjiImport.State.READY)} 条账单", Toast.LENGTH_LONG).show()
                    }.onFailure { error = "导入失败，本次未写入：${it.message}" }
                busy = false; confirm = false
            }
        }) { Text(if (busy) "正在导入…" else "确认导入") } })
}

private fun qianjiTypeLabel(type: String) = when(type) { "EXPENSE" -> "支出"; "INCOME" -> "收入"; else -> "转账" }

@Composable
private fun QianjiHelpText(title: String, text: String) {
    val p = LocalAbPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.xs)) {
        Text(title, style = AbType.footnote, color = p.label)
        Text(text, style = AbType.footnote, color = p.secondaryLabel)
    }
}

@Composable
private fun QianjiPreviewRow(item: QianjiImport.Item, finished: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val p = LocalAbPalette.current
    val record = (item.row.result as? QianjiRow.Result.Accepted)?.record
    val status = when(item.state) {
        QianjiImport.State.READY -> if (finished) "已导入" else "可导入"
        QianjiImport.State.DUPLICATE -> "重复"
        QianjiImport.State.CONFLICT -> "冲突"
        QianjiImport.State.SKIPPED -> "跳过"
    }
    val title = record?.subCategory ?: record?.category ?: item.row.cells["分类"]?.takeIf { it.isNotBlank() } ?: "未分类"
    val amount = record?.let { BigDecimal.valueOf(it.cents, 2).toPlainString() } ?: item.row.cells["金额"].orEmpty()
    val currency = item.row.cells["币种"].orEmpty()
    val displayAmount = if (currency == "CNY") "¥$amount" else "$currency $amount".trim()
    InsetGroup {
        ListRow(title = title, subtitle = "${item.row.cells["时间"].orEmpty().take(16)} · $status",
            trailing = { Text(displayAmount, Modifier.widthIn(max = 140.dp), style = AbType.footnote, textAlign = TextAlign.End,
                color = when(record?.type) { "EXPENSE" -> p.expense; "INCOME" -> p.income; else -> p.secondaryLabel }) }, onClick = onClick)
        if (item.state == QianjiImport.State.SKIPPED || item.state == QianjiImport.State.CONFLICT) {
            Text(item.reason, Modifier.padding(start = AbSpacing.l, end = AbSpacing.l, bottom = AbSpacing.s), style = AbType.caption1, color = p.secondaryLabel)
        }
        if (expanded) {
            InsetDivider()
            Column(Modifier.padding(com.example.autobook.ui.design.AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Text("账单表 · 第 ${item.row.number} 行", style = AbType.caption1, color = p.secondaryLabel)
                Text(item.reason, style = AbType.footnote, color = p.label)
                item.row.cells.filter { (name, value) -> value.isNotBlank() && name != "ID" }.forEach { (name, value) ->
                    Text("$name：$value", style = AbType.footnote, color = p.secondaryLabel)
                }
                if (record != null && record.omittedFields.isNotEmpty()) Text("未保留：${record.omittedFields.joinToString("、")}", style = AbType.footnote, color = p.secondaryLabel)
            }
        }
    }
}
