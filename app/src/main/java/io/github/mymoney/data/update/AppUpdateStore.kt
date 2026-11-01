package io.github.mymoney.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import io.github.mymoney.data.GitHubHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

object AppUpdateStore {
    enum class Status {
        IDLE, CHECKING, UP_TO_DATE, AVAILABLE, INCOMPATIBLE,
        DOWNLOADING, READY, NEED_PERMISSION, INSTALL_LAUNCHED, ERROR,
    }

    data class Downloaded(
        val file: File,
        val manifest: AppUpdateManifest,
        val manifestJson: String,
    )

    data class State(
        val status: Status = Status.IDLE,
        val candidate: GitHubAppUpdates.Candidate? = null,
        val incompatible: GitHubAppUpdates.Candidate? = null,
        val downloaded: Downloaded? = null,
        val progress: Int = 0,
        val message: String? = null,
        val checkedAt: Long? = null,
    )

    private const val DIR = "updates"
    private const val MAX_REDIRECTS = 6
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private var appContext: Context? = null
    @Volatile private var downloadCancelled = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { restoreDownloaded(context.applicationContext) }
    }

    suspend fun checkForUpdates() {
        val context = requireContext()
        mutable.update { it.copy(status = Status.CHECKING, message = null) }
        try {
            val info = packageInfo(context)
            val current = PackageInfoCompat.getLongVersionCode(info)
            val selection = GitHubAppUpdates.check(current, Build.VERSION.SDK_INT)
            val checkedAt = System.currentTimeMillis()
            val candidate = selection.bestCompatible
            val downloaded = mutable.value.downloaded
            if (candidate == null) {
                if (selection.bestIncompatible != null) {
                    mutable.value = State(
                        status = Status.INCOMPATIBLE,
                        incompatible = selection.bestIncompatible,
                        checkedAt = checkedAt,
                    )
                } else {
                    mutable.value = State(
                        status = Status.UP_TO_DATE,
                        checkedAt = checkedAt,
                    )
                }
            } else if (downloaded != null && downloaded.manifest.versionCode == candidate.manifest.versionCode &&
                downloaded.manifest.sha256 == candidate.manifest.sha256
            ) {
                mutable.value = State(
                    status = Status.READY,
                    candidate = candidate,
                    downloaded = downloaded,
                    progress = 100,
                    checkedAt = checkedAt,
                    message = "已下载 ${candidate.manifest.versionName}，可直接安装",
                )
            } else {
                mutable.value = State(
                    status = Status.AVAILABLE,
                    candidate = candidate,
                    checkedAt = checkedAt,
                )
            }
        } catch (error: Exception) {
            mutable.update {
                it.copy(
                    status = Status.ERROR,
                    message = error.message ?: "检查更新失败，请稍后重试",
                )
            }
        }
    }

    suspend fun download() {
        val context = requireContext()
        val candidate = mutable.value.candidate ?: run {
            mutable.update { it.copy(status = Status.ERROR, message = "请先检查更新") }
            return
        }
        downloadCancelled = false
        val dir = updateDir(context)
        val part = File(dir, "update-${UUID.randomUUID()}.part")
        val target = File(dir, "update-${UUID.randomUUID()}.apk")
        mutable.update {
            it.copy(status = Status.DOWNLOADING, progress = 0, message = null, downloaded = null)
        }
        try {
            downloadTo(candidate, part) { progress ->
                mutable.update { it.copy(progress = progress.coerceIn(0, 100)) }
            }
            if (part.length() != candidate.manifest.sizeBytes) {
                throw IOException("下载文件大小与更新说明不一致")
            }
            if (sha256(part) != candidate.manifest.sha256) {
                throw IOException("下载文件摘要校验失败")
            }
            if (!part.renameTo(target)) throw IOException("更新文件保存失败")
            verifyApk(context, target, candidate.manifest)
            val sidecar = File(target.absolutePath + ".json")
            sidecar.writeText(candidate.manifestJson, Charsets.UTF_8)
            mutable.update {
                it.copy(
                    status = Status.READY,
                    progress = 100,
                    downloaded = Downloaded(target, candidate.manifest, candidate.manifestJson),
                    message = "已下载 ${candidate.manifest.versionName}，可直接安装",
                )
            }
        } catch (error: Exception) {
            part.delete()
            target.delete()
            File(target.absolutePath + ".json").delete()
            mutable.update {
                if (downloadCancelled) {
                    it.copy(status = Status.AVAILABLE, progress = 0, message = "下载已取消")
                } else {
                    it.copy(
                        status = Status.ERROR,
                        progress = 0,
                        message = error.message ?: "下载失败，请稍后重试",
                    )
                }
            }
        } finally {
            downloadCancelled = false
        }
    }

    fun cancelDownload() {
        if (mutable.value.status == Status.DOWNLOADING) downloadCancelled = true
    }

    fun install(context: Context) {
        val downloaded = mutable.value.downloaded ?: run {
            mutable.update { it.copy(status = Status.ERROR, message = "没有已校验的安装包") }
            return
        }
        scope.launch {
            try {
                verifyApk(context, downloaded.file, downloaded.manifest)
                val current = PackageInfoCompat.getLongVersionCode(packageInfo(context))
                if (current >= downloaded.manifest.versionCode) {
                    mutable.update { it.copy(status = Status.UP_TO_DATE, message = "当前版本已经是最新版本") }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    ) {
                        mutable.update {
                            it.copy(
                                status = Status.NEED_PERMISSION,
                                message = "请允许麦米安装应用后返回此页继续",
                            )
                        }
                        return@withContext
                    }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.share",
                        downloaded.file,
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    mutable.update {
                        it.copy(status = Status.INSTALL_LAUNCHED, message = "已打开系统安装器，请确认安装")
                    }
                }
            } catch (error: Exception) {
                mutable.update {
                    it.copy(status = Status.ERROR, message = error.message ?: "无法打开系统安装器")
                }
            }
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun clearDownload() {
        val context = appContext ?: return
        updateDir(context).listFiles()?.forEach { it.delete() }
        mutable.update { State(checkedAt = it.checkedAt) }
    }

    private suspend fun restoreDownloaded(context: Context) {
        val files = updateDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            .orEmpty()
        for (file in files) {
            val sidecar = File(file.absolutePath + ".json")
            if (!sidecar.isFile) continue
            val json = runCatching { sidecar.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val manifest = runCatching { AppUpdateManifestCodec.parse(json) }.getOrNull() ?: continue
            if (file.length() != manifest.sizeBytes || sha256(file) != manifest.sha256) continue
            if (runCatching { verifyApk(context, file, manifest) }.isFailure) continue
            mutable.update {
                it.copy(
                    status = Status.READY,
                    downloaded = Downloaded(file, manifest, json),
                    progress = 100,
                    message = "已下载 ${manifest.versionName}，可直接安装",
                )
            }
            return
        }
    }

    private fun downloadTo(candidate: GitHubAppUpdates.Candidate, target: File, onProgress: (Int) -> Unit) {
        var current = URL(candidate.apk.url)
        var redirects = 0
        var connection: HttpURLConnection? = null
        while (true) {
            if (!GitHubAppUpdates.isAllowedDownloadUrl(current.toString())) {
                throw IOException("更新下载地址不受信任")
            }
            connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", GitHubHttp.USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: throw IOException("下载跳转缺少地址")
                connection.disconnect()
                redirects += 1
                if (redirects > MAX_REDIRECTS) throw IOException("下载跳转次数过多，请手动下载")
                current = URL(current, location)
                continue
            }
            if (code == 403 || code == 429) throw IOException("GitHub 请求受限，请稍后重试")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub 暂不可用（$code），请稍后重试")
            val length = connection.contentLengthLong
            if (length > candidate.manifest.sizeBytes || length > AppUpdateManifestCodec.MAX_APK_BYTES) {
                throw IOException("更新文件超过大小限制")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(target).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (downloadCancelled) throw IOException("下载已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > candidate.manifest.sizeBytes) throw IOException("更新文件超过大小限制")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val progress = if (candidate.manifest.sizeBytes == 0L) 0
                        else ((total * 100) / candidate.manifest.sizeBytes).toInt()
                        onProgress(progress)
                    }
                }
            }
            if (length >= 0 && total != length) throw IOException("更新下载不完整")
            if (total != candidate.manifest.sizeBytes) throw IOException("下载文件大小与更新说明不一致")
            if (hex(digest.digest()) != candidate.manifest.sha256) throw IOException("下载文件摘要校验失败")
            return
        }
    }

    private fun verifyApk(context: Context, file: File, manifest: AppUpdateManifest) {
        if (!file.isFile) throw IOException("安装包不存在")
        if (file.length() != manifest.sizeBytes) throw IOException("安装包大小与更新说明不一致")
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("无法读取安装包信息")
        if (archive.packageName != manifest.applicationId || archive.packageName != context.packageName) {
            throw IOException("安装包包名不匹配")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) != manifest.versionCode) {
            throw IOException("安装包版本号不匹配")
        }
        if (archive.versionName != null && archive.versionName != manifest.versionName) {
            throw IOException("安装包版本名称不匹配")
        }
        if (archive.applicationInfo?.minSdkVersion != manifest.minSdk) {
            throw IOException("安装包最低系统版本不匹配")
        }
        val archiveSigners = signerDigests(archive)
        val expected = manifest.signerSha256.toSet()
        if (archiveSigners != expected) throw IOException("安装包签名不匹配")
        val installed = signerDigests(packageInfo(context))
        if (installed != expected) throw IOException("当前安装包签名与更新包不一致")
    }

    private fun packageInfo(context: Context): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun requireContext(): Context = appContext ?: error("AppUpdateStore 尚未初始化")

    private fun updateDir(context: Context): File =
        File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
