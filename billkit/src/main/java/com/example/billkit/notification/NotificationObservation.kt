package com.example.billkit.notification

import java.security.MessageDigest

/** A notification observation, deliberately separate from a foreground page snapshot.
 * Text is ephemeral input; observation identities and hashes contain no raw message body.
 */
data class NotificationObservation(
    val eventId: String,
    val packageName: String,
    val deviceScope: String,
    val notificationKey: String,
    val notificationId: Int,
    val notificationTag: String?,
    val userScope: String,
    val postTime: Long,
    val receivedAt: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val channelId: String?,
    val category: String?,
    val template: String?,
    val isGroupSummary: Boolean = false,
    val isMessagingStyle: Boolean = false,
    val isOngoing: Boolean = false,
    val isReconnectBaseline: Boolean = false,
    val truncated: Boolean = false,
    val configurationToken: String = "",
) {
    /** One observation instance, not a payment ID. A reused key with a new postTime differs. */
    val observationKey: String get() = "notification:" + digest(
        deviceScope, userScope, packageName, notificationKey, notificationId.toString(), notificationTag, postTime.toString(),
    )
    /** A reused OS slot is only a review hint, never a transaction identity. */
    val reviewSlotKey: String get() = "notification-slot:" + digest(
        deviceScope, userScope, packageName, notificationKey, notificationId.toString(), notificationTag,
    )
    /** Updates can change content under the same observation instance. */
    val contentHash: String get() = digest(title, text, bigText, channelId, category, template,
        isGroupSummary.toString(), isMessagingStyle.toString(), isOngoing.toString())

    companion object {
        private fun digest(vararg values: String?): String {
            // Length prefixes avoid delimiter ambiguity without depending on an Android JSON class.
            val input = values.joinToString("") { value -> if (value == null) "-1:" else "${value.length}:$value" }
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
