package io.github.mymoney.ocr

import android.content.Context
import android.os.Build

/** Device-local consent: not exported with ledger backup or inferred from accessibility permission. */
class OcrPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("screen_text_consent", Context.MODE_PRIVATE)
    val enabled: Boolean get() = LocalOcrEngine.isSupported && prefs.getBoolean("local_screenshot_v1", false)
    val generation: Long get() = prefs.getLong("generation", 0)
    fun setEnabled(value: Boolean): Boolean = synchronized(lock) {
        prefs.edit().putBoolean("local_screenshot_v1", value).putLong("generation", generation + 1).commit()
    }
    companion object { private val lock = Any() }
}
