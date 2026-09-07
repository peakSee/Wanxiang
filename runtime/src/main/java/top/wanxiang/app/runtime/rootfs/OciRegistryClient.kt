package top.wanxiang.app.runtime.rootfs

import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.runtime.DistributionSpec
import top.wanxiang.app.runtime.DownloadProgress
import top.wanxiang.app.runtime.RegistryRoute
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class OciRegistryClient @Inject constructor(
    private val http: OkHttpClient,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class ImageInfo(val version: String, val digest: String)

    suspend fun resolve(
        distribution: DistributionSpec,
        route: RegistryRoute,
    ): ImageInfo = withContext(Dispatchers.IO) {
        val endpoints = when (route) {
            RegistryRoute.OFFICIAL -> listOf(Endpoint.dockerHub())
            RegistryRoute.CHINA_ACCELERATED -> listOf(Endpoint.daoCloud())
            RegistryRoute.AUTO -> listOf(Endpoint.daoCloud(), Endpoint.dockerHub())
        }
        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
            try {
                return@withContext resolveFrom(endpoint, distribution)
            } catch (failure: Throwable) {
                lastFailure = failure
                logger.w("OCI manifest check on ${endpoint.name} failed", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("没有可用的 OCI Registry 检查线路")
    }

    suspend fun pull(
        distribution: DistributionSpec,
        route: RegistryRoute,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
        resetDestination: suspend () -> Unit,
        applyLayer: suspend (File, String) -> Unit,
    ): ImageInfo = withContext(Dispatchers.IO) {
        val endpoints = when (route) {
            RegistryRoute.OFFICIAL -> listOf(Endpoint.dockerHub())
            RegistryRoute.CHINA_ACCELERATED -> listOf(Endpoint.daoCloud())
            RegistryRoute.AUTO -> listOf(Endpoint.daoCloud(), Endpoint.dockerHub())
        }
        var lastFailure: Throwable? = null
        for ((index, endpoint) in endpoints.withIndex()) {
            try {
                if (index > 0) resetDestination()
                val metadataClient = if (route == RegistryRoute.AUTO && index == 0) {
                    http.newBuilder()
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(12, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                } else {
                    http
                }
                return@withContext pullFrom(
                    endpoint,
                    distribution,
                    cacheDir,
                    onProgress,
                    applyLayer,
                    metadataClient,
                )
            } catch (failure: Throwable) {
                lastFailure = failure
                logger.w("OCI registry route ${endpoint.name} failed", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("没有可用的 OCI Registry 下载线路")
    }

    private suspend fun pullFrom(
        endpoint: Endpoint,
        distribution: DistributionSpec,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
        applyLayer: suspend (File, String) -> Unit,
        metadataClient: OkHttpClient,
    ): ImageInfo {
        cacheDir.mkdirs()
        val parsed = ImageReference.parse(distribution.imageReference)
        val repo = endpoint.rewriteRepo(parsed.repo)
        val token = endpoint.token(repo, metadataClient, json)
        var response = getManifest(
            "${endpoint.registryBase}/v2/$repo/manifests/${parsed.tag}", token,
            ACCEPT_MANIFESTS,
            metadataClient,
        )
        var manifest = response.body
        var digest = response.digest
        if (manifest["manifests"] != null) {
            val target = manifest.getValue("manifests").jsonArray.firstOrNull { entry ->
                val platform = entry.jsonObject["platform"]?.jsonObject ?: return@firstOrNull false
                platform["os"]?.jsonPrimitive?.content == "linux" &&
                    platform["architecture"]?.jsonPrimitive?.content == "arm64"
            }?.jsonObject ?: error("镜像 ${distribution.imageReference} 不提供 linux/arm64")
            digest = target.getValue("digest").jsonPrimitive.content
            response = getManifest(
                "${endpoint.registryBase}/v2/$repo/manifests/$digest", token,
                ACCEPT_MANIFESTS,
                metadataClient,
            )
            manifest = response.body
        }
        val layers = manifest["layers"]?.jsonArray ?: error("OCI manifest 没有文件系统层")
        val total = layers.sumOf { it.jsonObject["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }

        // 阶段一：受控并发下载全部层（移动网络单连接常被限速，3 路并发可显著提速；
        //         已校验缓存的层直接命中；进度按各层已下载字节汇总）
        data class LayerDownload(val file: File, val mediaType: String)
        val semaphore = Semaphore(MAX_PARALLEL_LAYER_DOWNLOADS)
        val downloaded = LongArray(layers.size)
        val blobs = coroutineScope {
            layers.mapIndexed { index, element ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val layer = element.jsonObject
                        val layerDigest = layer.getValue("digest").jsonPrimitive.content
                        val mediaType = layer["mediaType"]?.jsonPrimitive?.content.orEmpty()
                        val blob = downloadBlob(endpoint, repo, layerDigest, token, cacheDir) { current ->
                            downloaded[index] = current
                            onProgress(DownloadProgress(downloaded.sum(), total.takeIf { it > 0 }))
                        }
                        LayerDownload(blob, mediaType)
                    }
                }
            }.awaitAll()
        }

        // 阶段二：按层顺序解包（whiteout/硬链接语义依赖层顺序，不能并发）
        blobs.forEachIndexed { index, blob ->
            applyLayer(blob.file, blob.mediaType)
            onProgress(DownloadProgress(total, total.takeIf { it > 0 }))
            logger.i("Applied OCI layer ${index + 1}/${layers.size}: ${blob.file.name.takeLast(16)}")
        }
        return ImageInfo("oci-5.8.0-${distribution.id}-${parsed.tag}", digest ?: error("OCI manifest 未返回 digest"))
    }

    private fun resolveFrom(endpoint: Endpoint, distribution: DistributionSpec): ImageInfo {
        val parsed = ImageReference.parse(distribution.imageReference)
        val repo = endpoint.rewriteRepo(parsed.repo)
        val token = endpoint.token(repo, http, json)
        var response = getManifest(
            "${endpoint.registryBase}/v2/$repo/manifests/${parsed.tag}", token,
            ACCEPT_MANIFESTS,
            http,
        )
        var digest = response.digest
        if (response.body["manifests"] != null) {
            val target = response.body.getValue("manifests").jsonArray.firstOrNull { entry ->
                val platform = entry.jsonObject["platform"]?.jsonObject ?: return@firstOrNull false
                platform["os"]?.jsonPrimitive?.content == "linux" &&
                    platform["architecture"]?.jsonPrimitive?.content == "arm64"
            }?.jsonObject ?: error("镜像 ${distribution.imageReference} 不提供 linux/arm64")
            digest = target.getValue("digest").jsonPrimitive.content
            response = getManifest(
                "${endpoint.registryBase}/v2/$repo/manifests/$digest", token,
                ACCEPT_MANIFESTS,
                http,
            )
        }
        check(response.body["layers"]?.jsonArray != null) { "OCI manifest 没有文件系统层" }
        return ImageInfo("oci-5.8.0-${distribution.id}-${parsed.tag}", digest ?: error("OCI manifest 未返回 digest"))
    }

    private fun getJson(url: String, token: String, accept: String, client: OkHttpClient): JsonObject {
        val request = Request.Builder().url(url).header("Accept", accept).header("User-Agent", USER_AGENT)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Registry 请求失败 HTTP ${response.code}: $url" }
            return json.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    private data class ManifestResponse(val body: JsonObject, val digest: String?)

    private fun getManifest(url: String, token: String, accept: String, client: OkHttpClient): ManifestResponse {
        val request = Request.Builder().url(url).header("Accept", accept).header("User-Agent", USER_AGENT)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Registry 请求失败 HTTP ${response.code}: $url" }
            val raw = response.body.bytes()
            val headerDigest = response.header("Docker-Content-Digest")
            val digest = headerDigest ?: "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(raw)
                .joinToString("") { "%02x".format(it) }
            return ManifestResponse(
                body = json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject,
                digest = digest,
            )
        }
    }

    /**
     * 下载单个 OCI layer（带断点续传）：
     * - 已校验完成的缓存直接命中（`sha256-<digest>.layer`）；
     * - 中断残留的 `.part` 通过 HTTP `Range: bytes=<已有大小>-` 续传，不必整层重下；
     * - 摘要校验失败或服务器返回 416 时清空重试一次完整下载。
     */
    private suspend fun downloadBlob(
        endpoint: Endpoint,
        repo: String,
        digest: String,
        token: String,
        cacheDir: File,
        progress: suspend (Long) -> Unit,
    ): File {
        val expected = validateDigest(digest)
        val target = File(cacheDir, "sha256-$expected.layer")
        if (target.isFile && sha256(target) == expected) return target
        val partial = File(cacheDir, "sha256-$expected.part")
        var attempt = 0
        while (true) {
            attempt++
            val existing = if (partial.isFile) partial.length() else 0L
            val md = MessageDigest.getInstance("SHA-256")
            if (existing > 0) {
                // 断点续传：先累计本地已有部分的摘要，再续传剩余字节
                partial.inputStream().use { input -> hashInto(md, input) }
            }
            val request = Request.Builder().url("${endpoint.registryBase}/v2/$repo/blobs/$digest")
                .header("User-Agent", USER_AGENT)
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            var append = false
            var count = existing
            var actual: String? = null
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> append = true
                    200 -> {
                        // 服务器忽略 Range：从头重写
                        if (existing > 0) {
                            partial.delete()
                            count = 0
                            md.reset()
                        }
                    }
                    416 -> {
                        // 本地已完整：直接校验后提交
                        actual = sha256(partial)
                    }
                    else -> check(false) { "下载 OCI layer 失败 HTTP ${response.code}" }
                }
                if (actual == null) {
                    response.body.byteStream().use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                md.update(buffer, 0, read)
                                count += read
                                progress(count)
                            }
                        }
                    }
                    actual = md.digest().joinToString("") { "%02x".format(it) }
                }
            }
            if (actual == expected) {
                check(partial.renameTo(target)) { "无法提交 OCI layer 缓存" }
                return target
            }
            // 摘要不符：清空后重试（上限内）
            partial.delete()
            if (attempt >= MAX_BLOB_DOWNLOAD_ATTEMPTS) {
                check(false) { "OCI layer 摘要校验失败：期望 $expected（已重试 $MAX_BLOB_DOWNLOAD_ATTEMPTS 次）" }
            }
        }
    }

    private fun validateDigest(value: String): String {
        val match = Regex("^sha256:([0-9a-fA-F]{64})$").matchEntire(value)
            ?: throw SecurityException("非法或不支持的 OCI digest：$value")
        return match.groupValues[1].lowercase()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> hashInto(md, input) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashInto(md: MessageDigest, input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
    }

    private data class ImageReference(val repo: String, val tag: String) {
        companion object {
            fun parse(value: String): ImageReference {
                val last = value.substringAfterLast('/')
                val tag = if (':' in last) last.substringAfterLast(':') else "latest"
                val name = if (':' in last) value.substringBeforeLast(':') else value
                return ImageReference(if ('/' in name) name else "library/$name", tag)
            }
        }
    }

    private data class Endpoint(
        val name: String,
        val registryBase: String,
        val rewriteRepo: (String) -> String,
        val tokenProvider: (String, OkHttpClient, Json) -> String,
    ) {
        fun token(repo: String, http: OkHttpClient, json: Json) = tokenProvider(repo, http, json)

        companion object {
            fun dockerHub() = Endpoint("Docker Hub", "https://registry-1.docker.io", { it }) { repo, http, json ->
                val scope = URLEncoder.encode("repository:$repo:pull", Charsets.UTF_8.name())
                val request = Request.Builder().url("https://auth.docker.io/token?service=registry.docker.io&scope=$scope")
                    .header("User-Agent", USER_AGENT).build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Docker Hub 鉴权失败 HTTP ${response.code}" }
                    val body = json.parseToJsonElement(response.body.string()).jsonObject
                    body["token"]?.jsonPrimitive?.content ?: body["access_token"]?.jsonPrimitive?.content.orEmpty()
                }
            }

            fun daoCloud() = Endpoint(
                "DaoCloud public mirror",
                "https://docker.m.daocloud.io",
                { it },
                { repo, http, json ->
                    val scope = URLEncoder.encode("repository:$repo:pull", Charsets.UTF_8.name())
                    val request = Request.Builder()
                        .url("https://m.daocloud.io/auth/token?service=docker.m.daocloud.io&scope=$scope")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    http.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "DaoCloud 鉴权失败 HTTP ${response.code}" }
                        json.parseToJsonElement(response.body.string()).jsonObject["token"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    }
                },
            )
        }
    }

    private companion object {
        const val USER_AGENT = "WanXiang/proot-distro-5.8.0"
        const val ACCEPT_MANIFESTS =
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"
        /** OCI layer 并行下载并发数（移动网络单连接限速时多路并发可显著提速）。 */
        const val MAX_PARALLEL_LAYER_DOWNLOADS = 3
        /** 单个 layer 摘要校验失败后的完整重试次数上限。 */
        const val MAX_BLOB_DOWNLOAD_ATTEMPTS = 2
    }
}
