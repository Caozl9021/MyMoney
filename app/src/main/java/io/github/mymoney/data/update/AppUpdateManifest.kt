package io.github.mymoney.data.update

import io.github.mymoney.data.MiniJson
import java.time.OffsetDateTime

/** The release manifest is deliberately strict: unknown fields are rejected instead of guessed. */
data class AppUpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val publishedAt: String,
    val notes: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
    val signerSha256: List<String>,
) {
    val applicationId get() = AppUpdateManifestCodec.APPLICATION_ID
}

object AppUpdateManifestCodec {
    const val APPLICATION_ID = "io.github.mymoney"
    const val CHANNEL = "stable"
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_NOTES_BYTES = 16 * 1024
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val hex64 = Regex("[a-f0-9]{64}")
    private val rootFields = setOf(
        "kind", "schemaVersion", "applicationId", "channel",
        "versionName", "versionCode", "minSdk", "publishedAt", "notes", "apk",
    )
    private val apkFields = setOf("assetName", "sizeBytes", "sha256", "signerSha256")

    fun parse(text: String): AppUpdateManifest {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "更新说明文件超过大小限制" }
        val root = MiniJson.decode(text) as? Map<*, *> ?: error("更新说明不是 JSON 对象")
        require(root.keys.map { it.toString() }.toSet() == rootFields) { "更新说明字段不完整或包含未知字段" }
        require(root.string("kind") == "MyMoneyAppUpdate") { "更新说明类型不受支持" }
        require(root.long("schemaVersion") == 1L) { "更新说明版本不受支持" }
        require(root.string("applicationId") == APPLICATION_ID) { "更新包不是麦米 My Money" }
        require(root.string("channel") == CHANNEL) { "更新渠道不受支持" }
        val versionName = root.string("versionName")
        require(versionName.isNotBlank() && versionName.length <= 64) { "版本名称无效" }
        val versionCode = root.long("versionCode")
        require(versionCode > 0) { "版本号必须为正整数" }
        val minSdk = root.int("minSdk")
        require(minSdk in 1..1000) { "最低系统版本无效" }
        val publishedAt = root.string("publishedAt")
        runCatching { OffsetDateTime.parse(publishedAt) }.getOrElse { error("发布时间格式无效") }
        val notes = root.string("notes")
        require(notes.toByteArray(Charsets.UTF_8).size <= MAX_NOTES_BYTES) { "更新说明过长" }
        val apk = root["apk"] as? Map<*, *> ?: error("更新说明缺少 APK")
        require(apk.keys.map { it.toString() }.toSet() == apkFields) { "APK 字段不完整或包含未知字段" }
        val assetName = apk.string("assetName")
        require(assetName.isNotBlank() && assetName.length <= 128 && '/' !in assetName && '\\' !in assetName &&
            ".." !in assetName && !assetName.contains("debug", ignoreCase = true) &&
            !assetName.contains("unsigned", ignoreCase = true) && assetName.endsWith(".apk", ignoreCase = true)) {
            "APK 文件名无效"
        }
        val sizeBytes = apk.long("sizeBytes")
        require(sizeBytes in 1..MAX_APK_BYTES) { "APK 大小无效" }
        val sha256 = apk.string("sha256")
        require(hex64.matches(sha256)) { "APK 摘要格式无效" }
        val signers = (apk["signerSha256"] as? List<*>)?.map { it as? String ?: error("签名摘要格式无效") }
            ?: error("更新说明缺少签名摘要")
        require(signers.isNotEmpty() && signers.size <= 8 && signers.all(hex64::matches)) { "签名摘要格式无效" }
        return AppUpdateManifest(
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            publishedAt = publishedAt,
            notes = notes,
            assetName = assetName,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            signerSha256 = signers.distinct(),
        )
    }

    fun encode(manifest: AppUpdateManifest): String = MiniJson.encode(
        linkedMapOf(
            "kind" to "MyMoneyAppUpdate",
            "schemaVersion" to 1,
            "applicationId" to APPLICATION_ID,
            "channel" to CHANNEL,
            "versionName" to manifest.versionName,
            "versionCode" to manifest.versionCode,
            "minSdk" to manifest.minSdk,
            "publishedAt" to manifest.publishedAt,
            "notes" to manifest.notes,
            "apk" to linkedMapOf(
                "assetName" to manifest.assetName,
                "sizeBytes" to manifest.sizeBytes,
                "sha256" to manifest.sha256,
                "signerSha256" to manifest.signerSha256,
            ),
        ),
    )

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: error("更新说明缺少 $key")
    private fun Map<*, *>.long(key: String): Long =
        (this[key] as? Number)?.toLong() ?: error("更新说明缺少 $key")
    private fun Map<*, *>.int(key: String): Int {
        val value = (this[key] as? Number)?.toLong() ?: error("更新说明缺少 $key")
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "更新说明数字超出范围" }
        return value.toInt()
    }
}
