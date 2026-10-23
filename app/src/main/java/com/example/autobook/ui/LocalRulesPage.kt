package com.example.autobook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.autobook.data.*
import com.example.autobook.ui.design.*

@Composable
fun LocalRulesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by LocalRuleStore.state.collectAsState()
    val summary by ExternalRuleStore.summary.collectAsState()
    var windows by remember { mutableStateOf(LocalRuleStore.snapshot().mealWindows) }
    var message by remember { mutableStateOf<String?>(null) }
    var showFormat by remember { mutableStateOf(false) }
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
            ExternalRuleStore.importJson(text).getOrThrow()
            "规则已导入，后续识别立即生效"
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
            item { GroupFooter("手动修改优先；其次是导入规则、内置规则文件、连续3次确认的学习结果和基础规则。餐饮最后按账单时间补充餐别。分类和账户分别选择，只使用清单内项目。") }
            message?.let { item { Text(it, color = LocalAbPalette.current.accent) } }
            item { GroupHeader("规则文件") }
            item { InsetGroup {
                ListRow(title = "当前规则", subtitle = summary)
                ListRow(title = "导入规则文件", subtitle = "JSON，最大64KB；文件有误时保留现有规则", onClick = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                ListRow(title = "规则文件格式与示例", onClick = { showFormat = !showFormat })
                ListRow(title = "导出当前规则文件", onClick = { exportFile.launch("AutoBook-rules.json") })
                ListRow(title = "恢复随应用提供的规则", subtitle = "仅移除导入覆盖，保留学习及餐段设置", onClick = { message = runCatching { ExternalRuleStore.reset(); "已恢复内置规则" }.getOrElse { "恢复失败：${it.message}" } })
            } }
            if (showFormat) item { InsetGroup(Modifier.padding(12.dp)) { Column {
                Text("导出当前文件即可获得完整示例，包括页面识别规则。这里只支持文字包含匹配，不支持正则。分类、子类、账户须先在清单中创建；不存在的名称不会使用。")
                Text("version固定为1；rules为页面规则，categories为分类关键词，accounts为账户关键词。type可用EXPENSE支出、INCOME收入、REFUND退款、TRANSFER转账；matchMethod=true表示匹配支付方式。")
                Text("""{"version":1,"rules":[],"categories":[{"type":"EXPENSE","keywords":["示例餐厅"],"category":"餐饮","subCategory":"午餐"}],"accounts":[{"type":"EXPENSE","keywords":["零钱"],"account":"微信钱包","matchMethod":true}]}""")
                Text("页面规则须提供packages包名、kind页面类型、require全部条件、strategy名称；anyOf是备选条件组，exclude是排除词。require中的|表示任一文字，规则总数最多200。完整可用字段见导出的随包规则。")
            } } }
            item { GroupHeader("餐饮时段") }
            item { GroupFooter("使用账单发生时间，起点包含、终点不包含；支持跨午夜，不允许重叠。空档使用三餐或餐饮一级；咖啡、零食等明确类别保持原样。") }
            items(windows.indices.toList()) { index ->
                val window = windows[index]
                Column {
                    Text(window.subCategory)
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
                ListRow(title = "使用本地学习", subtitle = "手改立即记住，接受3次后记住", trailing = { AbSwitch(state.learningEnabled, LocalRuleStore::setLearningEnabled) })
                ListRow(title = "清空已学习规则", subtitle = "共${state.learned.size}条", onClick = { LocalRuleStore.clearLearned(); message = "已清空学习记录" })
            } }
            items(state.learned, key = { it.id }) { rule ->
                InsetGroup { ListRow(title = rule.merchant, subtitle = listOfNotNull(when(rule.type) { com.example.billkit.BillType.INCOME -> "收入"; com.example.billkit.BillType.EXPENSE -> "支出"; com.example.billkit.BillType.REFUND -> "退款"; else -> "转账" }, rule.category, rule.subCategory, rule.account, rule.method, rule.source.label).joinToString(" · "), trailing = { androidx.compose.material3.TextButton(onClick = { LocalRuleStore.deleteLearned(rule.id) }) { Text("删除") } }) }
            }
            item { GroupFooter("关闭时暂停新增学习及已学习推荐。取消、重复或保存失败均不学习。学习和餐段保存在本机，并随账本备份。移除分类或账户后，对应规则自动失效。删除规则后会重新累计新确认。") }
            item { GroupHeader("最近7天识别统计") }
            item { InsetGroup { ListRow(title = "刷新统计", onClick = { revision++ }); ListRow(title = "清空统计", onClick = { CoverageStore.clear(); revision++ }) } }
            if (rows.isEmpty()) item { Text("暂无识别记录") }
            items(rows) { row -> Text("${row.day}  ${row.packageName}\n${coverageLabel(row.outcome)}：${row.count}次") }
            item { GroupFooter("统计仅含日期、应用和识别结果次数，不保存页面原文。诊断页保存样本时同时更新样本索引。") }
        }
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
    "skip:throttled" -> "采集间隔内跳过"
    "skip:duplicate" -> "重复页面"
    else -> "其他结果"
}
