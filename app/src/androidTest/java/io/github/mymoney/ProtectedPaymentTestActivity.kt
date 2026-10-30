package io.github.mymoney

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Separate test APK UID; reproduces the documented system overlay protection, no real payment. */
class ProtectedPaymentTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.VERSION.SDK_INT >= 31)
        window.setHideOverlayWindows(true)
        setContentView(TextView(this).apply {
            text = "TEST PAYMENT WINDOW PROTECTION"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 60, 120))
        })
    }
}
