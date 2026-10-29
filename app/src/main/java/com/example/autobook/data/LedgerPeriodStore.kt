package com.example.autobook.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent of capture settings: changing the ledger calendar must never restart capture. */
object LedgerPeriodStore {
    private var preferences: SharedPreferences? = null
    private val mutableDay = MutableStateFlow(1)
    val day = mutableDay.asStateFlow()
    @Synchronized fun init(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("ledger_period", Context.MODE_PRIVATE)
        preferences = prefs
        mutableDay.value = prefs.getInt("month_start_day", 1).takeIf { it in 1..31 } ?: 1
    }
    @Synchronized fun save(context: Context, value: Int) {
        require(value in 1..31) { "每月起始日须为1–31日" }
        init(context)
        check(preferences!!.edit().putInt("month_start_day", value).commit()) { "保存记账周期失败，请重试" }
        mutableDay.value = value
        com.example.autobook.systems.SystemIntegration.refresh(context)
    }
    fun read(context: Context): Int { init(context); return day.value }
}
