package io.github.mymoney.data.rulesync

import io.github.mymoney.data.MiniJson
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.util.Base64

class FamilyInviteException(message: String) : IOException(message)

/**
 * 一次性家庭邀请：由家庭服务侧为**这一台**设备生成，只在本机首次入网时使用一次。
 *
 * 两种版本：
 * - **v1（MMJOIN1）**：只含网络授权（ctrl+authKey）与服务地址，配对码另填。
 * - **v2（MMJOIN2，完整连接码）**：在 v1 基础上**多带一个业务配对码 [pairCode]**，
 *   用户粘贴一次即可完成「设地址 + 网络授权 + 业务配对」，不用再单独输地址和配对码。
 *   v2 的 `pairCode` 可空——空时等价于 v1，仍走旧的「单独填配对码」路径。
 *
 * 刻意不做的事：
 * - 不落盘。`authKey` 与 `pairCode` 都只在本进程内存里活到用完为止；节点身份随后的凭证由
 *   tsnet 自己的状态目录承载，并且整个目录用 [TsnetIdentity] 加密封存。
 * - 不接受任意地址。控制面必须 https、服务地址必须过 [isAllowedServiceUrl] 同源的校验，
 *   这样一张邀请串不可能把请求引到第三方主机。
 * - 不支持通配/正则：字段是定长、定型的，坏串直接抛错，不做"尽量猜"。
 */
