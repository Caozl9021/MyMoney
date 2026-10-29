package com.example.autobook.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autobook.data.LedgerPeriodStore

@Composable
internal fun rememberMonthStartDay(): Int {
    LedgerPeriodStore.init(LocalContext.current)
    val day by LedgerPeriodStore.day.collectAsStateWithLifecycle()
    return day
}
