package io.github.mymoney.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Small bounded HTTP client shared by GitHub rule and App update checks. */
object GitHubHttp {
    const val USER_AGENT = "MyMoney-Updates"
    private const val MAX_REDIRECTS = 6
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private val allowedHosts = setOf(
        "api.github.com",
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun get(initial: String, limit: Int, acceptApi: Boolean = false): ByteArray {
        var current = URL(initial)
        var redirects = 0
        while (true) {
            if (current.protocol != "https" || current.port != -1 || current.userInfo != null ||
                current.host !in allowedHosts
            ) {
                throw IOException("下载地址不受信任")
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", if (acceptApi) "application/vnd.github+json" else "application/octet-stream")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("下载跳转缺少地址")
                connection.disconnect()
                redirects += 1
                if (redirects > MAX_REDIRECTS) throw IOException("下载跳转次数过多，请手动下载")
                current = URL(current, location)
                continue
            }
            if (code == 403 || code == 429) throw IOException("GitHub 请求受限，请稍后重试")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub 暂不可用（$code），请稍后重试")
            val declared = connection.contentLengthLong
            if (declared > limit) throw IOException("更新文件超过大小限制")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw IOException("更新文件超过大小限制")
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        }
    }
}
