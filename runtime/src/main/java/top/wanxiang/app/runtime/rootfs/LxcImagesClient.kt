package top.wanxiang.app.runtime.rootfs

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.runtime.DistributionSpec
import top.wanxiang.app.runtime.DownloadProgress

/**
 * lxc-images rootfs 下载兜底通道（单文件 `rootfs.tar.xz` + SHA256SUMS 摘要校验）。
 *
 * 镜像源按顺序尝试：清华 TUNA（国内加速，同步大部分发行版）→ LXC 官方源
 * （images.linuxcontainers.org，覆盖 TUNA 未同步的发行版，如 archlinux 的 arm64）。
 * 仅作为 OCI 线路（DaoCloud / Docker Hub）全部失败后的兜底，且只支持映射到
 * lxc-images 的发行版。
 */
@Singleton
class LxcImagesClient @Inject constructor(
    private val http: OkHttpClient,
    private val logger: AppLogger,
) {
    /** 该发行版是否提供 lxc-images 兜底镜像。 */
    fun supports(distributionId: String): Boolean = lxcPathFor(distributionId) != null

    suspend fun pull(
        distribution: DistributionSpec,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
        applyLayer: suspend (File, String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val lxcPath = lxcPathFor(distribution.id)
            ?: error("lxc-images 暂不支持发行版 ${distribution.id}")
        cacheDir.mkdirs()
        var lastFailure: Throwable? = null
        for (base in MIRROR_BASES) {
            try {
                val buildDir = resolveLatestBuild(base, lxcPath)
                val expectedSha = fetchExpectedSha256(base, lxcPath, buildDir)
                val blob = downloadRootfs(base, lxcPath, buildDir, expectedSha, cacheDir, onProgress)
                applyLayer(blob, MEDIA_TYPE_ROOTFS_TAR_XZ)
                return@withContext "lxc-5.8.0-${distribution.id}-$buildDir"
            } catch (failure: Throwable) {
                lastFailure = failure
                logger.w("lxc-images 镜像源 $base 不可用（${distribution.id}）", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("没有可用的 lxc-images 镜像源")
    }

    /** 抓取目录列表并解析出最新一次构建的时间戳目录（形如 20260820_05:24）。 */
    private fun resolveLatestBuild(mirrorBase: String, lxcPath: String): String {
        val url = "$mirrorBase/$lxcPath/arm64/default/"
        val body = fetchText(url, metadataClient())
        val builds = Regex("(\\d{8}_\\d{2}(?:%3A|:)\\d{2})/")
            .findAll(body)
            .map { it.groupValues[1].replace("%3A", ":") }
            .distinct()
            .sorted()
            .toList()
        check(builds.isNotEmpty()) { "lxc-images 目录解析失败：$url" }
        return builds.last()
    }

    /** 读取构建目录中的 SHA256SUMS，取 rootfs.tar.xz 的摘要。 */
    private fun fetchExpectedSha256(mirrorBase: String, lxcPath: String, buildDir: String): String {
        val url = buildUrl(mirrorBase, lxcPath, buildDir, "SHA256SUMS")
        val body = fetchText(url, metadataClient())
        val expected = body.lineSequence()
            .firstOrNull { it.trimEnd().endsWith("rootfs.tar.xz") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()
            ?: error("SHA256SUMS 中找不到 rootfs.tar.xz 条目：$url")
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "lxc-images SHA256SUMS 摘要格式非法：$expected" }
        return expected
    }

    private suspend fun downloadRootfs(
        mirrorBase: String,
        lxcPath: String,
        buildDir: String,
        expectedSha: String,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): File {
        val target = File(cacheDir, "sha256-$expectedSha.rootfs.tar.xz")
        if (target.isFile && sha256(target) == expectedSha) {
            logger.i("lxc-images layer cache hit: ${target.name}")
            return target
        }
        val partial = File(cacheDir, "sha256-$expectedSha.part")
        val url = buildUrl(mirrorBase, lxcPath, buildDir, "rootfs.tar.xz")
        // 断点续传：中断残留的 .part 通过 Range 续传，避免大文件整包重下
        val existing = if (partial.isFile) partial.length() else 0L
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        http.newCall(request).execute().use { response ->
            val append = response.code == 206
            if (response.code != 206 && response.code != 200) {
                check(false) { "下载 lxc-images rootfs 失败 HTTP ${response.code}" }
            }
            val md = MessageDigest.getInstance("SHA-256")
            if (append && existing > 0) {
                partial.inputStream().use { input -> hashInto(md, input) }
            } else if (existing > 0) {
                partial.delete()
            }
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { if (append) existing + it else it }
            var count = if (append) existing else 0L
            response.body.byteStream().use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        md.update(buffer, 0, read)
                        count += read
                        onProgress(DownloadProgress(count, total))
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            check(actual == expectedSha) { "lxc-images rootfs 摘要校验失败：期望 $expectedSha，实际 $actual" }
        }
        check(partial.renameTo(target)) { "无法提交 lxc-images rootfs 缓存" }
        return target
    }

    private fun hashInto(md: MessageDigest, input: java.io.InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
    }

    /** 时间戳目录名中的冒号需要编码，避免部分 HTTP 栈拒绝路径。 */
    private fun buildUrl(mirrorBase: String, lxcPath: String, buildDir: String, fileName: String): String =
        "$mirrorBase/$lxcPath/arm64/default/${buildDir.replace(":", "%3A")}/$fileName"

    private fun fetchText(url: String, client: OkHttpClient): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "lxc-images 元数据请求失败 HTTP ${response.code}: $url" }
            return response.body.string()
        }
    }

    /** 元数据请求（目录列表 / SHA256SUMS）使用短超时，避免兜底线路拖慢整体安装。 */
    private fun metadataClient(): OkHttpClient = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 依次尝试的镜像源：国内加速优先，官方源兜底（覆盖 TUNA 未同步的发行版）。 */
        val MIRROR_BASES = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images",
            "https://images.linuxcontainers.org/images",
        )
        const val USER_AGENT = "WanXiang/proot-distro-5.8.0"
        const val MEDIA_TYPE_ROOTFS_TAR_XZ = "application/x-tar.xz"

        /**
         * 发行版 id → lxc-images 路径映射。
         * 注意：lxc-images 只有 default（极简）rootfs，不含 buildpack-deps 工具链，
         * 因此仅作为兜底线路，不能等价替换 OCI 镜像。
         */
        fun lxcPathFor(distributionId: String): String? = when (distributionId.lowercase()) {
            "debian" -> "debian/bookworm"
            "ubuntu" -> "ubuntu/noble"
            "kali" -> "kali/current"
            "arch" -> "archlinux/current"
            else -> null
        }
    }
}
