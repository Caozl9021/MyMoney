package io.github.mymoney.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.billkit.BillDraft
import com.example.billkit.BillType
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.NoteFillStore
import io.github.mymoney.data.NoteTemplate
import io.github.mymoney.ui.design.*

@Composable
internal fun NoteTemplatePage(onBack: () -> Unit) {
    val palette = LocalAbPalette.current
    var saved by rememberSaveable { mutableStateOf(LedgerConfig.noteTemplate) }
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(saved)) }
    var discard by remember { mutableStateOf(false) }
    var rootExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var example by rememberSaveable { mutableIntStateOf(0) }
    val unknown = remember(value.text) { NoteTemplate.unknownTokens(value.text) }
    fun back() { if (value.text != saved) discard = true else onBack() }
    ActiveBackHandler(enabled = value.text != saved || !LocalPageBackManaged.current) { back() }
    SettingsRootExitHandler { allow ->
        if (value.text != saved) { rootExit = allow; discard = true } else allow()
    }
    if (discard) DiscardChangesDialog(onContinue = { discard = false; rootExit = null }, onDiscard = {
        value = TextFieldValue(saved); discard = false
        val leave = rootExit
        rootExit = null
        if (leave != null) leave() else onBack()
    })
    val sample = remember(example) {
        BillDraft(
            2350, BillType.EXPENSE, System.currentTimeMillis(),
            if (example == 1) null else "瑞幸咖啡", "零钱", null,
            "com.example.demo", "示例", category = "餐饮",
            subCategory = if (example == 1) null else "咖啡", account = "微信",
            productName = if (example == 2) "生椰拿铁" else null,
        )
    }
    SubPageScaffold(title = "备注模板", onBack = ::back) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AbSpacing.page),
            verticalArrangement = Arrangement.spacedBy(AbLayout.groupGap),
        ) {
            SectionColumn {
                GroupHeader("模板")
                InsetGroup {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        textStyle = AbType.body.copy(color = palette.label),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(AbSpacing.m),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AbSpacing.s),
                ) {
                    NoteTemplate.TOKENS.forEach { token ->
                        CapsuleChip(text = token, selected = false, onClick = {
                            val start = value.selection.min; val end = value.selection.max
                            value = TextFieldValue(value.text.replaceRange(start, end, token), TextRange(start + token.length))
                        })
                    }
                }
                if (unknown.isNotEmpty()) InlineMessage("未知变量：${unknown.joinToString()}", isError = true)
            }
            SectionColumn {
                GroupHeader("预览")
                Segmented(options = listOf("普通", "缺字段", "含商品"), selected = example, onSelect = { example = it })
                InsetGroup {
                    ListRow(title = NoteTemplate.render(sample, value.text, includeProduct = example == 2).ifBlank { "（空）" })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                PrimaryButton("保存", enabled = unknown.isEmpty(), onClick = {
                    NoteFillStore.saveNoteTemplate(value.text); saved = value.text; onBack()
                }, modifier = Modifier.fillMaxWidth())
                SecondaryButton("恢复默认", onClick = { value = TextFieldValue("{商户}", TextRange(4)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
