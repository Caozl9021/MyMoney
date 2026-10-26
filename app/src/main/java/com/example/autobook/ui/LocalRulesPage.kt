package com.example.autobook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.data.CoverageCounter
import com.example.autobook.data.CoverageStore
import com.example.autobook.data.ExternalRuleStore
import com.example.autobook.data.LedgerConfig
import com.example.autobook.data.LocalRuleStore
import com.example.autobook.data.MealWindow
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbTextField
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.GroupFooter
import com.example.autobook.ui.design.GroupHeader
import com.example.autobook.ui.design.IconBadge
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.ListRow
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PrimaryButton

internal enum class RuleBulkAction { IMPORT, RESET, CLEAR_STATS }

/**
 * 规则文件 / 餐段 / 识别统计三组的状态。
 *
 * 这三组从原「本地规则」整页搬进「识别与规则」，实现、文案与二次确认仍只有这一份；
 * 学习相关部分已归到页面的「学习记忆」组，不再出现在这里。
 */
internal class RulesSectionsState(
    val context: android.content.Context,
    val summary: String,
    val windows: List<MealWindow>,
    val message: String?,
    val showFormat: Boolean,
    val rows: List<CoverageCounter>,
    val pendingAction: RuleBulkAction?,
    val changeWindow: (Int, MealWindow) -> Unit,
    val saveMeals: () -> Unit,
    val fillMeals: () -> Unit,
    val toggleFormat: () -> Unit,
    val launchImport: () -> Unit,
    val exportRules: () -> Unit,
    val refreshStats: () -> Unit,
    val requestAction: (RuleBulkAction) -> Unit,
    val confirmAction: () -> Unit,
    val dismissAction: () -> Unit,
)

@Composable
internal fun rememberRulesSectionsState(): RulesSectionsState {
    val context = LocalContext.current
    val summary by ExternalRuleStore.summary.collectAsStateWithLifecycle()
    var windows by remember { mutableStateOf(LocalRuleStore.snapshot().mealWindows) }
    var message by remember { mutableStateOf<String?>(null) }
    var showFormat by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<RuleBulkAction?>(null) }
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

    return RulesSectionsState(
        context = context,
        summary = summary,
        windows = windows,
        message = message,
        showFormat = showFormat,
        rows = rows,
        pendingAction = pendingAction,
        changeWindow = { index, window -> windows = windows.toMutableList().also { it[index] = window } },
        saveMeals = {
            message = runCatching { LocalRuleStore.saveMealWindows(windows); "餐段已保存" }
                .getOrElse { "未保存：${it.message}" }
        },
        fillMeals = { LedgerConfig.addMealSubCategories(); message = "已补齐缺少的餐别" },
        toggleFormat = { showFormat = !showFormat },
        launchImport = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        exportRules = { exportFile.launch("AutoBook-rules.json") },
        refreshStats = { revision++ },
        requestAction = { pendingAction = it },
        confirmAction = {
            val action = pendingAction
            pendingAction = null
            message = runCatching {
                when (action) {
                    RuleBulkAction.IMPORT -> {
                        ExternalRuleStore.importJson(requireNotNull(pendingImport)).getOrThrow()
                        "规则已导入，后续识别立即生效"
                    }
                    RuleBulkAction.RESET -> { ExternalRuleStore.reset(); "已恢复内置规则" }
                    RuleBulkAction.CLEAR_STATS -> { CoverageStore.clear(); revision++; "已清空识别统计" }
                    null -> return@runCatching message.orEmpty()
                }
            }.getOrElse { "操作失败：${it.message}" }
            pendingImport = null
        },
        dismissAction = { pendingAction = null; pendingImport = null },
    )
}

/** 规则文件的二次确认；由页面在末尾调一次。 */
@Composable
internal fun RulesActionDialog(state: RulesSectionsState) {
    val action = state.pendingAction ?: return
    val p = LocalAbPalette.current
    val title = when (action) {
        RuleBulkAction.IMPORT -> "导入并替换规则？"
        RuleBulkAction.RESET -> "恢复内置规则？"
        RuleBulkAction.CLEAR_STATS -> "清空识别统计？"
    }
    val impact = when (action) {
        RuleBulkAction.IMPORT -> "替换当前导入规则，后续识别立即使用新规则；文件有误保留现有规则。" +
            "不修改账单、识别历史、已学习规则和餐段设置。"
        RuleBulkAction.RESET -> "移除全部导入规则覆盖，重新使用随应用提供的规则。" +
            "保留账单、识别历史、已学习规则和餐段设置；移除的导入覆盖不能直接撤销。"
        RuleBulkAction.CLEAR_STATS -> "清除最近7天按日期、应用和结果记录的识别次数。" +
            "保留账单、识别历史、规则和诊断样本。此操作不可撤销。"
    }
    AlertDialog(
        onDismissRequest = state.dismissAction,
        title = { Text(title, style = AbType.headline, color = p.label) },
        text = { Text(impact, style = AbType.body, color = p.secondaryLabel) },
        confirmButton = { TextButton(onClick = state.confirmAction) { Text("确认", color = p.red) } },
        dismissButton = { TextButton(onClick = state.dismissAction) { Text("取消", color = p.secondaryLabel) } },
    )
}

