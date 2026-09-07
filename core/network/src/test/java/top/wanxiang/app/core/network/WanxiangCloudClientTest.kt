package top.wanxiang.app.core.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云端客户端解析逻辑的假数据单元测试（不发起网络）。 */
class WanxiangCloudClientTest {

    private val client = WanxiangCloudClient(OkHttpClient())

    @Test
    fun `parseLatestVersion 解析版本 JSON`() {
        val config = mapOf(
            "wanxiang.latest_version" to
                """{"versionName":"1.2.0","versionCode":20,"apkUrl":"http://154.222.25.54/x.apk","changelog":"修复若干问题","forceUpdate":true}"""
        )
        val v = client.parseLatestVersion(config)
        assertEquals("1.2.0", v?.versionName)
        assertEquals(20, v?.versionCode)
        assertEquals("http://154.222.25.54/x.apk", v?.apkUrl)
        assertEquals("修复若干问题", v?.changelog)
        assertEquals(true, v?.forceUpdate)
    }

    @Test
    fun `parseLatestVersion 键缺失返回 null`() {
        assertNull(client.parseLatestVersion(emptyMap()))
        assertNull(client.parseLatestVersion(mapOf("wanxiang.latest_version" to "不是合法JSON")))
    }

    @Test
    fun `parseMirrorUrls 解析镜像列表`() {
        val config = mapOf(
            "wanxiang.github_mirror_urls" to """["https://ghproxy.net/","https://gh-proxy.com/"]"""
        )
        assertEquals(listOf("https://ghproxy.net/", "https://gh-proxy.com/"), client.parseMirrorUrls(config))
    }

    @Test
    fun `parseMirrorUrls 键缺失返回空`() {
        assertTrue(client.parseMirrorUrls(emptyMap()).isEmpty())
        assertTrue(client.parseMirrorUrls(mapOf("wanxiang.github_mirror_urls" to "非法")).isEmpty())
    }

    @Test
    fun `encodeConfig 与 decodeConfig 往返`() {
        val config = mapOf(
            "wanxiang.latest_version" to """{"versionCode":3}""",
            "wanxiang.github_mirror_urls" to """["https://a.b/"]""",
        )
        val json = client.encodeConfig(config)
        assertEquals(config, client.decodeConfig(json))
    }

    @Test
    fun `decodeConfig 空串返回空 Map`() {
        assertTrue(client.decodeConfig("").isEmpty())
        assertTrue(client.decodeConfig("非法JSON").isEmpty())
    }
}
