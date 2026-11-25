package io.github.mymoney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billkit.BillDraft
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.MerchantNoteRule
import io.github.mymoney.data.NoteFillConfig
import io.github.mymoney.data.NoteFillMode
import io.github.mymoney.data.NoteFillPolicy
import io.github.mymoney.data.NoteFillStore
import io.github.mymoney.data.NoteTemplate
import io.github.mymoney.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun ruleAppName(app: String) = if (app == "com.tencent.mm") "微信" else "支付宝"
private fun noteTypeName(type: String) = when (type) {
    "INCOME" -> "收入"
    "REFUND" -> "退款"
    else -> "支出"
}

@Composable
internal fun NoteFillRulesPage(
    onBack: () -> Unit,
    onOpenTemplate: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { io.github.mymoney.data.Settings(context) }
    var suggestionsEnabled by remember { mutableStateOf(settings.noteSuggestionsEnabled) }
    val p = LocalAbPalette.current
    val config by NoteFillStore.state.collectAsStateWithLifecycle()
    val storedError by NoteFillStore.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    val currentTemplate = LedgerConfig.noteTemplate

    SubPageScaffold("备注", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AbSpacing.page,
                end = AbSpacing.page,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap),
        ) {
            item {
                InsetGroup {
                    ListRow(
                        title = "备注模板",
                        leading = { SettingsIconBadge("备注模板", AbIcons.Note) },
                        trailing = {
                            Text(
                                currentTemplate.ifBlank { "空" },
                                style = AbType.footnote,
                                color = p.secondaryLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        chevron = true,
                        onClick = onOpenTemplate,
                    )
                    ListRow(
                        title = "商品说明优先",
                        leading = { SettingsIconBadge("商品说明优先", AbIcons.Filter) },
                        dividerAbove = true,
                        trailing = {
                            AbSwitch(config.productFirst) { checked ->
                                if (!busy) {
                                    busy = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { NoteFillStore.setProductFirst(checked) }
                                        } catch (t: Exception) {
                                            error = t.message
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }
                        },
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { NoteFillStore.setProductFirst(!config.productFirst) }
                                    } catch (t: Exception) {
                                        error = t.message
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                    )
                    ListRow(
                        title = "备注快捷建议",
                        subtitle = "关闭后确认卡不再展示下方建议气泡",
                        leading = { SettingsIconBadge("备注快捷建议", AbIcons.Note) },
                        dividerAbove = true,
                        trailing = {
                            AbSwitch(suggestionsEnabled) { checked ->
                                suggestionsEnabled = checked
                                settings.noteSuggestionsEnabled = checked
                            }
                        },
                        onClick = {
                            val next = !suggestionsEnabled
                            suggestionsEnabled = next
                            settings.noteSuggestionsEnabled = next
                        },
                    )
                }
            }
            (error ?: storedError)?.let { e ->
                item { InsetGroup { ListRow(title = e) } }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AbLayout.headingGap)) {
                    GroupHeader(
                        if (config.rules.isEmpty()) "商家备注规则"
                        else "商家备注规则 · ${config.rules.size} 条",
                    )
                    if (config.rules.isEmpty()) {
                        InsetGroup { ListRow(title = "暂无商家规则") }
                    } else {
                        InsetGroup {
                            config.rules.forEachIndexed { index, rule ->
                                val detail = "${ruleAppName(rule.app)} · ${noteTypeName(rule.type)} · ${rule.mode.label}" +
                                    if (rule.mode == NoteFillMode.FIXED) "：${rule.text}" else ""
                                ListRow(
                                    title = rule.merchant,
                                    subtitle = detail,
                                    dividerAbove = index > 0,
                                    chevron = true,
                                    onClick = { editor = rule.itemKey },
                                )
                            }
                        }
                    }
                }
            }
            item {
                PrimaryButton(
                    "添加规则",
                    onClick = { editor = "new" },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
        }
    }
    editor?.let { key ->
        MerchantNoteRuleEditor(
            config.rules.firstOrNull { it.itemKey == key },
            onClose = { editor = null },
            onSaved = { editor = null },
        )
    }
}

@Composable
private fun Modifier.backgroundForNotes(): Modifier = this.then(Modifier.background(LocalAbPalette.current.pageBg))

@Composable
private fun NoteModeChoices(mode: NoteFillMode, onChange: (NoteFillMode) -> Unit, enabled: Boolean = true) {
    NoteFillMode.entries.forEachIndexed { index, value ->
        ListRow(
            title = value.label,
            dividerAbove = index > 0,
            onClick = { if (enabled) onChange(value) },
            trailing = { if (mode == value) Text("✓", color = LocalAbPalette.current.accent) },
        )
    }
}

@Composable
internal fun MerchantNoteRuleEditor(initial: MerchantNoteRule?, onClose: () -> Unit, onSaved: () -> Unit) {
    var app by rememberSaveable { mutableStateOf(initial?.app ?: "com.eg.android.AlipayGphone") }
    var merchant by rememberSaveable { mutableStateOf(initial?.merchant.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(initial?.type ?: "EXPENSE") }
    var mode by rememberSaveable { mutableStateOf(initial?.mode ?: NoteFillMode.PRODUCT) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rule = MerchantNoteRule(app, merchant.trim(), type, mode, if (mode == NoteFillMode.FIXED) text.trim() else "", initial?.id.orEmpty())
    val dirty = rule != (initial ?: MerchantNoteRule("com.eg.android.AlipayGphone", ""))
    fun close() {
        if (!busy) {
            if (dirty) discard = true else onClose()
        }
    }
    val invalid = runCatching { rule.validate() }.exceptionOrNull()?.message
    fun commit(remove: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (remove) NoteFillStore.deleteRule(initial!!.itemKey)
                    else NoteFillStore.saveRule(rule, initial?.itemKey)
                }
                onSaved()
            } catch (t: Exception) {
                error = t.message
            } finally {
                busy = false
            }
        }
    }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().backgroundForNotes()) {
            SubPageScaffold(if (initial == null) "添加商家规则" else "编辑商家规则", { close() }, reserveDock = false) { padding ->
                LazyColumn(
                    Modifier.fillMaxSize().imePadding(),
                    contentPadding = PaddingValues(
                        start = AbSpacing.page,
                        end = AbSpacing.page,
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(AbSpacing.m),
                ) {
                    item {
                        InsetGroup {
                            InputRow("商家", merchant, { merchant = it.take(80) }, placeholder = "完整商家名")
                            Column(Modifier.padding(AbSpacing.l), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                                Text("应用", style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
                                Segmented(listOf("微信", "支付宝"), if (app == "com.tencent.mm") 0 else 1, {
                                    app = if (it == 0) "com.tencent.mm" else "com.eg.android.AlipayGphone"
                                })
                                Text("类型", style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
                                Segmented(
                                    listOf("支出", "收入", "退款"),
                                    listOf("EXPENSE", "INCOME", "REFUND").indexOf(type),
                                    { type = listOf("EXPENSE", "INCOME", "REFUND")[it] },
                                )
                            }
                        }
                    }
                    item {
                        InsetGroup {
                            NoteModeChoices(mode, { mode = it }, !busy)
                            if (mode == NoteFillMode.FIXED) {
                                InputRow("固定备注", text, { text = NoteTemplate.limit(it) }, placeholder = "输入固定备注", dividerAbove = true)
                            }
                        }
                    }
                    error?.let { e -> item { InsetGroup { ListRow(title = e) } } }
                    item {
                        PrimaryButton("保存", onClick = { commit() }, modifier = Modifier.fillMaxWidth(), enabled = !busy && invalid == null)
                    }
                    if (initial != null) {
                        item {
                            DestructiveButton("删除规则", onClick = { deleting = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        }
                    }
                }
            }
        }
    }
    if (discard) AppAlertDialog(
        title = "放弃修改？",
        message = "当前规则尚未保存。",
        onDismiss = { discard = false },
        confirmLabel = "放弃",
        onConfirm = onClose,
        dismissLabel = "继续编辑",
        destructive = true,
    )
    if (deleting) AppAlertDialog(
        title = "删除规则？",
        message = "不影响已有账单备注。",
        onDismiss = { deleting = false },
        confirmLabel = "删除",
        onConfirm = { commit(true) },
        destructive = true,
    )
}

/** Inline because the same confirmation UI is also hosted in a service overlay window. */
@Composable
internal fun InlineMerchantNoteEditor(draft: BillDraft, template: String, onClose: () -> Unit, onSaved: (BillDraft) -> Unit) {
    val initial = remember(draft.sourcePkg, draft.merchant, draft.type) { NoteFillPolicy.ruleFor(NoteFillStore.state.value, draft) }
    var mode by rememberSaveable { mutableStateOf(initial?.mode ?: NoteFillMode.PRODUCT) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val rule = MerchantNoteRule(draft.sourcePkg, draft.merchant.orEmpty(), draft.type.name, mode, if (mode == NoteFillMode.FIXED) text.trim() else "", initial?.id.orEmpty())
    val invalid = runCatching { rule.validate() }.exceptionOrNull()?.message
    Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
        Text("${ruleAppName(rule.app)} · ${rule.merchant}", style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
        InsetGroup {
            NoteModeChoices(mode, { mode = it }, !busy)
            if (mode == NoteFillMode.FIXED) InputRow("固定备注", text, { text = NoteTemplate.limit(it) }, placeholder = "输入常用备注", dividerAbove = true)
        }
        val preview = NoteFillPolicy.apply(draft.copy(note = null, noteOrigin = null), NoteFillConfig(false, listOf(rule)), template)
        Text("预览：${preview.note ?: "留空"}", style = AbType.footnote, color = LocalAbPalette.current.secondaryLabel)
        error?.let { Text(it, style = AbType.footnote, color = LocalAbPalette.current.red) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
            SecondaryButton("取消", onClick = onClose, modifier = Modifier.weight(1f), enabled = !busy)
            PrimaryButton("保存", onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { NoteFillStore.saveRule(rule, initial?.itemKey) }
                            onSaved(NoteFillStore.prepare(draft, template))
                        } catch (t: Exception) {
                            error = t.message
                        } finally {
                            busy = false
                        }
                    }
                }
            }, modifier = Modifier.weight(1f), enabled = !busy && invalid == null)
        }
    }
}
