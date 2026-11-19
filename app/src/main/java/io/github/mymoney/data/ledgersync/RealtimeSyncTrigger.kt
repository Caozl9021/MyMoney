package io.github.mymoney.data.ledgersync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mymoney.data.rulesync.RuleSyncSettings
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 实时增量同步的智能触发器：纯事件驱动，无常驻连接、无轮询。
 *
 * - 后台/悬浮窗自动记账：8 秒防抖，把紧随其后的备注回填与资产扣减合并成一次 push；
 * - App 前台交互：15 秒闲置防抖，用户切后台/锁屏（最后一个 Activity `onStop`）立即排空；
 * - 离线/服务端不可达：静默转交 WorkManager（联网 + 指数退避），不弹窗、不死循环。
 */
object RealtimeSyncTrigger {
    const val BACKGROUND_DEBOUNCE_MS = 8_000L
    const val FOREGROUND_IDLE_MS = 15_000L
    const val WORK_NAME = "mymoney_realtime_sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeActivities = AtomicInteger(0)
    private val timerLock = Any()
    private var timer: Job? = null

    @Volatile
    private var app: Application? = null

    fun init(application: Application) {
        synchronized(timerLock) {
            if (app != null) return
            app = application
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activeActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                if (activeActivities.decrementAndGet() <= 0) onEnterBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun isForeground(): Boolean = activeActivities.get() > 0

    /** 未配对或用户关闭「新记录自动实时同步」时不排空、不注册后台任务。 */
    fun enabled(context: Context): Boolean =
        RuleSyncSettings.realtimeSyncEnabled(context) && RuleSyncSettings.load(context).paired

    fun debounceMillis(foreground: Boolean): Long =
        if (foreground) FOREGROUND_IDLE_MS else BACKGROUND_DEBOUNCE_MS

    /** 业务写入（账单/资产/配置）落库并入队后调用，合并短时间内的多次修改。 */
    fun notifyChanged(context: Context) {
        val application = context.applicationContext
        if (!enabled(application)) return
        schedule(application, debounceMillis(isForeground()))
    }

    /** 分类树 / 记账周期变化：先写 meta:config，再触发同步。 */
    fun notifyMetaChanged(context: Context) {
        val application = context.applicationContext
        if (!enabled(application)) return
        scope.launch {
            runCatching { LedgerSyncRepository.enqueueMeta(application) }
                .onFailure { android.util.Log.e("RealtimeSync", "enqueue meta failed", it) }
            notifyChanged(application)
        }
    }

    /** 立即排空（切后台、或用户手动点「立即同步」）。 */
    fun flushNow(context: Context) {
        val application = context.applicationContext
        if (!enabled(application)) return
        cancelTimer()
        scope.launch { push(application) }
    }

    fun cancelTimer() {
        synchronized(timerLock) {
            timer?.cancel()
            timer = null
        }
    }

    private fun schedule(context: Context, delayMillis: Long) {
        synchronized(timerLock) {
            timer?.cancel()
            timer = scope.launch {
                delay(delayMillis)
                push(context)
            }
        }
    }

    private fun onEnterBackground() {
        val application = app ?: return
        if (!enabled(application)) return
        cancelTimer()
        scope.launch {
            if (runCatching { LedgerSyncRepository.pendingCount(application) }.getOrDefault(0) > 0) {
                push(application)
            }
        }
    }

    private suspend fun push(context: Context) {
        val pending = runCatching { LedgerSyncRepository.pendingCount(context) }.getOrDefault(0)
        if (pending <= 0) return
        try {
            LedgerSyncCoordinator.pushPending(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 断网/未开 Tailscale/服务端未启动：不打扰用户，交给 WorkManager 等网络恢复。
            scheduleWorker(context)
        }
    }

    private fun scheduleWorker(context: Context) {
        val request = OneTimeWorkRequestBuilder<RealtimeSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure { android.util.Log.e("RealtimeSync", "schedule worker failed", it) }
    }
}

/** 联网后由系统唤醒的补推任务；有网时一次 push 把 Outbox 排空。 */
class RealtimeSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!RealtimeSyncTrigger.enabled(context)) return Result.success()
        return try {
            LedgerSyncCoordinator.pushPending(context)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount >= 8) Result.failure() else Result.retry()
        }
    }
}
