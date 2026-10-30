package io.github.caozl9021.mymoney.ui

import io.github.caozl9021.mymoney.data.CaptureMode

/** A retained Root choice keeps its configuration accessible during authorization failures. */
internal fun showsLspSettings(mode: CaptureMode?): Boolean = mode == CaptureMode.ROOT
