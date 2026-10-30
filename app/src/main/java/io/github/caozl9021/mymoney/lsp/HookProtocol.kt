package io.github.caozl9021.mymoney.lsp

import android.os.Bundle

/**
 * 采集模块 → 宿主 App 的投递协议（SPEC §8 阶段 3/4）。
 *
 * 模块代码运行在**目标 App 进程**里（微信 / 支付宝），拿不到宿主的 Application、数据库和
 * Settings，所以它只能把「一条原始凭证」原样交回宿主，判定与落库都在宿主侧做——
 * 这也保证了 `app_data` 表是唯一的规则事实来源（SPEC §7）。
 *
 * 交易和目标加载回执使用同一 provider 的两个独立方法；调用方必须确实属于声明的目标 App。
 * UID 由内核提供，再经包名、加载探测 nonce 等准入校验，不能由请求方自行声明身份。
 */
object HookProtocol {
    // Keep these wire identifiers stable for target processes still running an older module.
    const val AUTHORITY = "io.github.caozl9021.mymoney.lsp.status"
    const val PERMISSION = "io.github.caozl9021.mymoney.permission.LSP_PROBE"

    /** Transaction delivery is separate from the metadata-only loading acknowledgement. */
    const val METHOD = "hook"

    /** Loading probes are independent of transaction delivery and carry no receipt body. */
    const val STATUS_METHOD = "hook_status"
    const val PROBE_ACTION = "io.github.caozl9021.mymoney.lsp.TARGET_PROBE"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_VERSION = "version"
    const val EXTRA_REGISTERED = "registered"
    const val EXTRA_ERROR = "error"
    const val ERROR_NO_METHOD = "NO_METHOD"
    const val ERROR_VERSION_UNKNOWN = "VERSION_UNKNOWN"
    const val ERROR_INSTALL_FAILED = "INSTALL_FAILED"

    const val EXTRA_APP = "app"
    const val EXTRA_TYPE = "type"
    const val EXTRA_PAYLOAD = "payload"

    const val APP_WECHAT = "com.tencent.mm"
    const val APP_ALIPAY = "com.eg.android.AlipayGphone"
    const val TYPE = "DATA"

    /** 只有这些包名的进程可以往采集通道投递（SPEC §8 阶段 3/4 的数据源）。 */
    val hookedPackages = setOf(APP_WECHAT, APP_ALIPAY)

    /** Never install module hooks in the system framework or a target application's subprocesses. */
    fun shouldCapture(packageName: String?, processName: String?): Boolean =
        packageName != null && packageName in hookedPackages && packageName == processName

    fun statusBundle(app: String, nonce: String, version: String, registered: Int, error: String?): Bundle = Bundle().apply {
        putString(EXTRA_APP, app)
        putString(EXTRA_NONCE, nonce)
        putString(EXTRA_VERSION, version)
        putInt(EXTRA_REGISTERED, registered)
        if (error != null) putString(EXTRA_ERROR, error)
    }

    fun bundle(app: String, payload: String): Bundle = Bundle().apply {
        putString(EXTRA_APP, app)
        putString(EXTRA_TYPE, TYPE)
        putString(EXTRA_PAYLOAD, payload)
    }
}
