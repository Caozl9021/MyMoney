package io.github.mymoney.ui

import io.github.mymoney.data.CaptureMode

/** Enhancement is shown inside recognition details by capability, including an enabled source
 * whose capability was lost. Legacy preset no longer hides the only enhancement entrance. */
@Suppress("UNUSED_PARAMETER")
internal fun showsLspSettings(hookEnabled: Boolean, lspInstalled: Boolean, preset: CaptureMode?): Boolean =
    hookEnabled || lspInstalled
