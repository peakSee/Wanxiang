package top.wanxiang.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wanxiang.app.core.model.CloudAnnouncement
import top.wanxiang.app.core.model.CloudLatestVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 万象云端服务客户端：远程配置 + 公告。
 *
 * 三个接口均为匿名（无需登录/Header），统一响应 `{"code":0,"msg":"","data":...}`。
 * 网络失败由调用方静默降级——云端不可达时版本检查回退纯 GitHub、镜像回退直连。
 */
@Singleton
class WanxiangCloudClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 拉取全部远程配置（Map<String,String>，值可能是 JSON 字符串）。 */
    suspend fun getAllConfig(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = executeGet("$BASE_URL/app-api/wanxiang/config/get-all")
            val root = json.parseToJsonElement(body).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 0) throw IllegalStateException("云端配置 code=$code")
            val data = root["data"]?.jsonObject ?: return@runCatching emptyMap()
            data.entries.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: v.toString()) }
        }
    }

    /** 拉取公告列表（按时间倒序）。 */
    suspend fun getAnnouncements(count: Int = 10): Result<List<CloudAnnouncement>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = executeGet("$BASE_URL/app-api/wanxiang/announcement/list?count=${count.coerceIn(1, 50)}")
            val root = json.parseToJsonElement(body).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 0) throw IllegalStateException("公告 code=$code")
            val arr = root["data"]?.jsonArray ?: return@runCatching emptyList()
            arr.map { el ->
                val obj = el.jsonObject
                CloudAnnouncement(
                    id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    type = obj["type"]?.jsonPrimitive?.intOrNull ?: 1,
                    content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    createTime = obj["createTime"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            }
        }
    }

    /** 从 get-all 结果解析云端版本信息（键 wanxiang.latest_version）。 */
    fun parseLatestVersion(config: Map<String, String>): CloudLatestVersion? {
        val raw = config["wanxiang.latest_version"] ?: return null
        return runCatching { json.decodeFromString(CloudLatestVersion.serializer(), raw) }.getOrNull()
    }

    /** 从 get-all 结果解析 GitHub 加速镜像前缀列表（键 wanxiang.github_mirror_urls）。 */
    fun parseMirrorUrls(config: Map<String, String>): List<String> {
        val raw = config["wanxiang.github_mirror_urls"] ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull().orEmpty()
    }

    private val configMapSerializer = MapSerializer(String.serializer(), String.serializer())

    /** 序列化配置 Map 为 JSON 字符串（缓存写入）。 */
    fun encodeConfig(config: Map<String, String>): String = json.encodeToString(configMapSerializer, config)

    /** 反序列化缓存 JSON 为配置 Map（离线兜底读取）。 */
    fun decodeConfig(cachedJson: String): Map<String, String> =
        if (cachedJson.isBlank()) emptyMap()
        else runCatching { json.decodeFromString(configMapSerializer, cachedJson) }.getOrNull().orEmpty()

    private fun executeGet(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "WanXiang-App").get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("云端响应错误 HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        // 生产环境基址（wanxiang.babm.cn，宝塔 Nginx 反代到 yudao 后端）
        const val BASE_URL = "http://wanxiang.babm.cn"
    }
}
