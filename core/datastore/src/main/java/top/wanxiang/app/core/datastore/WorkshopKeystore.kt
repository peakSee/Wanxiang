package top.wanxiang.app.core.datastore

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工坊 Android 签名（Keystore）记录。
 *
 * 主副本保存在宿主应用私有目录，元数据（含口令）整体加密后写入 DataStore，
 * 构建时按需同步进当前 Linux 沙箱 /opt/wanxiang/keystores/ 并注入 Gradle 签名配置。
 */
data class WorkshopKeystore(
    val id: String,
    val name: String,
    val fileName: String,
    val alias: String,
    val storePassword: String,
    val keyPassword: String,
    val validityYears: Int = 25,
    val organization: String = "",
    val createdAtMillis: Long = 0L,
) {
    companion object {
        const val DEFAULT_VALIDITY_YEARS = 25
    }
}

/** WorkshopKeystore 与 JSON 的编解码；存储层负责整体密文，这里只处理明文结构。 */
internal object WorkshopKeystoreCodec {

    fun encode(list: List<WorkshopKeystore>): String {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("fileName", item.fileName)
                    .put("alias", item.alias)
                    .put("storePassword", item.storePassword)
                    .put("keyPassword", item.keyPassword)
                    .put("validityYears", item.validityYears)
                    .put("organization", item.organization)
                    .put("createdAtMillis", item.createdAtMillis),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<WorkshopKeystore> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    add(
                        WorkshopKeystore(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            fileName = obj.optString("fileName"),
                            alias = obj.optString("alias"),
                            storePassword = obj.optString("storePassword"),
                            keyPassword = obj.optString("keyPassword"),
                            validityYears = obj.optInt("validityYears", WorkshopKeystore.DEFAULT_VALIDITY_YEARS),
                            organization = obj.optString("organization"),
                            createdAtMillis = obj.optLong("createdAtMillis"),
                        ),
                    )
                }
            }.filter { it.id.isNotBlank() && it.fileName.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