data class FamilyInvite(
    val ctrlUrl: String,
    val authKey: String,
    val serverUrl: String,
    val deviceName: String = "",
    /** v2 完整连接码里的业务配对码；v1 为 null/空，仍走单独输入。 */
    val pairCode: String = "",
    /**
     * v2 可选：**显式**要求走系统直连而非内嵌 tsnet。仅用于无法起内嵌节点的环境
     * （如半离线测试机），正常家用仍走内嵌。这是显式声明，不触发隐式降级。
     */
    val direct: Boolean = false,
) {
    companion object {
        /** v2 完整连接码前缀。 */
        const val PREFIX = "MMJOIN2."
        /** v1 旧邀请前缀，仍接受以便兼容。 */
        const val PREFIX_V1 = "MMJOIN1."
        const val MAX_INVITE_LENGTH = 4096
        private const val MAX_AUTH_KEY = 512
        private const val MAX_URL = 512
        private const val MAX_NAME = 64
        private const val MAX_PAIR_CODE = 64

        /** Encodes an invite into the copy/paste form the family admin hands over. */
        fun encode(invite: FamilyInvite): String {
            validate(invite)
            val hasPair = invite.pairCode.isNotBlank()
            val body = MiniJson.encode(
                buildMap {
                    put("v", if (hasPair) 2 else 1)
                    put("ctrl", invite.ctrlUrl)
                    put("key", invite.authKey)
                    put("server", invite.serverUrl)
                    put("name", invite.deviceName)
                    if (hasPair) put("code", invite.pairCode)
                    if (hasPair && invite.direct) put("direct", true)
                },
            )
            val prefix = if (hasPair) PREFIX else PREFIX_V1
            return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
        }

        /**
         * Accepts the paste form (MMJOIN1./MMJOIN2.), a raw JSON object, or the deep link
         * `mymoney://join?invite=<encoded>`. Throws [FamilyInviteException] on anything else.
         */
        fun parse(text: String): FamilyInvite {
            val raw = text.trim()
            if (raw.isEmpty()) throw FamilyInviteException("邀请内容为空")
            if (raw.length > MAX_INVITE_LENGTH) throw FamilyInviteException("邀请内容过长")
            val payload = when {
                raw.startsWith("mymoney://", ignoreCase = true) -> inviteFromDeepLink(raw)
                raw.startsWith(PREFIX, ignoreCase = true) -> decodeBody(raw.removePrefix(PREFIX))
                raw.startsWith(PREFIX_V1, ignoreCase = true) -> decodeBody(raw.removePrefix(PREFIX_V1))
                raw.startsWith("{") -> raw
                else -> throw FamilyInviteException("无法识别的邀请格式，请粘贴完整邀请串")
            }
            val map = runCatching { MiniJson.decode(payload) as? Map<*, *> }.getOrNull()
                ?: throw FamilyInviteException("邀请内容不是有效格式")
            val version = (map["v"] as? Number)?.toInt() ?: 0
            if (version != 1 && version != 2) throw FamilyInviteException("邀请版本不支持（$version）")
            val invite = FamilyInvite(
                ctrlUrl = (map["ctrl"] as? String).orEmpty().trim(),
                authKey = (map["key"] as? String).orEmpty().trim(),
                serverUrl = (map["server"] as? String).orEmpty().trim().trimEnd('/'),
                deviceName = (map["name"] as? String).orEmpty().trim(),
                pairCode = if (version >= 2) (map["code"] as? String).orEmpty().trim() else "",
                direct = version >= 2 && (map["direct"] as? Boolean) == true,
            )
            validate(invite)
            return invite
        }

        /** Test support: encodes without validating, so parse-side rejects stay testable. */
        internal fun encodeQuiet(invite: FamilyInvite): String {
            val hasPair = invite.pairCode.isNotBlank()
            val body = MiniJson.encode(
                buildMap {
                    put("v", if (hasPair) 2 else 1)
                    put("ctrl", invite.ctrlUrl)
                    put("key", invite.authKey)
                    put("server", invite.serverUrl)
                    put("name", invite.deviceName)
                    if (hasPair) put("code", invite.pairCode)
                    if (hasPair && invite.direct) put("direct", true)
                },
            )
            val prefix = if (hasPair) PREFIX else PREFIX_V1
            return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
        }

        /** True when the text looks like an invite rather than a bare service address. */
        fun looksLikeInvite(text: String): Boolean {
            val raw = text.trim()
            return raw.startsWith(PREFIX, ignoreCase = true) ||
                raw.startsWith(PREFIX_V1, ignoreCase = true) ||
                raw.startsWith("mymoney://", ignoreCase = true) ||
                raw.startsWith("{")
        }

        /** True when this invite is a full v2 connect code that already carries a pairing code. */
        fun isConnectCode(invite: FamilyInvite): Boolean = invite.pairCode.isNotBlank()

        private fun inviteFromDeepLink(raw: String): String {
            // 自定义 scheme 不走 java.net.URL（它不认 mymoney://），手工拆 host 与参数，
            // 免得为了一个深链把 URL 解析的歧义引进来。
            val sep = raw.indexOf("://")
            if (sep <= 0) throw FamilyInviteException("邀请链接格式不正确")
            val rest = raw.substring(sep + 3)
            val q = rest.indexOf('?')
            val authority = (if (q >= 0) rest.substring(0, q) else rest).trimEnd('/')
            if (!authority.equals("join", ignoreCase = true)) throw FamilyInviteException("邀请链接缺少 join 动作")
            val query = if (q >= 0) rest.substring(q + 1) else ""
            if (query.isEmpty()) throw FamilyInviteException("邀请链接没有参数")
            val value = query.split('&')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
                }
                .firstOrNull { it.first == "invite" }
                ?.second
                ?: throw FamilyInviteException("邀请链接没有邀请串")
            val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrThrow()
            // The deep link may carry either the paste form or the raw JSON payload.
            return when {
                decoded.startsWith(PREFIX, ignoreCase = true) -> decodeBody(decoded.removePrefix(PREFIX))
                decoded.startsWith(PREFIX_V1, ignoreCase = true) -> decodeBody(decoded.removePrefix(PREFIX_V1))
                else -> decoded
            }
        }

        private fun decodeBody(body: String): String {
            val padded = body + "=".repeat((4 - body.length % 4) % 4)
            val bytes = runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
                ?: throw FamilyInviteException("邀请内容无法解码")
            return String(bytes, Charsets.UTF_8)
        }

        private fun validate(invite: FamilyInvite) {
            val key = invite.authKey
            if (key.isEmpty()) throw FamilyInviteException("邀请缺少授权码")
            if (!key.startsWith("tskey-")) throw FamilyInviteException("授权码应以 tskey- 开头")
            if (key.length > MAX_AUTH_KEY) throw FamilyInviteException("授权码过长")
            if (key.any { it.isWhitespace() }) throw FamilyInviteException("授权码不能包含空白字符")
            val ctrl = invite.ctrlUrl
            if (ctrl.isNotEmpty()) {
                if (ctrl.length > MAX_URL) throw FamilyInviteException("控制面地址过长")
                val url = runCatching { URL(ctrl) }.getOrNull()
                    ?: throw FamilyInviteException("控制面地址格式不正确")
                if (!url.protocol.equals("https", ignoreCase = true)) throw FamilyInviteException("控制面地址必须使用 HTTPS")
                if (url.userInfo != null || url.ref != null) throw FamilyInviteException("控制面地址不能包含账号密码或片段")
                if (url.host.isNullOrBlank()) throw FamilyInviteException("控制面地址缺少主机名")
            }
            val server = invite.serverUrl
            if (server.isEmpty()) throw FamilyInviteException("邀请缺少家庭服务地址")
            if (server.length > MAX_URL) throw FamilyInviteException("家庭服务地址过长")
            val url = runCatching { URL(server) }.getOrNull() ?: throw FamilyInviteException("家庭服务地址格式不正确")
            if (url.userInfo != null || url.ref != null) throw FamilyInviteException("家庭服务地址不能包含账号密码或片段")
            if (url.host.isNullOrBlank()) throw FamilyInviteException("家庭服务地址缺少主机名")
            if (!url.protocol.equals("http", true) && !url.protocol.equals("https", true)) {
                throw FamilyInviteException("家庭服务地址只支持 HTTP 或 HTTPS")
            }
            if (invite.deviceName.length > MAX_NAME) throw FamilyInviteException("设备名称过长")
            val code = invite.pairCode
            if (code.length > MAX_PAIR_CODE) throw FamilyInviteException("配对码过长")
            if (code.any { it.isWhitespace() }) throw FamilyInviteException("配对码不能包含空白字符")
        }
    }
}
