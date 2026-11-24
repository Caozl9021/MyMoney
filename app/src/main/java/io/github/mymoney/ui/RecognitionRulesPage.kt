package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billkit.BillType
import io.github.mymoney.data.LearningField
import io.github.mymoney.data.LearningQuery
import io.github.mymoney.data.LearningRules
import io.github.mymoney.data.LocalRuleStore
import io.github.mymoney.data.RuleSource
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbIcons
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbSwitch
import io.github.mymoney.ui.design.AbTextButton
import io.github.mymoney.ui.design.AbTextField
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.AppAlertDialog
import io.github.mymoney.ui.design.CapsuleChip
import io.github.mymoney.ui.design.EmptyState
import io.github.mymoney.ui.design.IconBadge
import io.github.mymoney.ui.design.InsetDivider
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.SectionLazyColumn
import io.github.mymoney.ui.design.SubPageScaffold
import java.time.Instant
import java.time.ZoneId

/** 自动记账偏好子页：监控应用 / 学习记忆 / 餐段设置。 */
internal enum class RecognitionRuleSection(val title: String) {
    APPS("监控应用"),
    LEARNING("学习记忆"),
    MEALS("餐段设置"),
}

@Composable
internal fun RecognitionRulesPage(
    whitelist: Set<String>,
    onBack: () -> Unit,
    onToggleApp: (String) -> Unit,
    section: RecognitionRuleSection = RecognitionRuleSection.APPS,
) {
    val p = LocalAbPalette.current
    val ruleState by LocalRuleStore.state.collectAsStateWithLifecycle()

    var learningQuery by rememberSaveable { mutableStateOf("") }
    var learningType by rememberSaveable { mutableStateOf<BillType?>(null) }
    var learningField by rememberSaveable { mutableStateOf(LearningField.ALL) }
    var learningSource by rememberSaveable { mutableStateOf<RuleSource?>(null) }
    var showLearned by rememberSaveable { mutableStateOf(false) }
    var showPending by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var confirmClearLearned by remember { mutableStateOf(false) }

    val mealState = rememberMealSettingsState()
    val packages = remember(whitelist) { appPackages(whitelist) }
    val effectivePending = remember(ruleState) { LearningRules.effectivePending(ruleState) }
    val query = LearningQuery(learningQuery, learningType, learningField, learningSource)
    val learnedRows = remember(ruleState, query) { LearningRules.filter(ruleState.learned, query) }

    SubPageScaffold(title = section.title, onBack = onBack) { padding ->
        SectionLazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbCardDensity.gap),
        ) {
            if (section == RecognitionRuleSection.APPS) {
                item {
                    InsetGroup {
                        packages.forEachIndexed { index, pkg ->
                            val selected = pkg in whitelist
                            ListRow(
                                title = appLabel(pkg),
                                dividerAbove = index > 0,
                                trailing = {
                                    AbSwitch(selected) { onToggleApp(pkg) }
                                },
                                onClick = { onToggleApp(pkg) },
                            )
                        }
                    }
                }
            }

            if (section == RecognitionRuleSection.LEARNING) {
                item {
                    InsetGroup {
                        ListRow(
                            title = "本地学习",
                            trailing = {
                                AbSwitch(ruleState.learningEnabled) { LocalRuleStore.setLearningEnabled(it) }
                            },
                            onClick = { LocalRuleStore.setLearningEnabled(!ruleState.learningEnabled) },
                        )
                        InsetDivider()
                        ListRow(
                            title = "已学习",
                            trailing = {
                                Text("${ruleState.learned.size} 条", style = AbType.footnote, color = p.secondaryLabel)
                            },
                            chevron = true,
                            onClick = { showLearned = !showLearned },
                        )
                        InsetDivider()
                        ListRow(
                            title = "待生效",
                            trailing = {
                                Text("${effectivePending.size} 条", style = AbType.footnote, color = p.secondaryLabel)
                            },
                            chevron = true,
                            onClick = { showPending = !showPending },
                        )
                        InsetDivider()
                        ListRow(
                            title = "清空学习记忆",
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
                                if (ruleState.learned.isEmpty()) "暂无学习记忆" else "无匹配记忆",
                                if (ruleState.learned.isEmpty()) "保存账单修改分类或账户后将自动记录。"
                                else "换个关键词或筛选条件。",
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
                                    AbTextButton("删除", onClick = { pendingDelete = rule.id }, color = p.red)
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
                                    AbTextButton("删除", onClick = { LocalRuleStore.deletePending(pending.key) }, color = p.red)
                                },
                            )
                        }
                    }
                    if (effectivePending.isNotEmpty()) {
                        item {
                            InsetGroup {
                                ListRow(
                                    title = "清除待生效确认",
                                    destructive = true,
                                    onClick = { LocalRuleStore.clearPending() },
                                )
                            }
                        }
                    }
                }
            }

            if (section == RecognitionRuleSection.MEALS) mealSection(mealState)
        }
    }

    pendingDelete?.let { id ->
        val rule = ruleState.learned.firstOrNull { it.id == id }
        AppAlertDialog(
            title = "删除记忆？",
            message = "移除「${rule?.merchant ?: "该记录"}」的推荐偏好，不影响已有账单。",
            onDismiss = { pendingDelete = null },
            confirmLabel = "删除",
            onConfirm = {
                LocalRuleStore.deleteLearned(id)
                pendingDelete = null
            },
            destructive = true,
        )
    }
    if (confirmClearLearned) {
        AppAlertDialog(
            title = "清空学习记忆？",
            message = "将清空全部学习记录，不影响已有账单。",
            onDismiss = { confirmClearLearned = false },
            confirmLabel = "清空",
            onConfirm = {
                LocalRuleStore.clearLearned()
                confirmClearLearned = false
            },
            destructive = true,
        )
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
    else Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
