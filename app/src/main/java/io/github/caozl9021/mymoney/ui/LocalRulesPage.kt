package io.github.caozl9021.mymoney.ui

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
import io.github.caozl9021.mymoney.data.CoverageCounter
import io.github.caozl9021.mymoney.data.CoverageStore
import io.github.caozl9021.mymoney.data.ExternalRuleStore
import io.github.caozl9021.mymoney.data.LedgerConfig
import io.github.caozl9021.mymoney.data.LocalRuleStore
import io.github.caozl9021.mymoney.data.MealWindow
import io.github.caozl9021.mymoney.ui.design.AbIcons
import io.github.caozl9021.mymoney.ui.design.AbSpacing
import io.github.caozl9021.mymoney.ui.design.AbTextField
import io.github.caozl9021.mymoney.ui.design.AbType
import io.github.caozl9021.mymoney.ui.design.GroupFooter
import io.github.caozl9021.mymoney.ui.design.GroupHeader
import io.github.caozl9021.mymoney.ui.design.IconBadge
import io.github.caozl9021.mymoney.ui.design.InsetDivider
import io.github.caozl9021.mymoney.ui.design.InsetGroup
import io.github.caozl9021.mymoney.ui.design.ListRow
import io.github.caozl9021.mymoney.ui.design.LocalAbPalette
import io.github.caozl9021.mymoney.ui.design.PrimaryButton

internal enum class RuleBulkAction { IMPORT, RESET, CLEAR_STATS }

/**
 * 规则文件 / 餐段 / 识别统计三组的状态。
 *
 * 三组各有独立子页，操作与二次确认仍只有这一份。
 * 学习相关部分由「学习记忆」子页承载。
 */
internal class RulesSectionsState(
    val context: android.content.Context,
    val summary: String,
    val windows: List<MealWindow>,
    val message: String?,
    val rows: List<CoverageCounter>,
    val pendingAction: RuleBulkAction?,
    val changeWindow: (Int, MealWindow) -> Unit,
    val saveMeals: () -> Unit,
    val fillMeals: () -> Unit,
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
        rows = rows,
        pendingAction = pendingAction,
        changeWindow = { index, window -> windows = windows.toMutableList().also { it[index] = window } },
        saveMeals = {
            message = runCatching { LocalRuleStore.saveMealWindows(windows); "餐段已保存" }
                .getOrElse { "未保存：${it.message}" }
        },
        fillMeals = { LedgerConfig.addMealSubCategories(); message = "已补齐缺少的餐别" },
        launchImport = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        exportRules = { exportFile.launch("麦米-rules.json") },
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

        }
    }
}

internal fun LazyListScope.mealSection(state: RulesSectionsState) {
    item { GroupHeader("餐饮时段") }
    items(state.windows.indices.toList()) { index ->
        val window = state.windows[index]
        InsetGroup {
            Column(Modifier.fillMaxWidth().padding(io.github.caozl9021.mymoney.ui.design.AbCardDensity.content), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                Text(window.subCategory, style = AbType.headline, color = LocalAbPalette.current.label)
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
        item { InsetGroup { ListRow(title = "暂无识别记录", subtitle = "有新的识别结果后，会在这里按日期与应用汇总。") } }
    }
    items(state.rows) { row ->
        InsetGroup {
            ListRow(
                title = appLabel(row.packageName),
                subtitle = "${row.day} · ${coverageLabel(row.outcome)}",
                trailing = { Text("${row.count} 次", style = AbType.headline, color = LocalAbPalette.current.label) },
            )
        }
    }
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
