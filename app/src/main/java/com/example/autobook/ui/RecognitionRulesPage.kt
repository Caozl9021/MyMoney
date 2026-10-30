package com.example.autobook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.data.LearningField
import com.example.autobook.data.LearningQuery
import com.example.autobook.data.LearningRules
import com.example.autobook.data.LocalRuleStore
import com.example.autobook.data.PendingLearning
import com.example.autobook.data.RuleSource
import com.example.autobook.data.Settings
import com.example.autobook.ui.design.AbIcons
import com.example.autobook.ui.design.AbSpacing
import com.example.autobook.ui.design.AbSwitch
import com.example.autobook.ui.design.AbTextField
import com.example.autobook.ui.design.AbType
import com.example.autobook.ui.design.CapsuleChip
import com.example.autobook.ui.design.EmptyState
import com.example.autobook.ui.design.GroupFooter
import com.example.autobook.ui.design.IconBadge
import com.example.autobook.ui.design.InsetDivider
import com.example.autobook.ui.design.InsetGroup
import com.example.autobook.ui.design.ListRow
import com.example.autobook.ui.design.LocalAbPalette
import com.example.autobook.ui.design.PrimaryButton
import com.example.autobook.ui.design.StatusLabel
import com.example.autobook.ui.design.SubPageScaffold
import com.example.billkit.BillType
import com.example.billkit.notification.NotificationParser
import java.time.Instant
import java.time.ZoneId

/** 应用通道组的筛选口径；只影响展示，不改白名单。 */
private enum class AppFilter(val label: String) {
    ALL("全部"), ATTENTION("待处理"), READY("可用"),
}

/** 自动记账下的六个独立设置页，复用同一套规则状态与操作。 */
internal enum class RecognitionRuleSection(val title: String) {
    APPS("应用与通道"),
    LEARNING("学习记忆"),
    TEMPLATES("通知模板"),
    FILES("规则文件"),
    MEALS("餐段设置"),
    STATISTICS("识别统计"),
}

