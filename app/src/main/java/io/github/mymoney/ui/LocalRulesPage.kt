package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.LocalRuleStore
import io.github.mymoney.data.MealWindow
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbTextField
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.PrimaryButton

internal class MealSettingsState(
    val windows: List<MealWindow>,
    val message: String?,
    val changeWindow: (Int, MealWindow) -> Unit,
    val saveMeals: () -> Unit,
    val fillMeals: () -> Unit,
)

@Composable
internal fun rememberMealSettingsState(): MealSettingsState {
    var windows by remember { mutableStateOf(LocalRuleStore.snapshot().mealWindows) }
    var message by remember { mutableStateOf<String?>(null) }

    return MealSettingsState(
        windows = windows,
        message = message,
        changeWindow = { index, window -> windows = windows.toMutableList().also { it[index] = window } },
        saveMeals = {
            message = runCatching { LocalRuleStore.saveMealWindows(windows); "餐段已保存" }
                .getOrElse { "未保存：${it.message}" }
        },
        fillMeals = { LedgerConfig.addMealSubCategories(); message = "已补齐餐饮子类" },
    )
}

internal fun LazyListScope.mealSection(state: MealSettingsState) {
    item { GroupHeader("餐饮时段") }
    items(state.windows.size) { index ->
        val window = state.windows[index]
        InsetGroup {
            Column(
                Modifier.fillMaxWidth().padding(AbCardDensity.content),
                verticalArrangement = Arrangement.spacedBy(AbSpacing.s),
            ) {
                Text(window.subCategory, style = AbType.headline, color = LocalAbPalette.current.label)
                Row(horizontalArrangement = Arrangement.spacedBy(AbSpacing.s)) {
                    AbTextField(
                        value = window.from,
                        onValueChange = { value -> state.changeWindow(index, window.copy(from = value)) },
                        placeholder = "开始 HH:mm",
                        modifier = Modifier.weight(1f),
                    )
                    AbTextField(
                        value = window.to,
                        onValueChange = { value -> state.changeWindow(index, window.copy(to = value)) },
                        placeholder = "结束 HH:mm",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    item { PrimaryButton(text = "保存", modifier = Modifier.fillMaxWidth(), onClick = state.saveMeals) }
    item {
        InsetGroup {
            ListRow(
                title = "补齐餐饮子类",
                onClick = state.fillMeals,
            )
        }
    }
}
