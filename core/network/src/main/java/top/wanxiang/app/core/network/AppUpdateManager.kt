package top.wanxiang.app.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wanxiang.app.core.model.AppUpdateInfo
import top.wanxiang.app.core.model.CloudLatestVersion
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 万象 · 应用版本更新管理器 (GitHub Releases API)
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val wanxiangCloudClient: WanxiangCloudClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    companion object {
        const val GITHUB_REPO = "peakSee/Wanxiang"
        const val GITHUB_REPO_URL = "https://github.com/peakSee/Wanxiang"
        const val QQ_GROUP_ID = "905971993"
        private const val RELEASES_API = "https://api.github.com/repos/peakSee/Wanxiang/releases/latest"
    }

    /**
     * 向 GitHub 请求最新 Release 信息并与当前版本比对
     */
    suspend fun checkUpdate(currentVersionName: String): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "WanXiang-App/${currentVersionName}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub 响应错误 HTTP ${response.code}")
                }
                val body = response.body.string()
                val jsonElement = json.parseToJsonElement(body).jsonObject

                val tagName = jsonElement["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val latestVersion = tagName.removePrefix("v").trim()
                val title = jsonElement["name"]?.jsonPrimitive?.content ?: tagName
                val bodyText = jsonElement["body"]?.jsonPrimitive?.content.orEmpty()
                val htmlUrl = jsonElement["html_url"]?.jsonPrimitive?.content ?: GITHUB_REPO_URL
                val publishedAt = jsonElement["published_at"]?.jsonPrimitive?.content.orEmpty()

                // 查找 assets 中的 apk 文件
                var apkUrl: String? = null
                var apkSize: Long? = null
                val assets = jsonElement["assets"]?.jsonArray
                if (assets != null) {
                    for (asset in assets) {
                        val assetObj = asset.jsonObject
                        val name = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content
                            apkSize = assetObj["size"]?.jsonPrimitive?.longOrNull
                            break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(latestVersion, currentVersionName)

                AppUpdateInfo(
                    currentVersion = currentVersionName,
                    latestVersion = latestVersion.ifBlank { currentVersionName },
                    hasUpdate = hasUpdate,
                    releaseTitle = title,
                    releaseNotes = bodyText,
                    releaseUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    publishedAt = publishedAt,
                )
            }
        }
    }

    /**
     * 拉取云端版本（wanxiang.latest_version）并与当前 versionCode 比对。
     * 云端不可达/无更新返回 null，调用方回退纯 GitHub Releases 逻辑。
     */
    suspend fun checkCloudVersion(currentVersionCode: Int): CloudLatestVersion? {
        val config = wanxiangCloudClient.getAllConfig().getOrNull() ?: return null
        val cloud = wanxiangCloudClient.parseLatestVersion(config) ?: return null
        return if (cloud.versionCode > currentVersionCode) cloud else null
    }

    /**
     * 合并 GitHub Releases 与云端版本源：云端（versionCode 更大 + 明确 forceUpdate）优先，
     * 云端不可达/无更新时回退纯 GitHub。静默降级，不因云端故障阻断更新检查。
     */
    /**
     * 纯后台更新检查：只认云端 wanxiang.latest_version（versionCode 比对 + forceUpdate + apkUrl）。
     * 云端不可达时静默降级为「已是最新」，不再依赖 GitHub Releases。
     */
    suspend fun checkUpdateMerged(currentVersionName: String): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val cloud = wanxiangCloudClient.getAllConfig().getOrNull()?.let { wanxiangCloudClient.parseLatestVersion(it) }
        val currentCode = currentVersionCode()
        if (cloud == null) {
            return@withContext Result.success(AppUpdateInfo(
                currentVersion = currentVersionName,
                latestVersion = currentVersionName,
                hasUpdate = false,
                releaseTitle = "",
                releaseNotes = "",
                releaseUrl = GITHUB_REPO_URL,
            ))
        }
        Result.success(AppUpdateInfo(
            currentVersion = currentVersionName,
            latestVersion = cloud.versionName,
            hasUpdate = cloud.versionCode > currentCode,
            releaseTitle = cloud.versionName,
            releaseNotes = cloud.changelog,
            releaseUrl = cloud.apkUrl.ifBlank { GITHUB_REPO_URL },
            apkDownloadUrl = cloud.apkUrl.ifBlank { null },
            versionCode = cloud.versionCode,
            forceUpdate = cloud.forceUpdate,
        ))
    }

    private fun currentVersionCode(): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrNull() ?: 0

    /**
     * 下载 APK 文件并报告下载进度
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败 HTTP ${response.code}")
            }

            val body = response.body
            val contentLength = body.contentLength().takeIf { it > 0 }
            val downloadDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(downloadDir, "wanxiang-latest.apk")
            if (apkFile.exists()) apkFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, contentLength)
                    }
                    output.flush()
                }
            }

            apkFile
        }
    }

    /**
     * 调起系统安装器安装 APK
     */
    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 语义化版本比对：latest > current 返回 true
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val latestParts = latest.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
