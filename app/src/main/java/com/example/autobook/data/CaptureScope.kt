package com.example.autobook.data

/** Effective collection scope of the self-developed build; never changes stored legacy choices. */
object CaptureScope {
    val packages: Set<String> = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    const val smsSupported: Boolean = false

    fun supports(packageName: String): Boolean = packageName in packages

    fun filterPackages(configured: Set<String>): Set<String> =
        configured.filterTo(linkedSetOf(), ::supports)
}
