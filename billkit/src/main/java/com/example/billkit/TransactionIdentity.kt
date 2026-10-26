package com.example.billkit

import java.security.MessageDigest

/** Common order namespace for page and reviewed notification adapters; never persist raw order IDs. */
object TransactionIdentity {
    fun key(packageName: String, type: BillType, fullOrderId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$packageName|${type.name}|$fullOrderId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
