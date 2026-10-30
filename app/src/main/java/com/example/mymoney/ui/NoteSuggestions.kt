package com.example.mymoney.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.mymoney.data.AppDb
import com.example.mymoney.data.BillEntity
import com.example.mymoney.data.LocalRuleStore
import com.example.mymoney.data.NoteSuggester
import com.example.mymoney.data.LedgerConfig
import com.example.mymoney.data.NoteTemplate
import com.example.billkit.BillDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 三个确认卡宿主共用：后台读一次最近账单，之后按卡片当前字段实时算建议。
 * 读库失败时就是没有建议，不影响记账。
 */
@Composable
fun rememberNoteSuggestions(excludeId: Long? = null): (BillDraft) -> List<NoteSuggester.Suggestion> {
    val context = LocalContext.current.applicationContext
    var history by remember { mutableStateOf<List<BillEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) {
            runCatching { AppDb.get(context).bills().recentOnce(1000) }.getOrDefault(emptyList())
        }.filterNot { it.id == excludeId }
    }
    val rules = LocalRuleStore.snapshot()
    return remember(history, rules) {
        { current: BillDraft ->
            NoteSuggester.suggest(
                draft = current,
                history = history,
                mealWindows = rules.mealWindows,
                learningEnabled = rules.learningEnabled,
                templateNote = NoteTemplate.render(current, current.noteTemplateSnapshot ?: LedgerConfig.noteTemplate).ifBlank { null },
                currentNote = current.note,
            )
        }
    }
}