internal fun LazyListScope.rulesFileSection(state: RulesSectionsState) {
    state.message?.let { text ->
        item { Text(text, style = AbType.footnote, color = LocalAbPalette.current.accent) }
    }
    item { GroupHeader("规则文件") }
    item {
        val p = LocalAbPalette.current
        InsetGroup {
            ListRow(
                title = "导入规则文件",
                subtitle = "选择 JSON 文件，确认后替换导入规则；文件有误保留原规则",
                leading = { IconBadge(AbIcons.Export, p.accent) }, chevron = true,
                onClick = state.launchImport,
            )
            InsetDivider()
            ListRow(title = "当前规则", subtitle = state.summary)
            InsetDivider()
            ListRow(
                title = "导出当前规则文件",
                leading = { IconBadge(AbIcons.Layers, p.accent) },
                onClick = state.exportRules,
            )
            InsetDivider()
            ListRow(
                title = "恢复随应用提供的规则",
                subtitle = "移除导入覆盖，保留学习及餐段设置",
                onClick = { state.requestAction(RuleBulkAction.RESET) },
            )
            InsetDivider()
            ListRow(
                title = "规则文件格式与示例",
                subtitle = if (state.showFormat) "点击收起" else "查看 JSON 字段和完整示例",
                chevron = true,
                onClick = state.toggleFormat,
            )
        }
    }
    if (state.showFormat) {
        item {
            GroupFooter(
                "version固定为1；rules为页面规则，categories为分类关键词，accounts为账户关键词。" +
                    "type可用EXPENSE支出、INCOME收入、REFUND退款、TRANSFER转账；matchMethod=true表示匹配支付方式。",
            )
        }
        item {
            GroupFooter(
                """{"version":1,"rules":[],"categories":[{"type":"EXPENSE","keywords":["示例餐厅"],"category":"餐饮","subCategory":"午餐"}],"accounts":[{"type":"EXPENSE","keywords":["零钱"],"account":"微信钱包","matchMethod":true}]}""",
            )
        }
        item {
            GroupFooter(
                "这里只支持文字包含匹配，不支持正则。分类、子类、账户须先在清单中创建；不存在的名称不会使用。" +
                    "页面规则须提供packages包名、kind页面类型、require全部条件、strategy名称；" +
                    "anyOf是备选条件组，exclude是排除词；require中的|表示任一文字，规则总数最多200。",
            )
        }
    }
}

internal fun LazyListScope.mealSection(state: RulesSectionsState) {
    item { GroupHeader("餐饮时段") }
    item { GroupFooter("按账单发生时间推荐早餐、午餐等；调整后点击保存。") }
    items(state.windows.indices.toList()) { index ->
        val window = state.windows[index]
        Column(Modifier.fillMaxWidth().padding(vertical = AbSpacing.xs)) {
            Text(window.subCategory, style = AbType.body, color = LocalAbPalette.current.label)
            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                AbTextField(
                    value = window.from,
                    onValueChange = { value -> state.changeWindow(index, window.copy(from = value)) },
                    placeholder = "开始 HH:mm", modifier = Modifier.weight(1f),
                )
                AbTextField(
                    value = window.to,
                    onValueChange = { value -> state.changeWindow(index, window.copy(to = value)) },
                    placeholder = "结束 HH:mm", modifier = Modifier.weight(1f),
                )
            }
        }
    }
    item { PrimaryButton(text = "保存餐段", modifier = Modifier.fillMaxWidth(), onClick = state.saveMeals) }
    item {
        InsetGroup {
            ListRow(
                title = "补齐早餐、午餐、晚餐、夜宵",
                subtitle = "明确添加缺少的餐饮子类，保留已有分类",
                onClick = state.fillMeals,
            )
        }
    }
    item { GroupFooter("餐段起点包含、终点不包含，支持跨午夜且不允许重叠。空档使用三餐或餐饮一级。") }
}

internal fun LazyListScope.statisticsSection(state: RulesSectionsState) {
    item { GroupHeader("最近7天识别统计") }
    item {
        InsetGroup {
            ListRow(title = "刷新统计", onClick = state.refreshStats)
            InsetDivider()
            ListRow(title = "清空统计", subtitle = "仅清除识别结果次数",
                onClick = { state.requestAction(RuleBulkAction.CLEAR_STATS) })
        }
    }
    if (state.rows.isEmpty()) {
        item { Text("暂无识别记录", style = AbType.body, color = LocalAbPalette.current.secondaryLabel) }
    }
    items(state.rows) { row ->
        Text(
            "${row.day}  ${appLabel(row.packageName)}\n${coverageLabel(row.outcome)}：${row.count}次",
            style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel,
        )
    }
    item { GroupFooter("统计仅含日期、应用和识别结果次数，不保存页面原文。") }
}

internal fun coverageLabel(outcome: String): String = when (outcome) {
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
