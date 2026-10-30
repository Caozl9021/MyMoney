package com.example.mymoney.ui

import com.example.mymoney.data.CaptureMode

/** A retained Root choice keeps its configuration accessible during authorization failures. */
internal fun showsLspSettings(mode: CaptureMode?): Boolean = mode == CaptureMode.ROOT
