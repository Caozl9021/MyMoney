package com.example.autobook.lsp

/** Constants contain no privileged API; both isolated processes use the same wire protocol. */
object LspProtocol {
    const val PACKAGE = "com.example.autobook"
    const val COMPONENT = "$PACKAGE/$PACKAGE.capture.BillAccessibilityService"
    const val ACTION = "$PACKAGE.lsp.PROBE"
    const val PERMISSION = "$PACKAGE.permission.LSP_PROBE"
    const val AUTHORITY = "$PACKAGE.lsp.status"
    const val METHOD = "report"
    const val TIMEOUT_MS = 4_000L
    const val FRESH_MS = 60_000L

    fun acceptsReply(expectedNonce: String?, nonce: String?, sentAt: Long, now: Long): Boolean =
        expectedNonce != null && nonce == expectedNonce && now >= sentAt && now - sentAt <= TIMEOUT_MS

    /** Unknown reasons are deliberately allowed through, including all user/package lifecycle kills. */
    fun protectKill(packageName: String?, reason: String?, userMatches: Boolean,
                    enabled: Boolean, liveConnection: Boolean, exitReason: Int?): Boolean =
        packageName == PACKAGE && userMatches && enabled && liveConnection &&
            reason == "background restriction" && (exitReason == null || exitReason == 13)
}
