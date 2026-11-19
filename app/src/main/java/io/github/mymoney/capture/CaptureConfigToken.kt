package io.github.mymoney.capture

import android.content.Context
import io.github.mymoney.data.CaptureMergePolicy
import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.Settings

/** Snapshot token of active page-capture configuration; stale callbacks fail closed. */
internal object CaptureConfigToken {
    fun of(context: Context): String {
        val s = Settings(context)
        return CaptureMergePolicy.digest(
            MiniJson.encode(
                listOf(
                    s.captureRevision,
                    s.enabled,
                    s.pageEnabled,
                    s.captureMode?.name,
                    s.autoConfirm,
                    s.whitelist.sorted(),
                ),
            ),
        )
    }
}
