package com.example.autobook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.autobook.data.*
import com.example.autobook.ui.design.*

private enum class RuleBulkAction { IMPORT, RESET, CLEAR_LEARNED, CLEAR_STATS }

@Composable
fun LocalRulesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val p = LocalAbPalette.current
    val state by LocalRuleStore.state.collectAsState()
    val summary by ExternalRuleStore.summary.collectAsState()
    var windows by remember { mutableStateOf(LocalRuleStore.snapshot().mealWindows) }
    var message by remember { mutableStateOf<String?>(null) }
    var showFormat by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<RuleBulkAction?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val rows = remember(revision) { CoverageStore.rows() }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65_536) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= 65_536) { "规则文件不能超过64KB" }
                bytes.toString(Charsets.UTF_8)
            }
            pendingImport = text
            pendingAction = RuleBulkAction.IMPORT
            "规则文件已选择，确认后导入"
        }.getOrElse { "导入失败，保留原规则：${it.message}" }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(ExternalRuleStore.exportJson()) }
            "规则文件已导出"
        }.getOrElse { "导出失败：${it.message}" }
    }
    SubPageScaffold(title = "本地规则", onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            message?.let { item { Text(it, color = LocalAbPalette.current.accent) } }
            item { GroupHeader("导入规则") }
            item { InsetGroup {
                ListRow(title = "导入规则文件", subtitle = "选择 JSON 文件，确认后替换导入规则；文件有误保留原规则",
                    leading = { IconBadge(AbIcons.Export, p.accent) }, chevron = true,
                    onClick = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                InsetDivider()
                ListRow(title = "当前规则", subtitle = summary)
            } }
            item { GroupHeader("餐饮时段") }
            item { GroupFooter("按账单发生时间推荐早餐、午餐等；调整后点击保存。") }
            items(windows.indices.toList()) { index ->
                val window = windows[index]
                Column {
                    Text(window.subCategory, style = AbType.body, color = p.label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AbTextField(value = window.from, onValueChange = { value -> windows = windows.toMutableList().also { it[index] = window.copy(from = value) } }, placeholder = "开始 HH:mm", modifier = Modifier.weight(1f))
                        AbTextField(value = window.to, onValueChange = { value -> windows = windows.toMutableList().also { it[index] = window.copy(to = value) } }, placeholder = "结束 HH:mm", modifier = Modifier.weight(1f))
                    }
                }
            }
            item { PrimaryButton(text = "保存餐段", modifier = Modifier.fillMaxWidth(), onClick = { message = runCatching { LocalRuleStore.saveMealWindows(windows); "餐段已保存" }.getOrElse { "未保存：${it.message}" } }) }
            item { InsetGroup { ListRow(title = "补齐早餐、午餐、晚餐、夜宵", subtitle = "明确添加缺少的餐饮子类，保留已有分类", onClick = { LedgerConfig.addMealSubCategories(); message = "已补齐缺少的餐别" }) } }
            item { GroupHeader("记住我的选择") }
            item { InsetGroup {
                ListRow(title = "使用本地学习", subtitle = "保存手改后记住；同一推荐确认3次后记住", trailing = { AbSwitch(state.learningEnabled, LocalRuleStore::setLearningEnabled) })
                ListRow(title = "清空已学习规则", subtitle = "共${state.learned.size}条，确认后清空", onClick = { pendingAction = RuleBulkAction.CLEAR_LEARNED })
            } }
            items(state.learned, key = { it.id }) { rule ->
                InsetGroup { ListRow(title = rule.merchant, subtitle = listOfNotNull(when(rule.type) { com.example.billkit.BillType.INCOME -> "收入"; com.example.billkit.BillType.EXPENSE -> "支出"; com.example.billkit.BillType.REFUND -> "退款"; else -> "转账" }, rule.category, rule.subCategory, rule.account, rule.method, rule.source.label).joinToString(" · "), trailing = { androidx.compose.material3.TextButton(onClick = { LocalRuleStore.deleteLearned(rule.id) }) { Text("删除", color = p.red) } }) }
            }
            item { GroupFooter("关闭后暂停学习与已学习推荐；学习和餐段随账本备份。") }
            item { GroupHeader("高级") }
            item { InsetGroup {
                ListRow(title = "高级规则与识别统计", subtitle = if (showAdvanced) "收起技术说明" else "规则格式、导出、恢复与统计",
                    leading = { IconBadge(AbIcons.Terminal, p.accent) },
                    trailing = { androidx.compose.material3.Icon(if (showAdvanced) AbIcons.ChevronUp else AbIcons.ChevronDown, contentDescription = if (showAdvanced) "收起" else "展开", tint = p.tertiaryLabel) },
                    onClick = { showAdvanced = !showAdvanced })
            } }
            if (showAdvanced) {
                item { GroupFooter("手动修改优先；其次是导入规则、内置规则文件、连续3次确认的学习结果和基础规则。餐饮最后按账单时间补充餐别。分类和账户分别选择，只使用清单内项目。") }
                item { GroupFooter("餐段起点包含、终点不包含，支持跨午夜且不允许重叠。空档使用三餐或餐饮一级，咖啡、零食等明确类别保持原样。取消、重复或保存失败不学习；移除分类或账户后对应规则自动失效，删除规则后重新累计确认。") }
                item { InsetGroup {
                    ListRow(title = "规则文件格式与示例", subtitle = if (showFormat) "点击收起" else "查看 JSON 字段和完整示例", onClick = { showFormat = !showFormat })
                    InsetDivider()
                    ListRow(title = "导出当前规则文件", onClick = { exportFile.launch("AutoBook-rules.json") })
                    InsetDivider()
                    ListRow(title = "恢复随应用提供的规则", subtitle = "移除导入覆盖，保留学习及餐段设置", onClick = { pendingAction = RuleBulkAction.RESET })
                } }
                if (showFormat) item {
                    InsetGroup(Modifier.padding(12.dp)) {
                        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides p.secondaryLabel) {
                            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                Text("导出当前文件即可获得完整示例，包括页面识别规则。这里只支持文字包含匹配，不支持正则。分类、子类、账户须先在清单中创建；不存在的名称不会使用。")
                                Text("version固定为1；rules为页面规则，categories为分类关键词，accounts为账户关键词。type可用EXPENSE支出、INCOME收入、REFUND退款、TRANSFER转账；matchMethod=true表示匹配支付方式。")
                                Text("""{"version":1,"rules":[],"categories":[{"type":"EXPENSE","keywords":["示例餐厅"],"category":"餐饮","subCategory":"午餐"}],"accounts":[{"type":"EXPENSE","keywords":["零钱"],"account":"微信钱包","matchMethod":true}]}""")
                                Text("页面规则须提供packages包名、kind页面类型、require全部条件、strategy名称；anyOf是备选条件组，exclude是排除词。require中的|表示任一文字，规则总数最多200。完整可用字段见导出的随包规则。")
                            }
                        }
                    }
                }
                item { GroupHeader("最近7天识别统计") }
                item { InsetGroup {
                    ListRow(title = "刷新统计", onClick = { revision++ })
                    InsetDivider()
                    ListRow(title = "清空统计", subtitle = "仅清除识别结果次数", onClick = { pendingAction = RuleBulkAction.CLEAR_STATS })
                } }
                if (rows.isEmpty()) item { Text("暂无识别记录", style = AbType.body, color = p.secondaryLabel) }
                items(rows) { row -> Text("${row.day}  ${row.packageName}\n${coverageLabel(row.outcome)}：${row.count}次", style = AbType.footnote, color = p.secondaryLabel) }
                item { GroupFooter("统计仅含日期、应用和识别结果次数，不保存页面原文。识别排查中保存样本时同时更新样本索引。") }
            }
        }
    }
    pendingAction?.let { action ->
        val title = when (action) {
            RuleBulkAction.IMPORT -> "导入并替换规则？"
            RuleBulkAction.RESET -> "恢复内置规则？"
            RuleBulkAction.CLEAR_LEARNED -> "清空已学习规则？"
            RuleBulkAction.CLEAR_STATS -> "清空识别统计？"
        }
        val impact = when (action) {
            RuleBulkAction.IMPORT -> "替换当前导入规则，后续识别立即使用新规则；文件有误保留现有规则。不修改账单、识别历史、已学习规则和餐段设置。"
            RuleBulkAction.RESET -> "移除全部导入规则覆盖，重新使用随应用提供的规则。保留账单、识别历史、已学习规则和餐段设置；移除的导入覆盖不能直接撤销。"
            RuleBulkAction.CLEAR_LEARNED -> "删除全部 ${state.learned.size} 条已学习规则及待累计的确认次数，后续从头学习。保留账单、识别历史、导入规则和餐段设置。此操作不可撤销。"
            RuleBulkAction.CLEAR_STATS -> "清除最近7天按日期、应用和结果记录的识别次数。保留账单、识别历史、规则和诊断样本。此操作不可撤销。"
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingAction = null; pendingImport = null },
            title = { Text(title, style = AbType.headline, color = p.label) },
            text = { Text(impact, style = AbType.body, color = p.secondaryLabel) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                pendingAction = null
                message = runCatching {
                    when (action) {
                        RuleBulkAction.IMPORT -> {
                            ExternalRuleStore.importJson(requireNotNull(pendingImport)).getOrThrow()
                            "规则已导入，后续识别立即生效"
                        }
                        RuleBulkAction.RESET -> { ExternalRuleStore.reset(); "已恢复内置规则" }
                        RuleBulkAction.CLEAR_LEARNED -> { LocalRuleStore.clearLearned(); "已清空学习记录" }
                        RuleBulkAction.CLEAR_STATS -> { CoverageStore.clear(); revision++; "已清空识别统计" }
                    }
                }.getOrElse { "操作失败：${it.message}" }
                pendingImport = null
            }) { Text("确认", color = p.red) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingAction = null; pendingImport = null }) { Text("取消", color = p.secondaryLabel) } },
        )
    }

}

private fun coverageLabel(outcome: String): String = when(outcome) {
    "draft" -> "识别成功"
    "skip:not-a-bill-page" -> "非账单页"
    "skip:failed" -> "交易失败"
    "skip:cancelled" -> "交易取消"
    "skip:pending" -> "交易未完成"
    "skip:no-status" -> "未确认交易状态"
    "skip:no-amount" -> "未找到金额"
    "skip:zero-amount" -> "零金额"
    "skip:throttled" -> "识别间隔内跳过"
    "skip:duplicate" -> "重复页面"
    else -> "其他结果"
}
