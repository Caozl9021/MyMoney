package com.example.autobook.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

internal data class LedgerClock(val date: LocalDate, val zone: ZoneId)

private fun currentLedgerClock(): LedgerClock {
    val zone = ZoneId.systemDefault()
    return LedgerClock(LocalDate.now(zone), zone)
}

/** Calendar midnight, including 23/25-hour days and zones whose midnight is skipped. */
internal fun nextLedgerMidnightDelay(nowMillis: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis)
    val next = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    return (next.toEpochMilli() - nowMillis).coerceAtLeast(1L)
}

/** A single calendar clock shared by ledger screens; no per-second polling. */
@Composable
internal fun rememberLedgerClock(): LedgerClock {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var clock by remember { mutableStateOf(currentLedgerClock()) }
    // Also restart the midnight wait when the wall clock changes within the same date.
    var clockRevision by remember { mutableStateOf(0) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { clock = currentLedgerClock(); clockRevision++ }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(lifecycle, clockRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                clock = currentLedgerClock()
                delay(nextLedgerMidnightDelay(System.currentTimeMillis(), clock.zone))
                clock = currentLedgerClock()
            }
        }
    }
    return clock
}
