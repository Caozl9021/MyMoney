package com.example.autobook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.autobook.data.LedgerConfig
import com.example.autobook.data.NoteTemplate
import com.example.autobook.ui.design.*
import com.example.billkit.BillDraft
import com.example.billkit.BillType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteTemplatePage(onBack: () -> Unit) {
    val palette = LocalAbPalette.current
    var saved by rememberSaveable { mutableStateOf(LedgerConfig.noteTemplate) }
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(saved)) }
    var discard by remember { mutableStateOf(false) }
    var example by rememberSaveable { mutableIntStateOf(0) }
    val unknown = remember(value.text) { NoteTemplate.unknownTokens(value.text) }
    fun back() { if (value.text != saved) discard = true else onBack() }
    ActiveBackHandler(enabled = value.text != saved || !LocalPageBackManaged.current) { back() }
    if (discard) DiscardChangesDialog(onContinue = { discard = false }, onDiscard = { value = TextFieldValue(saved); discard = false; onBack() })
    val sample = remember(example) {
        BillDraft(2350, BillType.EXPENSE, System.currentTimeMillis(), if (example == 1) null else "瑞幸咖啡", "零钱", null,
            "com.example.demo", "示例", category = "餐饮", subCategory = if (example == 1) null else "咖啡", account = "微信",
            productName = if (example == 2) "生椰拿铁" else null)
    }
    SubPageScaffold(title = "备注模板", onBack = ::back) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap)) {
            com.example.autobook.ui.design.SectionColumn {
                GroupHeader("模板")
                InsetGroup {
                    BasicTextField(value = value, onValueChange = { value = it }, textStyle = AbType.body.copy(color = palette.label),
                        cursorBrush = SolidColor(palette.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(AbSpacing.m))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s), verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    NoteTemplate.TOKENS.forEach { token ->
                        CapsuleChip(text = token, selected = false, onClick = {
                            val start = value.selection.min; val end = value.selection.max
                            value = TextFieldValue(value.text.replaceRange(start, end, token), TextRange(start + token.length))
                        })
                    }
                }
                if (unknown.isNotEmpty()) InlineMessage("未知变量：${unknown.joinToString()}，请改正或用双花括号保留。", isError = true)
            }
            com.example.autobook.ui.design.SectionColumn {
                GroupHeader("实时预览 · 示例")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    listOf("普通", "缺少字段", "已点选商品").forEachIndexed { index, label ->
                        CapsuleChip(label, selected = example == index, onClick = { example = index })
                    }
                }
                InsetGroup { ListRow(title = NoteTemplate.render(sample, value.text, includeProduct = example == 2).ifBlank { "（空）" },
                    subtitle = if (example == 2) "商品只在你点选建议后填入，此处仅为示例" else "按账单发生日期生成，不修改已有备注") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                PrimaryButton("保存模板", enabled = unknown.isEmpty(), onClick = {
                    LedgerConfig.noteTemplate = value.text; saved = value.text; onBack()
                })
                SecondaryButton("恢复默认", onClick = { value = TextFieldValue("{商户}", TextRange(4)) })
            }
        }
    }
}
