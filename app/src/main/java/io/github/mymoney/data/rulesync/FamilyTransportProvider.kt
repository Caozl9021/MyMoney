package io.github.mymoney.data.rulesync

import android.content.Context
import java.net.URL

/** Which wire the family service is reached over. */
enum class FamilyTransportKind { DIRECT, EMBEDDED }

/**
 * 一次调用用哪条传输。
 *
 * 规则只有三条，刻意不猜：
 * 1. 没开内嵌 → 直连（老用户默认，行为与 1.9.0/146 完全一样）。
 * 2. 开了内嵌但不是 64 位 ABI → **明确失败**。计划 §3.3 明令禁止“隐式退回常规网络”，
 *    否则用户以为在走内嵌，流量其实从公网出去了。
 * 3. 其余 → 内嵌。
 */
object FamilyTransportProvider {

    fun kindFor(useEmbedded: Boolean, embeddedAbiAvailable: Boolean): FamilyTransportKind = when {
        !useEmbedded -> FamilyTransportKind.DIRECT
        !embeddedAbiAvailable -> throw FamilyTransportException("此设备不支持内嵌连接：需要 arm64-v8a 或 x86_64")
        else -> FamilyTransportKind.EMBEDDED
    }

    fun kindFor(config: RuleSyncConfig, embeddedAbiAvailable: Boolean): FamilyTransportKind =
        kindFor(config.useEmbedded, embeddedAbiAvailable)

    /** Exact peer derived from the configured service address (scheme/host/port + path prefix). */
    fun derivePeer(serverUrl: String): EmbeddedTsnetTransport.Peer {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw FamilyTransportException("尚未填写家庭服务地址")
        val url = runCatching { URL(trimmed) }.getOrNull()
            ?: throw FamilyTransportException("家庭服务地址格式不正确")
        if (url.userInfo != null || url.ref != null) throw FamilyTransportException("家庭服务地址不能包含账号密码或片段")
        val host = url.host ?: throw FamilyTransportException("家庭服务地址缺少主机名")
        val port = if (url.port > 0) url.port else if (url.protocol.equals("https", true)) 443 else 80
        return EmbeddedTsnetTransport.Peer(
            scheme = url.protocol.lowercase(),
            host = host,
            port = port,
            pathPrefix = "/",
        )
    }

    /** The transport for this call. Throws instead of silently downgrading. */
    fun select(context: Context, config: RuleSyncConfig): FamilyTransport =
        when (kindFor(config.useEmbedded, EmbeddedTsnetTransport.isAvailable())) {
            FamilyTransportKind.DIRECT -> DirectHttpTransport()
            FamilyTransportKind.EMBEDDED -> TsnetSession.transport(context, config)
        }
}
