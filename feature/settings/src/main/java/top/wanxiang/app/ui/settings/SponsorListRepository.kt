package top.wanxiang.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 赞助者/贡献者名单仓库。
 *
 * 名单外置在 GitHub 仓库根目录的 sponsors.json 中，App 只在赞助页面被打开时才请求一次，
 * 因此新增赞助者/贡献者只需修改 JSON 并推送，无需重新发布版本。
 *
 * sponsors.json 格式（kind/source 均与下方枚举的 @SerialName 对应）：
 * {
 *   "entries": [
 *     { "kind": "funding",      "name": "阿伟",   "source": "qq",    "identifier": "10001" },
 *     { "kind": "resource",     "name": "peakSee",  "source": "github", "identifier": "peakSee", "note": "提供推理 token" },
 *     { "kind": "contribution", "name": "路人甲", "source": "github", "identifier": "lurenjia", "note": "上报多窗口闪退" }
 *   ]
 * }
 */
@Serializable
enum class SponsorSource {
    @SerialName("github") GitHub,
    @SerialName("qq") Qq,
}

/** 贡献类型：资金赞助 / 资源赞助(如提供 token、测试设备) / 贡献(提 bug、PR、建议) */
@Serializable
enum class SponsorKind {
    @SerialName("funding") Funding,
    @SerialName("resource") Resource,
    @SerialName("contribution") Contribution,
}

/** 鸣谢名单条目：name 为展示昵称，identifier 为 GitHub 用户名或 QQ 号，note 为补充说明 */
@Serializable
data class SponsorEntry(
    val kind: SponsorKind,
    val name: String,
    val source: SponsorSource,
    val identifier: String,
    val note: String? = null,
) {
    /** 按来源生成公开头像地址：GitHub 用户头像 / QQ 公开头像 */
    val avatarUrl: String
        get() = when (source) {
            SponsorSource.GitHub -> "https://avatars.githubusercontent.com/$identifier?s=96"
            SponsorSource.Qq -> "https://q1.qlogo.cn/g?b=qq&nk=$identifier&s=96"
        }
}

@Serializable
data class SponsorListDto(val entries: List<SponsorEntry> = emptyList())

@Singleton
class SponsorListRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 拉取 GitHub 上的赞助者名单。仅在赞助页面打开时调用。
     */
    suspend fun loadEntries(): Result<List<SponsorEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(SPONSORS_JSON_URL)
                .header("User-Agent", "WanXiang-App")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("名单加载失败 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    throw IllegalStateException("名单内容为空")
                }
                json.decodeFromString(SponsorListDto.serializer(), body).entries
            }
        }
    }

    companion object {
        // TODO: 把仓库根目录的 sponsors.json 推送到 GitHub 后，确认该地址可访问
        const val SPONSORS_JSON_URL = "https://raw.githubusercontent.com/peakSee/Wanxiang/main/sponsors.json"
    }
}
