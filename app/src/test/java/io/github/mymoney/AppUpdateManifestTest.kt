package io.github.mymoney

import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.update.AppUpdateManifest
import io.github.mymoney.data.update.AppUpdateManifestCodec
import io.github.mymoney.data.update.AppUpdateException
import io.github.mymoney.data.update.GitHubAppUpdates
import org.junit.Assert.*
import org.junit.Test

class AppUpdateManifestTest {
    private fun manifest(
        versionName: String = "1.4.0",
        versionCode: Long = 116,
        minSdk: Int = 26,
        assetName: String = "MyMoney-1.4.0-master-release.apk",
        size: Long = 1024,
        sha: String = "a".repeat(64),
        signer: List<String> = listOf("b".repeat(64)),
    ) = AppUpdateManifest(
        versionName = versionName,
        versionCode = versionCode,
        minSdk = minSdk,
        publishedAt = "2026-11-01T16:00:00Z",
        notes = "测试更新",
        assetName = assetName,
        sizeBytes = size,
        sha256 = sha,
        signerSha256 = signer,
    )

    @Test fun validManifestRoundTripsAndRejectsUnknownFields() {
        val text = AppUpdateManifestCodec.encode(manifest())
        assertEquals(116L, AppUpdateManifestCodec.parse(text).versionCode)
        val root = (MiniJson.decode(text) as Map<*, *>).toMutableMap()
        root["unexpected"] = "x"
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManifestCodec.parse(MiniJson.encode(root))
        }
    }

    @Test fun rejectsWrongApplicationIdVersionAndSigner() {
        val root = (MiniJson.decode(AppUpdateManifestCodec.encode(manifest())) as Map<*, *>).toMutableMap()
        root["applicationId"] = "com.example.other"
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestCodec.parse(MiniJson.encode(root)) }

        val badVersion = (MiniJson.decode(AppUpdateManifestCodec.encode(manifest())) as Map<*, *>).toMutableMap()
        badVersion["versionCode"] = 0
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestCodec.parse(MiniJson.encode(badVersion)) }

        val badSigner = (MiniJson.decode(AppUpdateManifestCodec.encode(manifest())) as Map<*, *>).toMutableMap()
        val apk = (badSigner["apk"] as Map<*, *>).toMutableMap()
        apk["signerSha256"] = listOf("short")
        badSigner["apk"] = apk
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestCodec.parse(MiniJson.encode(badSigner)) }
    }

    @Test fun selectsHighestCompatibleAndHigherIncompatible() {
        val candidates = listOf(
            candidate(116, 26),
            candidate(120, 35, "c".repeat(64)),
            candidate(118, 33, "d".repeat(64)),
        )
        val selection = GitHubAppUpdates.select(candidates, currentVersionCode = 115, deviceSdk = 33)
        assertEquals(118L, selection.bestCompatible?.manifest?.versionCode)
        assertEquals(120L, selection.bestIncompatible?.manifest?.versionCode)
        assertNull(GitHubAppUpdates.select(candidates, currentVersionCode = 120, deviceSdk = 35).bestCompatible)
    }

    @Test fun duplicateVersionCodeWithDifferentDigestIsRejected() {
        val candidates = listOf(candidate(116, 26, "a".repeat(64)), candidate(116, 26, "b".repeat(64)))
        assertThrows(AppUpdateException::class.java) {
            GitHubAppUpdates.select(candidates, currentVersionCode = 115, deviceSdk = 35)
        }
    }

    @Test fun checkReadsManifestAndMatchesApkMetadata() {
        val text = AppUpdateManifestCodec.encode(manifest())
        val release = releaseJson(
            assets = listOf(
                asset("app-update.json", "https://github.com/Caozl9021/MyMoney/releases/download/v1.4.0/app-update.json", text.length.toLong()),
                asset("MyMoney-1.4.0-master-release.apk", "https://github.com/Caozl9021/MyMoney/releases/download/v1.4.0/MyMoney-1.4.0-master-release.apk", 1024),
            ),
        )
        val selection = GitHubAppUpdates.check(115, 35) { url, _ ->
            if (url.endsWith("app-update.json")) text.toByteArray() else release.toByteArray()
        }
        assertEquals(116L, selection.bestCompatible?.manifest?.versionCode)
    }

    @Test fun draftAndPrereleaseAreNotSelectable() {
        val draft = MiniJson.encode(listOf(mapOf(
            "draft" to true, "prerelease" to false, "tag_name" to "v1.4.0", "name" to "1.4.0",
            "html_url" to "https://github.com/Caozl9021/MyMoney/releases/tag/v1.4.0",
            "published_at" to "2026-11-01T16:00:00Z", "assets" to emptyList<Any>(),
        )))
        assertTrue(GitHubAppUpdates.parseReleasePage(draft).isEmpty())
        val prerelease = MiniJson.encode(listOf(mapOf(
            "draft" to false, "prerelease" to true, "tag_name" to "v1.4.0", "name" to "1.4.0",
            "html_url" to "https://github.com/Caozl9021/MyMoney/releases/tag/v1.4.0",
            "published_at" to "2026-11-01T16:00:00Z", "assets" to emptyList<Any>(),
        )))
        assertTrue(GitHubAppUpdates.parseReleasePage(prerelease).isEmpty())
    }

    @Test fun checkSkipsReleaseWithoutManifest() {
        val release = MiniJson.encode(listOf(mapOf(
            "draft" to false, "prerelease" to false, "tag_name" to "v1.4.0", "name" to "1.4.0",
            "html_url" to "https://github.com/Caozl9021/MyMoney/releases/tag/v1.4.0",
            "published_at" to "2026-11-01T16:00:00Z",
            "assets" to listOf(mapOf(
                "name" to "MyMoney-rules-1.1.0.mymoney-rules.zip",
                "browser_download_url" to "https://github.com/Caozl9021/MyMoney/releases/download/v1.4.0/MyMoney-rules-1.1.0.mymoney-rules.zip",
                "size" to 100,
                "digest" to "sha256:" + "a".repeat(64),
            )),
        )))
        val selection = GitHubAppUpdates.check(115, 35) { _, _ -> release.toByteArray() }
        assertNull(selection.bestCompatible)
        assertNull(selection.bestIncompatible)
    }

    @Test fun checkRejectsMultipleManifestAssets() {
        val text = AppUpdateManifestCodec.encode(manifest())
        val release = MiniJson.encode(listOf(mapOf(
            "draft" to false, "prerelease" to false, "tag_name" to "v1.4.0", "name" to "1.4.0",
            "html_url" to "https://github.com/Caozl9021/MyMoney/releases/tag/v1.4.0",
            "published_at" to "2026-11-01T16:00:00Z",
            "assets" to listOf(
                asset("app-update.json", "https://github.com/Caozl9021/MyMoney/releases/download/v1.4.0/app-update.json", text.length.toLong()),
                asset("app-update.json", "https://github.com/Caozl9021/MyMoney/releases/download/v1.4.0/app-update.json", text.length.toLong()),
            ),
        )))
        assertThrows(AppUpdateException::class.java) {
            GitHubAppUpdates.check(115, 35) { _, _ -> release.toByteArray() }
        }
    }

    private fun candidate(versionCode: Long, minSdk: Int, sha: String = "a".repeat(64)): GitHubAppUpdates.Candidate {
        val m = manifest(versionName = "1.4.$versionCode", versionCode = versionCode, minSdk = minSdk, sha = sha)
        return GitHubAppUpdates.Candidate(
            m,
            GitHubAppUpdates.Release("v1.4.$versionCode", "v1.4.$versionCode", GitHubAppUpdates.RELEASES_URL, "", emptyList()),
            AppUpdateManifestCodec.encode(m),
            GitHubAppUpdates.Asset(m.assetName, "https://github.com/Caozl9021/MyMoney/releases/download/v1.4.$versionCode/${m.assetName}", m.sizeBytes, m.sha256),
        )
    }

    private fun releaseJson(assets: List<Map<String, Any>>) = MiniJson.encode(listOf(mapOf(
        "draft" to false,
        "prerelease" to false,
        "tag_name" to "v1.4.0",
        "name" to "My Money 1.4.0",
        "html_url" to "https://github.com/Caozl9021/MyMoney/releases/tag/v1.4.0",
        "published_at" to "2026-11-01T16:00:00Z",
        "assets" to assets,
    )))

    private fun asset(name: String, url: String, size: Long) = mapOf(
        "name" to name,
        "browser_download_url" to url,
        "size" to size,
        "digest" to "sha256:" + "a".repeat(64),
    )
}