/** 通道结论来自 [appChannelState]，与首页健康卡共用 [CaptureHealthInput]。 */
@Composable
internal fun RecognitionRulesPage(
    whitelist: Set<String>,
    healthInput: CaptureHealthInput,
    onBack: () -> Unit,
    onToggleApp: (String) -> Unit,
    onOpenDebug: () -> Unit,
    section: RecognitionRuleSection = RecognitionRuleSection.APPS,
) {
    val p = LocalAbPalette.current
    val ruleState by LocalRuleStore.state.collectAsStateWithLifecycle()
    val bundledRules by com.example.autobook.data.SpecRuleStore.status.collectAsStateWithLifecycle()

    var appQuery by rememberSaveable { mutableStateOf("") }
    var appFilter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var expandedApp by rememberSaveable { mutableStateOf<String?>(null) }

    var learningQuery by rememberSaveable { mutableStateOf("") }
    var learningType by rememberSaveable { mutableStateOf<BillType?>(null) }
    var learningField by rememberSaveable { mutableStateOf(LearningField.ALL) }
    var learningSource by rememberSaveable { mutableStateOf<RuleSource?>(null) }
    var showLearned by rememberSaveable { mutableStateOf(false) }
    var showPending by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var confirmClearLearned by remember { mutableStateOf(false) }

    val rulesSections = rememberRulesSectionsState()
    val coverageRows = remember(rulesSections.rows) { rulesSections.rows }
    val recentByPackage = remember(coverageRows) {
        coverageRows.groupBy { it.packageName }.mapValues { (_, rows) ->
            rows.maxByOrNull { it.day }?.let { "${coverageLabel(it.outcome)} · ${it.day}" }
        }
    }

    val packages = remember(whitelist) { appPackages(whitelist) }
    val appStates = remember(healthInput, whitelist, packages) {
        appChannelStates(healthInput, packages, whitelist)
    }
    val visibleApps = appStates.filter { state ->
        val matchesFilter = when (appFilter) {
            AppFilter.ALL -> true
            AppFilter.ATTENTION -> state.needsAttention
            AppFilter.READY -> state.anyActive
        }
        val text = appQuery.trim().lowercase()
        matchesFilter && (text.isEmpty() || state.packageName.lowercase().contains(text) ||
            appLabel(state.packageName).lowercase().contains(text))
    }
    val activeApps = appStates.count { it.anyActive }
    val attentionApps = appStates.count { it.needsAttention }

    val effectivePending = remember(ruleState) { LearningRules.effectivePending(ruleState) }
    val query = LearningQuery(learningQuery, learningType, learningField, learningSource)
    val learnedRows = remember(ruleState, query) { LearningRules.filter(ruleState.learned, query) }

    SubPageScaffold(title = section.title, onBack = onBack) { padding ->
        com.example.autobook.ui.design.SectionLazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page, end = AbSpacing.page,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(com.example.autobook.ui.design.AbCardDensity.gap),
        ) {
            rulesSections.message?.let { message ->
                item { InsetGroup { ListRow(title = message) } }
            }
            // ------------------------------------------------------------ 应用通道
            if (section == RecognitionRuleSection.APPS) {
                item {
                    InsetGroup {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                        ) {
                            IconBadge(AbIcons.Search, p.tertiaryLabel, size = 26.dp)
                            AbTextField(
                                value = appQuery,
                                onValueChange = { appQuery = it },
                                modifier = Modifier.weight(1f),
                                placeholder = "搜索应用或包名",
                            )
                            if (appQuery.isNotEmpty()) {
                                androidx.compose.material3.TextButton(onClick = { appQuery = "" }) {
                                    Text("清除", color = p.secondaryLabel)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = AbSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                    ) {
                        AppFilter.entries.forEach { filter ->
                            CapsuleChip(filter.label, appFilter == filter, { appFilter = filter }, Modifier.weight(1f))
                        }
                    }
                }
                if (visibleApps.isEmpty()) {
                    item { EmptyState(AbIcons.Search, "没有匹配的应用", "换个关键词，或把筛选切回「全部」。") }
                }
                items(visibleApps, key = { it.packageName }) { state ->
                    val expanded = expandedApp == state.packageName
                    InsetGroup {
                        ListRow(
                            title = appLabel(state.packageName),
                            subtitle = "${state.packageName}${recentByPackage[state.packageName]?.let { " · 最近 $it" }.orEmpty()}",
                            // 真机教训：两个「XX未生效」长徽标吃掉近半行宽，支付宝与京东的包名会从中间折断。
                            // 徽标收成「图标 + 通道名」，状态由图标与底色表达；完整包名在展开详情里给。
                            subtitleMaxLines = 1,
                            leading = { IconBadge(if (state.anyActive) AbIcons.Check else AbIcons.Close,
                                if (state.anyActive) p.green else p.tertiaryLabel, size = 26.dp) },
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    ChannelChip("通知", state.notification.active)
                                    // 页面徽标只在该模式下存在页面通道时才画：root 模式没有页面采集，
                                    // 画一个永远灰着的徽标等于告诉用户"功能坏了"。
                                    state.page?.let { ChannelChip("页面", it.active) }
                                    state.hook?.let { ChannelChip("LSP", it.active) }
                                }
                            },
                            chevron = true,
                            onClick = { expandedApp = if (expanded) null else state.packageName },
                        )
                        if (expanded) {
                            InsetDivider()
                            ChannelDetail(state, onToggleApp)
                        }
                    }
                }

            }

            // ------------------------------------------------------------ 学习记忆
            if (section == RecognitionRuleSection.LEARNING) {
                item {
                    InsetGroup {
                        ListRow(
                            title = "使用本地学习",
                            subtitle = "手改后立即记住；同一结果确认 ${LearningRules.PROMOTION_THRESHOLD} 次后记住",
                            trailing = { AbSwitch(ruleState.learningEnabled) { LocalRuleStore.setLearningEnabled(it) } },
                        )
                        InsetDivider()
                        ListRow(
                            title = "已学习 ${ruleState.learned.size} 条",
                            subtitle = if (showLearned) "收起" else "查看、检索与删除",
                            chevron = true,
                            onClick = { showLearned = !showLearned },
                        )
                        InsetDivider()
                        ListRow(
                            title = "待生效 ${effectivePending.size} 条",
                            subtitle = if (showPending) "收起" else "同一结果累计到 ${LearningRules.PROMOTION_THRESHOLD} 次才生效",
                            chevron = true,
                            onClick = { showPending = !showPending },
                        )
                        InsetDivider()
                        ListRow(
                            title = "清空全部学习记忆",
                            subtitle = "已生效 ${ruleState.learned.size} 条与待生效 ${effectivePending.size} 条一并清除",
                            destructive = true,
                            onClick = { confirmClearLearned = true },
                        )
                    }
                }
                if (showLearned) {
                    item {
                        InsetGroup {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                            ) {
                                IconBadge(AbIcons.Search, p.tertiaryLabel, size = 26.dp)
                                AbTextField(learningQuery, { learningQuery = it }, Modifier.weight(1f), "搜索商户、分类或账户")
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(vertical = AbSpacing.xs), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                BillType.entries.forEach { type ->
                                    CapsuleChip(billTypeLabel(type), learningType == type, {
                                        learningType = if (learningType == type) null else type
                                    }, Modifier.weight(1f))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                LearningField.entries.forEach { field ->
                                    CapsuleChip(learningFieldLabel(field), learningField == field, {
                                        learningField = field
                                    }, Modifier.weight(1f))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                listOf(LearningSourceOption.MANUAL, LearningSourceOption.LEARNED).forEach { option ->
                                    CapsuleChip(option.label, learningSource == option.source, {
                                        learningSource = if (learningSource == option.source) null else option.source
                                    }, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (learnedRows.isEmpty()) {
                        item {
                            EmptyState(
                                AbIcons.Filter,
                                if (ruleState.learned.isEmpty()) "还没有学习记忆" else "没有匹配的记忆",
                                if (ruleState.learned.isEmpty())
                                    "保存账单时改过分类或账户，这里就会出现记住的偏好。"
                                else "换个关键词，或把筛选切回全部。",
                            )
                        }
                    }
                    items(learnedRows, key = { it.id }) { rule ->
                        InsetGroup {
                            ListRow(
                                title = rule.merchant,
                                subtitle = listOfNotNull(
                                    billTypeLabel(rule.type), rule.category, rule.subCategory,
                                    rule.account, rule.method, rule.source.label,
                                    "命中 ${rule.hits} 次", learnedDate(rule.updatedAt),
                                ).joinToString(" · "),
                                trailing = {
                                    androidx.compose.material3.TextButton(onClick = { pendingDelete = rule.id }) {
                                        Text("删除", color = p.red)
                                    }
                                },
                            )
                        }
                    }
                }
                if (showPending) {
                    items(effectivePending, key = { it.key }) { pending ->
                        InsetGroup {
                            ListRow(
                                title = LearningRules.pendingLabels(pending).joinToString(" · "),
                                subtitle = LearningRules.progress(pending),
                                trailing = {
                                    androidx.compose.material3.TextButton(onClick = { LocalRuleStore.deletePending(pending.key) }) {
                                        Text("删除", color = p.red)
                                    }
                                },
                            )
                        }
                    }
                    if (effectivePending.isNotEmpty()) {
                        item {
                            InsetGroup {
                                ListRow(title = "清除全部待生效确认", subtitle = "不清除已生效的记忆",
                                    destructive = true, onClick = { LocalRuleStore.clearPending() })
                            }
                        }
                    }
                }

            }

            // ------------------------------------------------------------ 通知模板
            if (section == RecognitionRuleSection.TEMPLATES) {
                item {
                    InsetGroup {
                        ListRow(
                            title = "支付宝交易提醒",
                            subtitle = "模板 alipay-trade-expense-v1 · 仅限已验证的渠道与「交易提醒」标题",
                            leading = { IconBadge(AbIcons.Note, p.accent, size = 26.dp) },
                        )
                        val untemplated = appStates.filter { it.whitelisted && !it.hasTemplate }
                        if (untemplated.isNotEmpty()) {
                            InsetDivider()
                            ListRow(
                                title = "尚无已验证模板",
                                subtitle = untemplated.joinToString("、") { appLabel(it.packageName) } +
                                    " · 是否支持本地规则请查看应用与通道",
                                leading = { IconBadge(AbIcons.Close, p.tertiaryLabel, size = 26.dp) },
                            )
                        }
                        InsetDivider()
                        ListRow(
                            title = "通知诊断与样本",
                            subtitle = "最近事件、脱敏样本与导出",
                            leading = { IconBadge(AbIcons.Terminal, p.accent, size = 26.dp) },
                            chevron = true,
                            onClick = onOpenDebug,
                        )
                    }
                }
            }

            // ------------------------------------------------------------ 规则文件 / 餐段 / 统计
            if (section == RecognitionRuleSection.FILES) {
                item {
                    InsetGroup {
                        ListRow(title = "自研识别规则", subtitle = when {
                            bundledRules.error != null -> "加载失败：${bundledRules.error}"
                            !bundledRules.ready -> "正在准备本地规则"
                            else -> "${bundledRules.version} · 已启用 ${bundledRules.rules} / ${bundledRules.available} 条"
                        })
                    }
                }
                rulesFileSection(rulesSections)
            }
            if (section == RecognitionRuleSection.MEALS) mealSection(rulesSections)
            if (section == RecognitionRuleSection.STATISTICS) statisticsSection(rulesSections)
        }
    }

    RulesActionDialog(rulesSections)

    pendingDelete?.let { id ->
        val rule = ruleState.learned.firstOrNull { it.id == id }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记忆？", style = AbType.headline, color = p.label) },
            text = {
                Text(
                    "${rule?.merchant ?: "这条记忆"}的偏好会被移除，后续推荐不再使用它；要重新累计确认次数。" +
                        "账单、识别历史和其他记忆不受影响。",
                    style = AbType.body, color = p.secondaryLabel,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    LocalRuleStore.deleteLearned(id)
                    pendingDelete = null
                }) { Text("删除", color = p.red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = p.secondaryLabel)
                }
            },
        )
    }
    if (confirmClearLearned) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClearLearned = false },
            title = { Text("清空全部学习记忆？", style = AbType.headline, color = p.label) },
            text = {
                Text(
                    "删除全部 ${ruleState.learned.size} 条已记住的偏好及 ${effectivePending.size} 条待生效确认，" +
                        "后续从头学习。保留账单、识别历史、导入规则和餐段设置。此操作不可撤销。",
                    style = AbType.body, color = p.secondaryLabel,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    LocalRuleStore.clearLearned()
                    confirmClearLearned = false
                }) { Text("清空", color = p.red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmClearLearned = false }) {
                    Text("取消", color = p.secondaryLabel)
                }
            },
        )
    }
}

