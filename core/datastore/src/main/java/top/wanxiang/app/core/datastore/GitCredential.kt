package top.wanxiang.app.core.datastore

import org.json.JSONArray
import org.json.JSONObject

/**
 * Git HTTPS 访问凭证：主机 + 用户名 + 令牌/密码。
 *
 * 用于克隆/推送 GitHub、Gitee、GitLab 等 HTTPS 私有仓库。
 * 令牌（PAT）在 GitLab 就是 Personal Access Token，在 GitHub 就是 Fine-grained / Classic PAT，
 * 在 Gitee 就是「私人令牌」，本质都是 HTTPS Basic Auth 的 password 字段。
 *
 * 主副本经 Android Keystore 加密后写入 DataStore，运行 git 时通过临时 credential.helper
 * 使用（不落 .git/config、不改 remote URL、跑完立即删除临时文件）。
 */
data class GitCredential(
    val id: String,
    val name: String,
    val host: String,
    val username: String,
    val token: String,
    val createdAtMillis: Long = 0L,
)

/** GitCredential 与 JSON 的编解码；存储层负责整体密文，这里只处理明文结构。 */
internal object GitCredentialCodec {

    fun encode(list: List<GitCredential>): String {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("host", item.host)
                    .put("username", item.username)
                    .put("token", item.token)
                    .put("createdAtMillis", item.createdAtMillis),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<GitCredential> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    add(
                        GitCredential(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            host = obj.optString("host"),
                            username = obj.optString("username"),
                            token = obj.optString("token"),
                            createdAtMillis = obj.optLong("createdAtMillis"),
                        ),
                    )
                }
            }.filter { it.id.isNotBlank() && it.host.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
