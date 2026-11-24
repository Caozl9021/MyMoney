package io.github.mymoney.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billkit.BillType
import io.github.mymoney.data.LedgerConfig
import io.github.mymoney.data.LocalRuleStore
import io.github.mymoney.data.MealRules
import io.github.mymoney.data.MealWindow
import io.github.mymoney.ui.design.AbCardDensity
import io.github.mymoney.ui.design.AbSpacing
import io.github.mymoney.ui.design.AbTextField
import io.github.mymoney.ui.design.AbType
import io.github.mymoney.ui.design.GroupFooter
import io.github.mymoney.ui.design.GroupHeader
import io.github.mymoney.ui.design.InsetGroup
import io.github.mymoney.ui.design.ListRow
import io.github.mymoney.ui.design.LocalAbPalette
import io.github.mymoney.ui.design.PrimaryButton

internal class MealSettingsState(
    val windows: List<MealWindow>,
    val message: String?,
    val isError: Boolean,
    val missingMealSubs: List<String>,
    val changeWindow: (Int, MealWindow) -> Unit,
    val saveMeals: () -> Unit,
    val fillMeals: () -> Unit,
)

@Composable
internal fun rememberMealSettingsState(): MealSettingsState {
    val storeState by LocalRuleStore.state.collectAsStateWithLifecycle()
    var windows by remember { mutableStateOf(storeState.mealWindows) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var foodSubsRevision by remember { mutableStateOf(0) }
    LaunchedEffect(storeState.mealWindows) {
        windows = storeState.mealWindows
    }
    val existingFoodSubs = remember(foodSubsRevision, windows) {
        runCatching { LedgerConfig.subsOf(BillType.EXPENSE, "餐饮") }.getOrDefault(emptyList())
    }
    val missingMealSubs = remember(windows, existingFoodSubs) {
        windows.map { it.subCategory.trim() }.filter { it.isNotEmpty() && it !in existingFoodSubs }.distinct()
    }

    return MealSettingsState(
        windows = windows,
        message = message,
        isError = isError,
        missingMealSubs = missingMealSubs,
        changeWindow = { index, window ->
            windows = windows.toMutableList().also { it[index] = window }
            message = null
            isError = false
        },
        saveMeals = {
            val normalized = windows.map {
                it.copy(
                    from = MealRules.normalizeTime(it.from),
                    to = MealRules.normalizeTime(it.to),
                )
            }
            runCatching {
                LocalRuleStore.saveMealWindows(normalized)
                windows = normalized
                isError = false
                message = "餐段已保存"
            }.onFailure {
                isError = true
                message = "未保存：${it.message}"
            }
        },
        fillMeals = {
            LedgerConfig.addMealSubCategories()
            foodSubsRevision++
            isError = false
            message = "已补齐餐饮子类"
        },
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
    item { GroupFooter("未覆盖时段将保留原分类，不自动填充餐段。") }
    state.message?.let { message ->
        item {
            val p = LocalAbPalette.current
            Text(
                text = message,
                style = AbType.footnote,
                color = if (state.isError) p.red else p.accent,
                modifier = Modifier.padding(horizontal = AbSpacing.xs),
            )
        }
    }
    item { PrimaryButton(text = "保存", modifier = Modifier.fillMaxWidth(), onClick = state.saveMeals) }
    item {
        InsetGroup {
            ListRow(
                title = "补齐餐饮子类",
                subtitle = if (state.missingMealSubs.isNotEmpty()) {
                    "餐饮缺少：${state.missingMealSubs.joinToString("、")}"
                } else {
                    "已包含全部餐段子类"
                },
                onClick = state.fillMeals,
            )
        }
    }
}
