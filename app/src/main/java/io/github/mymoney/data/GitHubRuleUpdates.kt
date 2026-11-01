package io.github.mymoney.data

import com.example.billkit.spec.PortableRulePackage
import java.net.URL

/** Only public release metadata and rule ZIPs are requested; no device or ledger data. */
object GitHubRuleUpdates {
    const val REPOSITORY = "Caozl9021/MyMoney"
    data class Release(val version: String, val name: String, val url: String, val size: Long, val digest: String?)
    private val assetName = Regex("MyMoney-rules-([0-9]+\\.[0-9]+\\.[0-9]+)\\.mymoney-rules\\.zip")

    fun parse(text: String): List<Release> {
        val rows = MiniJson.decode(text) as? List<*> ?: error("GitHub 返回的发布列表无效")
        return rows.flatMap { raw ->
            val r = raw as? Map<*, *> ?: return@flatMap emptyList()
            if(r["draft"] != false || r["prerelease"] != false) return@flatMap emptyList()
            (r["assets"] as? List<*>).orEmpty().mapNotNull { item ->
                val a = item as? Map<*, *> ?: return@mapNotNull null
                val name = a["name"] as? String ?: return@mapNotNull null
                val version = assetName.matchEntire(name)?.groupValues?.get(1) ?: return@mapNotNull null
                if(version.split('.').any { it.toIntOrNull() == null }) return@mapNotNull null
                val url = a["browser_download_url"] as? String ?: return@mapNotNull null
                val u = runCatching { URL(url) }.getOrNull() ?: return@mapNotNull null
                val size = (a["size"] as? Number)?.toLong() ?: return@mapNotNull null
                if(u.protocol != "https" || u.host != "github.com" || u.port != -1 || u.userInfo != null ||
                    !u.path.startsWith("/$REPOSITORY/releases/download/") || size !in 1..PortableRulePackage.MAX_ZIP.toLong()) return@mapNotNull null
                val digest = a["digest"] as? String
                if(digest != null && !Regex("sha256:[a-f0-9]{64}").matches(digest)) return@mapNotNull null
                Release(version,name,url,size,digest?.removePrefix("sha256:"))
            }
        }
    }

    fun latest(fetch: (String, Int) -> ByteArray = ::get): Release? {
        val releases = mutableListOf<Release>()
        // Paginate across both App and pure-rule releases, rather than using /latest (an App tag).
        for(page in 1..10) {
            val bytes = fetch("https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=$page", 4*1024*1024)
            val text = bytes.toString(Charsets.UTF_8)
            releases += parse(text)
            val count = (MiniJson.decode(text) as List<*>).size
            if(count < 100) return releases.maxWithOrNull { a,b -> PortableRulePackage.compareVersions(a.version,b.version) }
        }
        error("发布记录过多，请从 GitHub 下载规则包后导入")
    }

    fun download(release: Release, fetch: (String, Int) -> ByteArray = ::get): ByteArray {
        val bytes = fetch(release.url,PortableRulePackage.MAX_ZIP)
        require(bytes.size.toLong() == release.size) { "下载文件不完整，请重新检查更新" }
        require(release.digest == null || PortableRulePackage.hash(bytes) == release.digest) { "下载摘要不一致，原规则已保留" }
        val pack = PortableRulePackage.load(bytes)
        require(pack.version == release.version) { "发布文件名称与包版本不一致" }
        return bytes
    }

    private fun get(initial: String, limit: Int): ByteArray =
        GitHubHttp.get(initial, limit, acceptApi = initial.startsWith("https://api.github.com/"))
}
