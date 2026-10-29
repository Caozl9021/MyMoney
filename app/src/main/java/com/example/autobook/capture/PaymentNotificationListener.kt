package com.example.autobook.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.autobook.data.Settings
import com.example.autobook.data.CaptureMode
import com.example.billkit.notification.NotificationObservation
import com.example.billkit.notification.NotificationParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

/** Dedicated notification input; reads selected packages only and never opens their PendingIntent.
 * Access to notifications, our own notification permission and overlay access are separate grants.
 */
class PaymentNotificationListener : NotificationListenerService() {
    private var connectedAt = Long.MAX_VALUE
    private val baselineInstances = LinkedHashSet<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedAt = System.currentTimeMillis()
        baselineInstances.clear()
        // Existing active notifications establish a baseline, not missed transactions to replay.
        runCatching { activeNotifications }.getOrNull().orEmpty().take(MAX_BASELINE).forEach { notification ->
            baselineInstances += instanceKey(notification)
        }
        instance = this
        mutableBound.value = true
    }

    override fun onListenerDisconnected() {
        if (instance === this) { instance = null; mutableBound.value = false }
        connectedAt = Long.MAX_VALUE
        super.onListenerDisconnected()
        requestReconnect(this)
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; mutableBound.value = false }
        connectedAt = Long.MAX_VALUE
        super.onDestroy()
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        notification ?: return
        if (instance !== this || !mutableBound.value) return
        val settings = Settings(this)
        val pkg = notification.packageName
        // Inspect package and state before touching any message extras.
        if (settings.captureMode == CaptureMode.ROOT || !settings.enabled || !settings.notificationCaptureEnabled || pkg == packageName || pkg !in settings.whitelist) return
        val token = NotificationIngress.configurationToken(this)
        val baseline = notification.postTime <= connectedAt || instanceKey(notification) in baselineInstances
        val observation = buildObservation(notification, token, baseline, readText = !baseline)
        NotificationIngress.offer(applicationContext, observation)
    }

    /** Baseline callback input never reads message text. Explicit diagnostics may read it without
     * changing the baseline marker or sending that observation through the transaction ingress.
     */
    private fun buildObservation(
        notification: StatusBarNotification,
        token: String,
        baseline: Boolean,
        readText: Boolean,
    ): NotificationObservation {
        val n = notification.notification
        val extras = n.extras
        var remaining = MAX_TEXT_CHARS
        var truncated = false
        fun text(key: String): String? {
            if (!readText) return null
            val value = runCatching { extras.getCharSequence(key)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val length = minOf(value.length, MAX_FIELD_CHARS, remaining)
            if (length != value.length) truncated = true
            remaining -= length
            return value.take(length)
        }
        val template = if (!readText) null else runCatching { extras.getString(Notification.EXTRA_TEMPLATE) }.getOrNull()?.take(MAX_META_CHARS)
        // Session IDs are installed-local, contain no Android/device identifier and are not an authorization.
        return NotificationObservation(
            eventId = UUID.randomUUID().toString(), packageName = notification.packageName, deviceScope = deviceScope(this),
            notificationKey = notification.key.take(MAX_KEY_CHARS), notificationId = notification.id,
            notificationTag = notification.tag?.take(MAX_META_CHARS), userScope = notification.user.toString(),
            postTime = notification.postTime, receivedAt = System.currentTimeMillis(),
            title = text(Notification.EXTRA_TITLE), text = text(Notification.EXTRA_TEXT), bigText = text(Notification.EXTRA_BIG_TEXT),
            channelId = n.channelId?.take(MAX_META_CHARS), category = n.category?.take(MAX_META_CHARS), template = template,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isMessagingStyle = template?.endsWith("MessagingStyle") == true ||
                (readText && runCatching { extras.containsKey(Notification.EXTRA_MESSAGES) }.getOrDefault(false)),
            isOngoing = notification.isOngoing, isReconnectBaseline = baseline,
            truncated = truncated || notification.key.length > MAX_KEY_CHARS || (notification.tag?.length ?: 0) > MAX_META_CHARS,
            configurationToken = token,
        )
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        // Dismissal or replacement says nothing about whether payment succeeded. Do not mutate bills.
        super.onNotificationRemoved(notification)
    }

    private fun instanceKey(notification: StatusBarNotification): String = digest(
        notification.packageName, notification.key, notification.user.toString(), notification.postTime.toString(),
    )

    companion object {
        private const val MAX_DIAGNOSTIC_SAMPLES = 20
        private const val MAX_BASELINE = 256
        private const val MAX_TEXT_CHARS = 2048
        private const val MAX_FIELD_CHARS = 1024
        private const val MAX_META_CHARS = 256
        private const val MAX_KEY_CHARS = 1024
        private const val REBIND_INTERVAL_MS = 30_000L
        @Volatile private var instance: PaymentNotificationListener? = null
        private val mutableBound = MutableStateFlow(false)
        val bound = mutableBound.asStateFlow()
        private var lastRebindAt = Long.MIN_VALUE

        fun isAuthorized(context: Context): Boolean = runCatching {
            val allowed = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val component = ComponentName(context, PaymentNotificationListener::class.java)
            allowed.split(':').any { ComponentName.unflattenFromString(it) == component }
        }.getOrDefault(false)

        /** User-invoked diagnostic snapshot only. It NEVER feeds active/old messages into capture.
         * Package gates run before reading notification extras; a changed configuration discards
         * the whole snapshot. Callers must keep chat bodies out of raw sample exports.
         */
        fun currentDiagnosticSamples(context: Context): List<NotificationObservation> {
            val current = instance ?: return emptyList()
            if (!mutableBound.value) return emptyList()
            val settings = Settings(context)
            if (!settings.enabled || !settings.notificationCaptureEnabled || !isAuthorized(context)) return emptyList()
            val token = NotificationIngress.configurationToken(context)
            val acceptedPackages = NotificationParser.SUPPORTED_PACKAGES.intersect(settings.whitelist)
            if (acceptedPackages.isEmpty()) return emptyList()
            val notifications = runCatching { current.activeNotifications }.getOrNull().orEmpty()
                .asSequence().filter { it.packageName in acceptedPackages }
                .sortedByDescending { it.postTime }.take(MAX_DIAGNOSTIC_SAMPLES).toList()
            fun stillAllowed(): Boolean = instance === current && mutableBound.value &&
                settings.enabled && settings.notificationCaptureEnabled && isAuthorized(context) &&
                token == NotificationIngress.configurationToken(context)
            val samples = ArrayList<NotificationObservation>(notifications.size)
            for (notification in notifications) {
                if (!stillAllowed()) return emptyList()
                // A snapshot may contain a group summary AND its real children. Keep their flags;
                // diagnostic consumers choose useful children and must not promote summaries.
                if (notification.packageName !in settings.whitelist) continue
                samples += current.buildObservation(notification, token, baseline = true, readText = true)
            }
            return if (stillAllowed()) samples else emptyList()
        }

        /** Only explicit UI refresh or an official disconnect requests one rate-limited rebind. */
        @Synchronized
        fun requestReconnect(context: Context) {
            val settings = Settings(context)
            if (!settings.enabled || !settings.notificationCaptureEnabled || !isAuthorized(context) || mutableBound.value) return
            val now = SystemClock.elapsedRealtime()
            if (lastRebindAt != Long.MIN_VALUE && now - lastRebindAt < REBIND_INTERVAL_MS) return
            lastRebindAt = now
            runCatching { requestRebind(ComponentName(context, PaymentNotificationListener::class.java)) }
        }

        private fun deviceScope(context: Context): String {
            val prefs = context.getSharedPreferences("notification_capture_local", Context.MODE_PRIVATE)
            return prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("installation_id", it).apply()
            }
        }

        private fun digest(vararg values: String): String = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
