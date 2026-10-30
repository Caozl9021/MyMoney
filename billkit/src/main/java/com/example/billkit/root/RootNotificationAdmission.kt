package com.example.billkit.root

import com.example.billkit.notification.NotificationEnvelopeGuard

/** 实时轮先验信封再占用去重槽位；启动基线仍由调用方无条件登记。 */
object RootNotificationAdmission {
    sealed interface Result {
        data object Accepted : Result
        data object Duplicate : Result
        data class Rejected(val reason: String) : Result
    }

    fun admitLive(notification: NotificationDumpParser.Dumped, dedup: NotificationDumpDedup, now: Long): Result {
        val observation = notification.observation(now) ?: return Result.Rejected("missing-envelope-metadata")
        NotificationEnvelopeGuard.rejection(observation)?.let { return Result.Rejected(it) }
        return if (dedup.accept(notification, now)) Result.Accepted else Result.Duplicate
    }
}