@Composable
private fun ChannelDetail(state: AppChannelState, onToggleApp: (String) -> Unit) {
    val p = LocalAbPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AbSpacing.l, vertical = AbSpacing.s),
        verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
    ) {
        ChannelLine("通知", state.notification)
        state.page?.let { ChannelLine("页面", it) }
        state.hook?.let { ChannelLine("LSPosed", it) }
        Text("可用表示识别方式已就绪，具体交易仍需匹配本地规则。",
            style = AbType.footnote, color = p.secondaryLabel)
        ListRow(
            title = if (state.whitelisted) "从清单移除" else "加入清单",
            subtitle = if (state.whitelisted) "移除后不再识别该应用，已生成的待确认不受影响"
            else "加入后按上方可用方式识别",
            destructive = state.whitelisted,
            onClick = { onToggleApp(state.packageName) },
        )
    }
}

/** 应用行右侧的通道徽标：只用通道名 + 勾/叉，状态不靠再加「未生效」三个字。 */
@Composable
private fun ChannelChip(label: String, active: Boolean) {
    StatusLabel(
        text = label,
        // 徽标省掉了「未生效」三个字，读屏时必须补回来，否则只念出通道名。
        modifier = Modifier.semantics { contentDescription = if (active) "$label 可用" else "$label 未生效" },
        icon = if (active) AbIcons.Check else AbIcons.Close,
        emphasized = active,
    )
}

@Composable
private fun ChannelLine(label: String, verdict: ChannelVerdict) {
    val p = LocalAbPalette.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        Text(label, style = AbType.footnote, color = p.label, modifier = Modifier.width(40.dp))
        Text(verdict.detail, style = AbType.footnote, color = if (verdict.active) p.secondaryLabel else p.red,
            modifier = Modifier.weight(1f))
    }
}

private enum class LearningSourceOption(val source: RuleSource, val label: String) {
    MANUAL(RuleSource.MANUAL, "手动学习"),
    LEARNED(RuleSource.LEARNED, "确认学习"),
}

private fun billTypeLabel(type: BillType): String = when (type) {
    BillType.EXPENSE -> "支出"
    BillType.INCOME -> "收入"
    BillType.REFUND -> "退款"
    BillType.TRANSFER -> "转账"
}

private fun learningFieldLabel(field: LearningField): String = when (field) {
    LearningField.ALL -> "全部字段"
    LearningField.CATEGORY -> "分类"
    LearningField.ACCOUNT -> "账户"
}

private fun learnedDate(at: Long): String =
    if (at <= 0L) "时间未知"
    else "最近 " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
