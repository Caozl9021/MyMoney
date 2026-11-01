package io.github.mymoney.data.update

import io.github.mymoney.data.GitHubHttp
import io.github.mymoney.data.MiniJson
import java.io.IOException
import java.net.URL

class AppUpdateException(message: String) : IOException(message)

/** Public release discovery for the App itself. Rule releases deliberately do not participate. */
object GitHubAppUpdates {
    const val REPOSITORY = "Caozl9021/MyMoney"
    const val RELEASES_URL = "https://github.com/Caozl9021/MyMoney/releases"
    private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 10
    private val digest = Regex("sha256:[a-f0-9]{64}")

    data class Asset(val name: String, val url: String, val size: Long, val digest: String?)
    data class Release(
        val tagName: String,
        val name: String,
        val htmlUrl: String,
        val publishedAt: String,
        val assets: List<Asset>,
    )
    data class Candidate(
        val manifest: AppUpdateManifest,
        val release: Release,
        val manifestJson: String,
        val apk: Asset,
    )
    data class Selection(val bestCompatible: Candidate?, val bestIncompatible: Candidate?)

    fun parseReleasePage(text: String): List<Release> {
        val rows = MiniJson.decode(text) as? List<*> ?: throw AppUpdateException("GitHub 返回的发布列表无效")
        return rows.mapNotNull { raw ->
            val row = raw as? Map<*, *> ?: return@mapNotNull null
            if (row["draft"] != false || row["prerelease"] != false) return@mapNotNull null
            val tag = row["tag_name"] as? String ?: return@mapNotNull null
            val name = row["name"] as? String ?: tag
            val htmlUrl = (row["html_url"] as? String)?.takeIf(::isReleasePageUrl) ?: RELEASES_URL
            val publishedAt = row["published_at"] as? String ?: ""
            val assets = (row["assets"] as? List<*>).orEmpty().mapNotNull { parseAsset(it) }
            Release(tag, name, htmlUrl, publishedAt, assets)
        }
    }

    fun check(
        currentVersionCode: Long,
        deviceSdk: Int,
        fetch: (String, Int) -> ByteArray = { url, limit ->
            GitHubHttp.get(url, limit, acceptApi = url.startsWith("https://api.github.com/"))
        },
    ): Selection {
        val releases = mutableListOf<Release>()
        for (page in 1..MAX_PAGES) {
            val bytes = fetch(
                "https://api.github.com/repos/$REPOSITORY/releases?per_page=$PAGE_SIZE&page=$page",
                MAX_PAGE_BYTES,
            )
            val rows = parseReleasePage(bytes.toString(Charsets.UTF_8))
            releases += rows
            if (rows.size < PAGE_SIZE) break
            if (page == MAX_PAGES) throw AppUpdateException("发布记录过多，请打开 GitHub Releases 手动确认")
        }

        val candidates = mutableListOf<Candidate>()
        for (release in releases) {
            val manifests = release.assets.filter { it.name == "app-update.json" }
            if (manifests.isEmpty()) continue
            if (manifests.size != 1) throw AppUpdateException("发布 ${release.tagName} 包含多个更新说明")
            val manifestAsset = manifests.single()
            val manifestJson = fetch(manifestAsset.url, AppUpdateManifestCodec.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
            val manifest = try {
                AppUpdateManifestCodec.parse(manifestJson)
            } catch (error: Exception) {
                throw AppUpdateException("发布 ${release.tagName} 的更新说明无效：${error.message}")
            }
            val apkMatches = release.assets.filter { it.name == manifest.assetName }
            if (apkMatches.size != 1) {
                throw AppUpdateException("发布 ${release.tagName} 缺少唯一的 APK 资产（${manifest.assetName}）")
            }
            val apk = apkMatches.single()
            if (apk.size != manifest.sizeBytes) {
                throw AppUpdateException("发布 ${release.tagName} 的 APK 大小与更新说明不一致")
            }
            if (apk.digest != null && apk.digest != manifest.sha256) {
                throw AppUpdateException("发布 ${release.tagName} 的 APK 摘要与更新说明不一致")
            }
            candidates += Candidate(manifest, release, manifestJson, apk)
        }
        return select(candidates, currentVersionCode, deviceSdk)
    }

    fun select(candidates: List<Candidate>, currentVersionCode: Long, deviceSdk: Int): Selection {
        val grouped = candidates.groupBy { it.manifest.versionCode }
        for ((code, items) in grouped) {
            if (items.map { it.manifest.sha256 }.toSet().size > 1) {
                throw AppUpdateException("发现重复版本号 $code 的不同安装包，请到 GitHub Releases 手动确认")
            }
        }
        val newer = grouped.values.map { it.first() }.filter { it.manifest.versionCode > currentVersionCode }
        return Selection(
            bestCompatible = newer.filter { it.manifest.minSdk <= deviceSdk }
                .maxByOrNull { it.manifest.versionCode },
            bestIncompatible = newer.filter { it.manifest.minSdk > deviceSdk }
                .maxByOrNull { it.manifest.versionCode },
        )
    }

    internal fun isAllowedDownloadUrl(value: String): Boolean {
        val url = runCatching { URL(value) }.getOrNull() ?: return false
        return url.protocol == "https" && url.port == -1 && url.userInfo == null &&
            url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    private fun parseAsset(raw: Any?): Asset? {
        val row = raw as? Map<*, *> ?: return null
        val name = row["name"] as? String ?: return null
        if (name.isBlank() || name.length > 160 || '/' in name || '\\' in name || ".." in name) return null
        val value = row["browser_download_url"] as? String ?: return null
        val url = runCatching { URL(value) }.getOrNull() ?: return null
        if (!isAllowedDownloadUrl(value) || !url.path.startsWith("/$REPOSITORY/releases/download/")) return null
        val size = (row["size"] as? Number)?.toLong() ?: return null
        if (size !in 1..AppUpdateManifestCodec.MAX_APK_BYTES) return null
        val rawDigest = row["digest"] as? String
        if (rawDigest != null && !digest.matches(rawDigest)) return null
        return Asset(name, value, size, rawDigest?.removePrefix("sha256:"))
    }

    private fun isReleasePageUrl(value: String): Boolean {
        val url = runCatching { URL(value) }.getOrNull() ?: return false
        return url.protocol == "https" && url.port == -1 && url.userInfo == null &&
            url.host == "github.com" && url.path.startsWith("/$REPOSITORY/releases")
    }
}
