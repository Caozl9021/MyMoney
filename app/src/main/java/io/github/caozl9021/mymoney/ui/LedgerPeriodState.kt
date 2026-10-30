package io.github.caozl9021.mymoney.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caozl9021.mymoney.data.LedgerPeriodStore

@Composable
internal fun rememberMonthStartDay(): Int {
    LedgerPeriodStore.init(LocalContext.current)
    val day by LedgerPeriodStore.day.collectAsStateWithLifecycle()
    return day
}
