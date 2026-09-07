package top.wanxiang.app.core.tools

import top.wanxiang.app.core.network.DownloadEvent
import top.wanxiang.app.core.network.DownloadRequest
import top.wanxiang.app.core.network.FileDownloader
import top.wanxiang.app.core.network.ChecksumVerifier
import top.wanxiang.app.runtime.RuntimePathManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FlutterSdkArchive(
    val hostFile: File,
    val guestPath: String,
    val version: String,
)

/**
 * Downloads Flutter SDK archives in the Android app process.
 *
 * The Linux setup script runs inside PRoot and should not own large network
 * transfers. The shared downloader provides HTTPS checks, retries, resume and
 * checksum verification; the archive is placed in the bind-mounted /opt/wanxiang
 * tree so the script can extract it without downloading again.
 */
@Singleton
class FlutterSdkDownloader @Inject constructor(
    private val fileDownloader: FileDownloader,
    private val checksumVerifier: ChecksumVerifier,
    private val pathManager: RuntimePathManager,
    private val json: Json,
) {
    suspend fun prepare(
        distroId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): FlutterSdkArchive {
        val cacheDir = File(pathManager.wanxiangRootDir(distroId), "flutter-cache-arm64").apply { mkdirs() }
        val metadata = File(cacheDir, "releases_linux_arm64.json")
        val release = fetchRelease(metadata)
        val archiveName = release.archive.substringAfterLast('/').ifBlank {
            error("Flutter 发布索引返回了无效 archive：${release.archive}")
        }
        val archive = File(cacheDir, archiveName)
        val cacheValid = archive.isFile && archive.length() > 0L &&
            (release.sha256.isBlank() || runCatching {
                checksumVerifier.verify(archive, release.sha256)
            }.isSuccess)
        if (!cacheValid) {
            archive.delete()
            downloadArchive(release, archive, onProgress)
        }
        return FlutterSdkArchive(
            hostFile = archive,
            guestPath = "/opt/wanxiang/flutter-cache-arm64/$archiveName",
            version = release.version,
        )
    }

    private suspend fun fetchRelease(metadata: File): ReleaseInfo {
        var lastError: Throwable? = null
        for (url in METADATA_URLS) {
            try {
                metadata.delete()
                fileDownloader.download(
                    DownloadRequest(
                        url = url,
                        destination = metadata,
                        partialFile = File("${metadata.absolutePath}.part"),
                        maxAttempts = 3,
                        maxBytes = 32L * 1024L * 1024L,
                    ),
                ).collect { }
                return parseRelease(metadata)
            } catch (throwable: Throwable) {
                lastError = throwable
            }
        }
        throw IllegalStateException(
            "Flutter 发布信息下载失败：${lastError?.message ?: "未知网络错误"}",
            lastError,
        )
    }

    private fun parseRelease(metadata: File): ReleaseInfo {
        val document = json.parseToJsonElement(metadata.readText()).jsonObject
        val armAsset = document["assets"]?.jsonArray?.firstOrNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                .contains("linux_arm64_android_web_sdk")
        }?.jsonObject
        if (armAsset != null) {
            val archive = armAsset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
            check(archive.isNotBlank()) { "ARM64 Flutter 发布资产缺少下载地址" }
            return ReleaseInfo(
                archive = archive,
                sha256 = "",
                version = document["tag_name"]?.jsonPrimitive?.content ?: "stable-arm64",
                absoluteUrl = true,
            )
        }
        val releases = document["releases"]?.jsonArray
            ?: error("Flutter 发布索引缺少 ARM64 assets 字段")
        val release = releases.firstOrNull { item ->
            val objectValue = item.jsonObject
            objectValue["channel"]?.jsonPrimitive?.content == "stable" &&
                objectValue["dart_sdk_arch"]?.jsonPrimitive?.content == "arm64"
        }?.jsonObject ?: error("Flutter 发布索引中没有 stable Linux ARM64 SDK")
        val archive = release["archive"]?.jsonPrimitive?.content.orEmpty()
        val sha256 = release["sha256"]?.jsonPrimitive?.content.orEmpty()
        val version = release["version"]?.jsonPrimitive?.content ?: "stable"
        check(archive.isNotBlank() && sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Flutter 发布索引中的 archive 或 sha256 无效"
        }
        return ReleaseInfo(archive, sha256, version, absoluteUrl = false)
    }

    private suspend fun downloadArchive(
        release: ReleaseInfo,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        var lastError: Throwable? = null
        val bases = if (release.absoluteUrl) GITHUB_ARCHIVE_PROXIES else ARCHIVE_BASE_URLS
        for (base in bases) {
            val url = if (release.absoluteUrl) "${base}${release.archive}" else "${base.trimEnd('/')}/${release.archive}"
            try {
                fileDownloader.download(
                    DownloadRequest(
                        url = url,
                        destination = destination,
                        partialFile = File("${destination.absolutePath}.part"),
                        sha256 = release.sha256.takeIf { it.isNotBlank() },
                        maxAttempts = 4,
                        maxBytes = 2L * 1024L * 1024L * 1024L,
                    ),
                ).collect { event ->
                    if (event is DownloadEvent.Progress) {
                        onProgress(event.downloadedBytes, event.totalBytes)
                    }
                }
                return
            } catch (throwable: Throwable) {
                lastError = throwable
                destination.delete()
                File("${destination.absolutePath}.part").delete()
            }
        }
        throw IllegalStateException(
            "Flutter SDK 下载或 SHA-256 校验失败：${lastError?.message ?: "未知错误"}",
            lastError,
        )
    }

    private data class ReleaseInfo(
        val archive: String,
        val sha256: String,
        val version: String,
        val absoluteUrl: Boolean,
    )

    private companion object {
        // 固定 Flutter SDK 到具体 tag，避免 latest 抬高 Gradle/AGP/Kotlin 最低版本要求。
        // 本版本对应最低工具链：Gradle 8.14+ / AGP 8.11.1+ / Kotlin 2.2.20+。
        private const val PINNED_FLUTTER_TAG = "flutter-3.47.1-87-linux"
        val METADATA_URLS = listOf(
            "https://api.github.com/repos/MohamedAlkindi/flutter-native-arm64/releases/tags/$PINNED_FLUTTER_TAG",
            "https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json",
            "https://storage.flutter-io.cn/flutter_infra_release/releases/releases_linux.json",
        )
        val ARCHIVE_BASE_URLS = listOf(
            "https://storage.flutter-io.cn/flutter_infra_release/releases",
            "https://storage.googleapis.com/flutter_infra_release/releases",
        )
        // GitHub 直连在国内易被墙；下载 ARM64 发布包时优先走代理镜像（github.akams.cn 全量节点），最后回退直连。
        val GITHUB_ARCHIVE_PROXIES = listOf(
            "https://gh.dpik.top/",
            "https://github.tbap.top/",
            "https://ghfile.geekertao.top/",
            "https://ghproxy.net/",
            "https://gh-proxy.com/",
            "https://cdn.gh-proxy.com/",
            "https://github.dpik.top/",
            "https://j.1lin.dpdns.org/",
            "https://github.starrlzy.cn/",
            "https://github-proxy.memory-echoes.cn/",
            "https://git.yylx.win/",
            "https://ghm.078465.xyz/",
            "https://gh.927223.xyz/",
            "https://ghf.无名氏.top/",
            "https://gh.felicity.ac.cn/",
            "https://gh.bugdey.us.kg/",
            "https://cdn.akaere.online/",
            "https://jiashu.1win.eu.org/",
            "https://tvv.tw/",
            "https://j.1win.ggff.net/",
            "https://gitproxy.127731.xyz/",
            "https://gh.inkchills.cn/",
            "https://gh.catmak.name/",
            "https://gh.b52m.cn/",
            "https://down.mxw.xx.kg/",
            "https://down.mxw.qzz.io/",
            "https://github.mxw.qzz.io/",
            "https://gh.acmsz.top/",
            "https://gh.jjj.gv.uy/",
            "https://slink.ltd/",
            "https://github.tmby.shop/",
            "https://ghpr.cc/",
            "https://gh.tryxd.cn/",
            "https://gitproxy.click/",
            "https://github.chenc.dev/",
            "https://gh.ddlc.top/",
            "https://gitproxy.mrhjx.cn/",
            "https://gh.sixyin.com/",
            "https://gh.monlor.com/",
            "https://ghpxy.hwinzniej.top/",
            "https://git.669966.xyz/",
            "https://ghfast.top/",
            "https://gh.jasonzeng.dev/",
            "https://github.geekery.cn/",
            "https://gp.zkitefly.eu.org/",
            "https://fastgit.cc/",
            "https://ghproxy.1888866.xyz/",
            "https://ghp.arslantu.xyz/",
            "https://github.ednovas.xyz/",
            "https://ghproxy.imciel.com/",
            "https://ghproxy.cxkpro.top/",
            "https://github.xxlab.tech/",
            "https://gh.idayer.com/",
            "https://free.cn.eu.org/",
            "https://gh.chjina.com/",
            "https://ghp.keleyaa.com/",
            "https://proxy.yaoyaoling.net/",
            "https://ghproxy.monkeyray.net/",
            "https://gh.noki.icu/",
            "https://g.blfrp.cn/",
            "https://githubdog.com/",
            "https://gh.meali.top/",
            "https://777.z321.cc.cd/",
            "https://gg.z321.cc.cd/",
            "https://g.z321.cc.cd/",
            "https://js.jiangss.shop/",
            "https://gap.andyjin.website/",
            "https://gh.my-website.ccwu.cc/",
            "https://github.ikgy.top/",
            "https://gh.07150721.xyz/",
            "https://cfgh.ikgy.top/",
            "https://xsadwsd.kdns.fr/",
            "https://gh.ruan.dpdns.org/",
            "https://ghproxy.felicity.land/",
            "https://github.nswrz.cn/",
            "https://gh.zhai.edu.pl/",
            "https://gh.qfmc0721.cc.cd/",
            "https://github-cf.947563.xyz/",
            "",
        )
    }
}
