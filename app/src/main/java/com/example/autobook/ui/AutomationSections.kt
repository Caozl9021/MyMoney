package com.example.autobook.ui

import com.example.autobook.data.CaptureMode

/** A retained Root choice keeps its configuration accessible during authorization failures. */
internal fun showsLspSettings(mode: CaptureMode?): Boolean = mode == CaptureMode.ROOT
