package io.github.mymoney.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ephemeral, bounded notices for the existing confirmation card; never a second delivery. */
object OcrReviewNotices {
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val messages = mutable.asStateFlow()
    @Synchronized fun set(id: String, message: String) {
        mutable.value = (mutable.value + (id to message)).entries.toList().takeLast(100).associate { it.toPair() }
    }
}
